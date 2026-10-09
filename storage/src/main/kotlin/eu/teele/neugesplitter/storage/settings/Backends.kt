package eu.teele.neugesplitter.storage.settings

import app.cash.sqldelight.coroutines.asFlow
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Which groups are shared through a backend, where it is, and how their sync went (SY-6). */
class Backends internal constructor(private val database: Database) {

    private val settings = database.backendSettingsQueries
    private val states = database.syncStateQueries

    /** Shares group [id] through the backend [at], or moves it there. */
    fun connect(id: GroupId, at: BackendSettings) {
        settings.connect(
            id.hex,
            at.endpoint,
            at.bucket,
            at.prefix,
            at.region,
            if (at.pathStyle) 1 else 0,
            at.accessKey,
        )
    }

    /** Where group [id]'s backend is, or null for a purely local group (BE-1). */
    fun settings(id: GroupId): BackendSettings? =
        settings
            .settings(id.hex) { endpoint, bucket, prefix, region, pathStyle, accessKey ->
                BackendSettings(endpoint, bucket, prefix, region, pathStyle != 0L, accessKey)
            }
            .executeAsOneOrNull()

    /** Every group shared through a backend. */
    fun connected(): List<GroupId> = settings.connected().executeAsList().map(GroupId::parse)

    /**
     * The shortest wait between group [id]'s background polls (SY-13), again whenever it changes;
     * null while it has no backend.
     */
    fun interval(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<Duration?> =
        settings
            .interval(id.hex)
            .asFlow()
            .map { it.executeAsOneOrNull()?.minutes }
            .distinctUntilChanged()
            .flowOn(context)

    /** Has group [id] wait at least [interval] between background polls (SY-13, TR-10). */
    fun setInterval(id: GroupId, interval: Duration) {
        require(interval >= SHORTEST_INTERVAL) { "Android polls no more often than this" }
        settings.setInterval(interval.inWholeMinutes, id.hex)
    }

    /** An encrypted group's root object, which its key is unlocked from; null for any other. */
    fun root(id: GroupId): ByteArray? = database.logObjectQueries.root(id.hex).executeAsOneOrNull()

    /** Group [id]'s object 0, encrypted or not, which says whose log a location holds (BE-9). */
    fun first(id: GroupId): ByteArray? =
        database.logObjectQueries.first(id.hex).executeAsOneOrNull()

    /** A pass over group [id] ended [at] with nothing between it and its backend (TR-12). */
    fun succeeded(id: GroupId, at: Instant) {
        states.succeeded(id.hex, at.toEpochMilli())
    }

    /** Group [id] stopped syncing as [halted] says (SY-15). */
    fun halted(id: GroupId, halted: Upload.Halted) {
        states.halted(id.hex, halted.number.value.toLong(), halted.reason.name)
    }

    /** A pass over group [id] could not finish, as [failure] says. */
    fun failed(id: GroupId, failure: Failure) {
        states.failed(id.hex, failure.name)
    }

    /** How group [id]'s sync stands, again whenever that changes; null while it has no backend. */
    fun state(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<SyncState?> {
        val connected = settings.settings(id.hex)
        val state = states.state(id.hex)
        val waiting = database.eventRowQueries.waiting(id.hex)
        return merge(connected.asFlow(), state.asFlow(), waiting.asFlow())
            .map {
                database.transactionWithResult {
                    connected.executeAsOneOrNull()?.let {
                        val row = state.executeAsOneOrNull()
                        SyncState(
                            succeeded = row?.succeeded?.let(Instant::ofEpochMilli),
                            halted =
                                row?.halted_at?.let {
                                    Upload.Halted(
                                        ObjectNumber(it.toInt()),
                                        Halt.valueOf(row.halt!!),
                                    )
                                },
                            failure = row?.failure?.let(Failure::valueOf),
                            waiting = waiting.executeAsOne(),
                        )
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(context)
    }

    companion object {
        /** The shortest interval Android runs periodic work at (TR-10). */
        val SHORTEST_INTERVAL: Duration = 15.minutes
    }
}

/**
 * An S3-compatible location (BE-2), apart from its secret key, which [Secrets] keeps. Its address
 * stays out of [toString], and so out of logs (TR-38).
 */
data class BackendSettings(
    val endpoint: String,
    val bucket: String,
    val prefix: String,
    val region: String,
    val pathStyle: Boolean,
    val accessKey: String,
) {
    override fun toString(): String = "BackendSettings(redacted)"
}

/** How a group's sync stands (SY-6). */
data class SyncState(
    /** The end of the last pass that succeeded, if any did (TR-12). */
    val succeeded: Instant?,
    /** Where and why the group stopped syncing, until a pass finds the backend agreeing (SY-15). */
    val halted: Upload.Halted?,
    /** Why the passes since the last success failed, if they did. */
    val failure: Failure?,
    /** How many events are not on the backend yet. */
    val waiting: Long,
)

/** Why a pass could not finish, in the terms the app reports it in (BE-7). */
enum class Failure {
    UNREACHABLE,
    DENIED,
    MISSING,
    FAILED,

    /** A secret the group needs is missing, or no longer opens, and has to be entered again. */
    LOCKED;

    companion object {
        fun of(e: StoreException): Failure =
            when (e) {
                is StoreException.Unreachable -> UNREACHABLE
                is StoreException.Denied -> DENIED
                is StoreException.Missing -> MISSING
                is StoreException.Failed -> FAILED
            }
    }
}
