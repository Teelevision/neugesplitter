package eu.teele.neugesplitter.sync

import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.crypto.AgeCodec
import eu.teele.neugesplitter.format.crypto.GroupKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The group keys unlocked this session. Each is unlocked once, off the main thread, and reading an
 * object only ever takes what is held here (TR-26).
 */
class Keyring(
    private val codec: AgeCodec,
    private val context: CoroutineContext = Dispatchers.Default,
) {
    private val keys = ConcurrentHashMap<GroupId, GroupKey>()

    // One at a time: each unlock takes 256 MiB (format-spec 5.1).
    private val mutex = Mutex()

    private val _unlocking = MutableStateFlow<Set<GroupId>>(emptySet())

    /** The groups being unlocked right now, for the progress TR-26 asks for. */
    val unlocking: StateFlow<Set<GroupId>> = _unlocking.asStateFlow()

    /** [group]'s key, if it has been unlocked this session. Never unlocks. */
    operator fun get(group: GroupId): GroupKey? = keys[group]

    /** Holds [key] for [group] without unlocking, as when the group was just created with it. */
    fun hold(group: GroupId, key: GroupKey) {
        keys[group] = key
    }

    /**
     * [group]'s key from its [root] object, unlocking only if it is not held yet. [passphrase] is
     * wiped either way (TR-25).
     *
     * @throws eu.teele.neugesplitter.format.crypto.WrongPassphrase if it does not open [root].
     */
    suspend fun unlock(group: GroupId, root: ByteArray, passphrase: CharArray): GroupKey {
        try {
            keys[group]?.let {
                return it
            }
            _unlocking.update { it + group }
            try {
                return mutex.withLock {
                    keys[group]
                        ?: withContext(context) { codec.unlock(root, passphrase) }
                            .also { keys[group] = it }
                }
            } finally {
                _unlocking.update { it - group }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }
}
