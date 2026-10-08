package eu.teele.neugesplitter.work

import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.crypto.AgeCodec
import eu.teele.neugesplitter.format.crypto.GroupKey
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.storage.settings.Failure
import eu.teele.neugesplitter.storage.settings.Secrets
import eu.teele.neugesplitter.sync.GroupSync
import eu.teele.neugesplitter.sync.Keyring
import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.Sealing
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.writer.Debounce
import eu.teele.neugesplitter.sync.writer.Upload
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every shared group's sync while the app runs: a pass shortly after local changes stop, bounded
 * (TR-14, SY-14), or at once when asked (TR-13). How each pass ended is recorded for SY-6.
 */
class Syncing(
    private val groups: GroupRepository,
    private val secrets: Secrets,
    private val keyring: Keyring,
    private val codec: AgeCodec,
    private val scope: CoroutineScope,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    /** The backend at a location, given its secret key. */
    private val stores: (BackendSettings, String) -> LogStore,
    private val quiet: Duration = 5.seconds,
    private val bound: Duration = 30.seconds,
    private val context: CoroutineContext = Dispatchers.IO,
) {
    private val running = ConcurrentHashMap<GroupId, Runner>()

    /** Starts every group shared already; anything waiting goes up after the usual wait. */
    fun start() {
        scope.launch(context) { groups.backends.connected().forEach(::runner) }
    }

    /**
     * A new encrypted group's key and root object, locked with [passphrase] (format-spec 5.1). It
     * is made once and handed to every [connect] attempt, so that a retry publishes the same bytes.
     * Unlike [Keyring.unlock], [passphrase] is kept, for [connect] to seal; [NewKey.wipe] wipes it.
     */
    suspend fun newKey(passphrase: CharArray): NewKey =
        withContext(Dispatchers.Default) {
            val key = codec.generate()
            NewKey(key, codec.lock(key, passphrase.copyOf()), passphrase)
        }

    /**
     * Shares group [id] through the backend at [settings] and syncs it at once. An encrypted group
     * publishes its [newKey]'s root object first, and its log follows it.
     *
     * @return false, keeping nothing, if the location already holds another root object (BE-9).
     * @throws StoreException if the backend could not be asked; nothing is kept then either.
     */
    suspend fun connect(
        id: GroupId,
        settings: BackendSettings,
        secretKey: String,
        newKey: NewKey? = null,
    ): Boolean =
        withContext(context) {
            running.remove(id)?.scope?.cancel()
            val store = stores(settings, secretKey)
            if (newKey != null) {
                val sealing = Sealing.Encrypted(codec, newKey.key)
                val published =
                    GroupSync(store, groups.local(id, today), sealing).publish(newKey.root)
                if (!published) return@withContext false
                secrets.keepPassphrase(id, newKey.passphrase)
                keyring.hold(id, newKey.key)
            }
            secrets.keepSecretKey(id, secretKey)
            groups.backends.connect(id, settings)
            runner(id).debounce.now()
            true
        }

    /** Runs a pass over group [id] without waiting (SY-9). */
    fun now(id: GroupId) {
        running[id]?.debounce?.now()
    }

    /** Stops syncing group [id] and forgets its secrets, as deleting it does (GR-11). */
    fun forget(id: GroupId) {
        running.remove(id)?.scope?.cancel()
        secrets.forget(id)
    }

    /** [forget]s every group (PR-9). */
    fun forgetAll() {
        running.keys.toList().forEach { running.remove(it)?.scope?.cancel() }
        secrets.forgetAll()
    }

    private fun runner(id: GroupId): Runner = running.computeIfAbsent(id, ::Runner)

    private inner class Runner(private val id: GroupId) {

        val scope =
            CoroutineScope(
                this@Syncing.scope.coroutineContext +
                    SupervisorJob(this@Syncing.scope.coroutineContext[Job]) +
                    context
            )
        val debounce = Debounce(scope, quiet, bound) { pass() }
        private var sync: GroupSync? = null

        init {
            scope.launch {
                groups.backends
                    .state(id, context)
                    .map { it?.waiting ?: 0 }
                    .distinctUntilChanged()
                    .filter { it > 0 }
                    .collect { debounce.changed() }
            }
        }

        private suspend fun pass() {
            val sync = sync ?: built() ?: return groups.backends.failed(id, Failure.LOCKED)
            this.sync = sync
            try {
                when (val upload = sync.pass()) {
                    is Upload.Halted -> groups.backends.halted(id, upload)
                    else -> groups.backends.succeeded(id, now())
                }
            } catch (e: StoreException) {
                groups.backends.failed(id, Failure.of(e))
            }
        }

        /** The group's sync, or null while a secret it needs is missing or no longer opens. */
        private suspend fun built(): GroupSync? {
            val settings = groups.backends.settings(id) ?: return null
            val secretKey = secrets.secretKey(id) ?: return null
            val root = groups.backends.root(id)
            val sealing =
                if (root == null) Sealing.Plain
                else Sealing.Encrypted(codec, key(root) ?: return null)
            return GroupSync(stores(settings, secretKey), groups.local(id, today, context), sealing)
        }

        private suspend fun key(root: ByteArray): GroupKey? =
            keyring[id]
                ?: secrets.passphrase(id)?.let {
                    try {
                        keyring.unlock(id, root, it)
                    } catch (_: WrongPassphrase) {
                        null
                    }
                }
    }
}

/** What [Syncing.newKey] made: the key, its root object and the passphrase that locks it. */
class NewKey
internal constructor(
    internal val key: GroupKey,
    internal val root: ByteArray,
    internal val passphrase: CharArray,
) {
    fun wipe() {
        passphrase.fill('\u0000')
    }
}
