package eu.teele.neugesplitter.storage.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.ObjectNumber.Companion.ROOT
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BackendsTest {

    private val driver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val groups = GroupRepository(driver)
    private val backends = groups.backends

    private val events = Log().create().add(BEN, "Ben").events()
    private val settings =
        BackendSettings("https://s3.example", "splits", "wg", "eu-central-1", false, "AKIDEXAMPLE")
    private val root = "an armoured identity".encodeToByteArray()

    private fun state() = runBlocking { backends.state(GROUP, Dispatchers.Unconfined).first() }

    private fun interval() = runBlocking {
        backends.interval(GROUP, Dispatchers.Unconfined).first()
    }

    @Test
    fun `a group polls every six hours at most until told otherwise, also once moved (SY-13)`() {
        assertNull(interval())
        backends.connect(GROUP, settings)
        assertEquals(6.hours, interval())

        backends.setInterval(GROUP, 15.minutes)
        backends.connect(GROUP, settings.copy(bucket = "elsewhere"))

        assertEquals(15.minutes, interval())
        assertFailsWith<IllegalArgumentException> { backends.setInterval(GROUP, 14.minutes) }
    }

    @Test
    fun `a group's backend is kept, and its address stays out of logs (BE-2, BE-4, TR-38)`() {
        assertNull(backends.settings(GROUP))

        backends.connect(GROUP, settings)

        assertEquals(settings, backends.settings(GROUP))
        assertEquals(listOf(GROUP), backends.connected())
        assertFalse("s3.example" in settings.toString())
        assertFalse("AKIDEXAMPLE" in settings.toString())
    }

    @Test
    fun `sync state shows the last success, a halt, a failure and what waits (SY-6, TR-12, SY-15)`() {
        groups.append(GROUP, events, TODAY)
        assertNull(state())

        backends.connect(GROUP, settings)
        assertEquals(SyncState(null, null, null, waiting = 2), state())

        backends.failed(GROUP, Failure.of(StoreException.Unreachable(IOException("offline"))))
        assertEquals(SyncState(null, null, Failure.UNREACHABLE, 2), state())

        val at = Instant.parse("2026-08-01T10:00:00Z")
        backends.succeeded(GROUP, at)
        assertEquals(SyncState(at, null, null, 2), state())

        val halted = Upload.Halted(ObjectNumber(3), Halt.TRUNCATED)
        backends.halted(GROUP, halted)
        backends.failed(GROUP, Failure.of(StoreException.Denied("forbidden")))
        assertEquals(SyncState(at, halted, Failure.DENIED, 2), state())

        val later = at.plusSeconds(60)
        backends.succeeded(GROUP, later)
        assertEquals(SyncState(later, null, null, 2), state())
    }

    @Test
    fun `an encrypted group's root object is retained as the head of its log (format-spec 5_1)`():
        Unit = runBlocking {
        val log = groups.local(GROUP, { TODAY }, Dispatchers.Unconfined)
        assertNull(backends.root(GROUP))

        log.rooted(root)

        assertContentEquals(root, backends.root(GROUP))
        assertContentEquals(root, backends.first(GROUP))
        val head = log.head()!!
        assertEquals(ROOT, head.number)
        assertContentEquals(root, head.bytes)
    }

    @Test
    fun `an unencrypted group's first object is not a root to unlock (format-spec 2_1)`() {
        assertNull(backends.first(GROUP))
        Database(driver).logObjectQueries.retain(GROUP.hex, 0, "ba7c".repeat(8), byteArrayOf(1))
        assertNull(backends.root(GROUP))
        assertContentEquals(byteArrayOf(1), backends.first(GROUP))
    }

    @Test
    fun `an installation from version 2 keeps its retained objects and can be connected`(): Unit =
        runBlocking {
            val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(old)
            for (table in listOf("log_object", "backend_settings", "sync_state", "unseen")) {
                old.execute(null, "DROP TABLE $table", 0)
            }
            old.execute(
                null,
                """
                CREATE TABLE log_object (
                    group_id TEXT NOT NULL,
                    number INTEGER NOT NULL,
                    batch TEXT NOT NULL,
                    bytes BLOB NOT NULL,
                    PRIMARY KEY (group_id, number)
                )
                """
                    .trimIndent(),
                0,
            )
            Database(old).logObjectQueries.retain(GROUP.hex, 0, "ba7c".repeat(8), byteArrayOf(7))

            Database.Schema.migrate(old, 2, Database.Schema.version)

            val migrated = GroupRepository(old)
            val head = migrated.local(GROUP, { TODAY }, Dispatchers.Unconfined).head()!!
            assertContentEquals(byteArrayOf(7), head.bytes)
            val other = GroupId.parse("%032x".format(0x7e57))
            migrated.local(other, { TODAY }, Dispatchers.Unconfined).rooted(root)
            assertContentEquals(root, migrated.backends.root(other))
            migrated.backends.connect(GROUP, settings)
            migrated.backends.succeeded(GROUP, Instant.EPOCH)
            assertEquals(settings, migrated.backends.settings(GROUP))
            assertEquals(6.hours, migrated.backends.interval(GROUP, Dispatchers.Unconfined).first())
        }
}
