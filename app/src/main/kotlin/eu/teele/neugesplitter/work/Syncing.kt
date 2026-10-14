package eu.teele.neugesplitter.work

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.crypto.AgeCodec
import eu.teele.neugesplitter.format.crypto.GroupKey
import eu.teele.neugesplitter.format.crypto.TooLittleMemory
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
import eu.teele.neugesplitter.sync.probe.locked
import eu.teele.neugesplitter.sync.writer.Debounce
import eu.teele.neugesplitter.sync.writer.Upload
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Every shared group's sync while the app runs: a pass shortly after local changes stop, bounded
 * (TR-14, SY-14), at once when asked (TR-13), and in the background at most as often as the group
 * asks (TR-9, SY-13). How each pass ended is recorded for SY-6, and one that could not reach the
 * backend goes again after a while.
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
    private val polls: Polls,
    private val quiet: Duration = 5.seconds,
    private val bound: Duration = 30.seconds,
    /** The first wait before a pass that could not reach the backend goes again; it doubles. */
    private val retry: Duration = 30.seconds,
    private val context: CoroutineContext = Dispatchers.IO,
    /** Told about each group a pass succeeded over, which may have received changes (SY-10). */
    private val received: suspend (GroupId) -> Unit = {},
) {
    private val running = ConcurrentHashMap<GroupId, Runner>()
    private val opened = AtomicBoolean()
    /** Fetching for a join and giving up on one take turns, so nothing fetched outlives either. */
    private val joining = Mutex()

    /**
     * Starts every group shared already: what is recorded goes up, and each is polled in the
     * background. A process started only to poll makes no request beyond the poll's own. What a
     * join left before anybody was chosen is removed first (BE-6).
     */
    fun start() {
        scope.launch(context) {
            joining.withLock { groups.unclaimed().forEach(::discard) }
            groups.backends.connected().forEach {
                runner(it)
                schedule(it)
            }
        }
    }

    /** Fetches what others wrote meanwhile into every shared group, once per process (SY-2). */
    fun opened() {
        if (opened.getAndSet(true)) return
        scope.launch(context) { groups.backends.connected().forEach { runner(it).debounce.now() } }
    }

    /**
     * Runs a pass over group [id] and returns once it has ended, as a background poll does (TR-9).
     * A group no longer shared is polled no more.
     */
    suspend fun poll(id: GroupId) =
        withContext(context) {
            if (groups.backends.settings(id) == null) return@withContext polls.cancel(id)
            runner(id).pass()
        }

    /** Has group [id] polled at most every [interval] from now on (SY-13). */
    fun interval(id: GroupId, interval: Duration) {
        groups.backends.setInterval(id, interval)
        polls.schedule(id, interval)
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
            schedule(id)
            true
        }

    /** Runs a pass over group [id] without waiting (SY-9). */
    fun now(id: GroupId) {
        running[id]?.debounce?.now()
    }

    /**
     * The group whose log follows the encrypted [root] in [store], once [passphrase] opens it; its
     * key is held for [fetch]. Null if no group's log follows it. [passphrase] stays the caller's.
     *
     * @throws WrongPassphrase if [passphrase] does not open [root].
     * @throws eu.teele.neugesplitter.format.crypto.TooLittleMemory if scrypt does not fit.
     * @throws StoreException if the backend could not be asked.
     */
    suspend fun unlock(store: LogStore, root: ByteArray, passphrase: CharArray): GroupId? =
        withContext(context) {
            val key =
                try {
                    withContext(Dispatchers.Default) { codec.unlock(root, passphrase) }
                } catch (_: Malformed) {
                    return@withContext null
                } catch (_: Unsupported) {
                    return@withContext null
                }
            locked(store, root, Sealing.Encrypted(codec, key))?.also { keyring.hold(it, key) }
        }

    /**
     * Fetches group [id]'s history from [store] for a join, before anybody is chosen to act as in
     * it (SY-7, BE-6); [root] is an encrypted group's, [unlock]ed already. Until [join], it is
     * [abandon]ed or removed at the next start.
     *
     * @return where the history stops being one that can be applied, keeping none of it; null once
     *   all of it is held.
     * @throws StoreException if the backend could not be asked; what was fetched stays for a retry.
     */
    suspend fun fetch(id: GroupId, store: LogStore, root: ByteArray?): Upload.Halted? =
        withContext(context) {
            joining.withLock {
                val local = groups.local(id, today, context)
                val sealing =
                    if (root == null) Sealing.Plain
                    else Sealing.Encrypted(codec, checkNotNull(keyring[id]) { "not unlocked" })
                if (root != null && local.head() == null) local.rooted(root)
                val halted = GroupSync(store, local, sealing).pass() as? Upload.Halted
                if (halted != null) discard(id)
                halted
            }
        }

    /**
     * Joins group [id], [fetch]ed already, acting as [me] after appending [events] (BE-6), and
     * shares it through [settings] from then on. [passphrase], an encrypted group's, stays the
     * caller's.
     */
    suspend fun join(
        id: GroupId,
        settings: BackendSettings,
        secretKey: String,
        passphrase: CharArray?,
        me: MemberId,
        events: List<Event>,
    ) {
        withContext(context) {
            joining.withLock {
                secrets.keepSecretKey(id, secretKey)
                passphrase?.let { secrets.keepPassphrase(id, it) }
                groups.backends.connect(id, settings)
                if (events.isNotEmpty()) groups.append(id, events, today())
                // Acting as somebody is what makes the group this installation's to keep.
                groups.actAs(id, me)
            }
            runner(id).debounce.now()
            schedule(id)
        }
    }

    /** Removes what joining group [id] fetched, unless it was joined after all. */
    fun abandon(id: GroupId) {
        scope.launch(context) { joining.withLock { if (groups.actingAs(id) == null) discard(id) } }
    }

    /** Stops syncing group [id] and forgets its secrets, as deleting it does (GR-11). */
    fun forget(id: GroupId) {
        running.remove(id)?.scope?.cancel()
        polls.cancel(id)
        secrets.forget(id)
    }

    /** [forget]s every group (PR-9). */
    fun forgetAll() {
        running.keys.toList().forEach { running.remove(it)?.scope?.cancel() }
        polls.cancelAll()
        secrets.forgetAll()
    }

    private fun runner(id: GroupId): Runner = running.computeIfAbsent(id, ::Runner)

    private fun discard(id: GroupId) {
        groups.delete(id)
        secrets.forget(id)
    }

    private suspend fun schedule(id: GroupId) {
        polls.schedule(id, groups.backends.interval(id, context).first() ?: return)
    }

    private inner class Runner(private val id: GroupId) {

        val scope =
            CoroutineScope(
                this@Syncing.scope.coroutineContext +
                    SupervisorJob(this@Syncing.scope.coroutineContext[Job]) +
                    context
            )
        val debounce = Debounce(scope, quiet, bound) { pass() }
        private val lock = Mutex()
        private var sync: GroupSync? = null
        private var again: Job? = null
        private var failures = 0

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

        /** One at a time, so that a poll and a manual sync share one [GroupSync] (TR-15). */
        suspend fun pass() = lock.withLock { attempt() }

        private suspend fun attempt() {
            again?.cancel()
            val sync = sync ?: built() ?: return groups.backends.failed(id, Failure.LOCKED)
            this.sync = sync
            try {
                when (val upload = sync.pass()) {
                    is Upload.Halted -> groups.backends.halted(id, upload)
                    else -> groups.backends.succeeded(id, now())
                }
                failures = 0
                received(id)
            } catch (e: StoreException) {
                val failure = Failure.of(e)
                groups.backends.failed(id, failure)
                // Denied and missing wait for a person; the others may pass on their own.
                if (failure == Failure.UNREACHABLE || failure == Failure.FAILED) {
                    val wait = retry * (1 shl minOf(failures++, 4))
                    again =
                        scope.launch {
                            delay(wait)
                            debounce.now()
                        }
                }
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
                    } catch (_: TooLittleMemory) {
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
