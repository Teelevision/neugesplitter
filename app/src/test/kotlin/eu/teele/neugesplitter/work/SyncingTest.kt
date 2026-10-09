package eu.teele.neugesplitter.work

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.crypto.AgeCodec
import eu.teele.neugesplitter.format.crypto.GroupKey
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.LogObject
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.storage.settings.Failure
import eu.teele.neugesplitter.storage.settings.Secrets
import eu.teele.neugesplitter.storage.settings.SecretsDatabase
import eu.teele.neugesplitter.storage.settings.SyncState
import eu.teele.neugesplitter.storage.settings.Vault
import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.Keyring
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.Retained
import eu.teele.neugesplitter.sync.Sealing
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncingTest {

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var ids = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val trip = GroupId.parse(id(9))
    private val settings = BackendSettings("https://s3.example", "splits", "", "auto", true, "AK")
    private val passphrase = "fluss kabel nebel raupe wolke ziege"

    private val context = RuntimeEnvironment.getApplication()
    private val groups = GroupRepository(AndroidSqliteDriver(Database.Schema, context, null))
    private val secrets =
        Secrets(AndroidSqliteDriver(SecretsDatabase.Schema, context, null), XorVault())
    private val bucket = BucketStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** age as far as these tests need it, without scrypt: a root object names its passphrase. */
    private class FastCodec : AgeCodec by KageCodec() {
        val keys = mutableMapOf<String, GroupKey>()
        var unlocks = 0

        override fun lock(key: GroupKey, passphrase: CharArray): ByteArray {
            keys[String(passphrase)] = key
            return "root of ${String(passphrase)}".encodeToByteArray()
        }

        override fun unlock(root: ByteArray, passphrase: CharArray): GroupKey {
            unlocks++
            if (root.decodeToString() != "root of ${String(passphrase)}") throw WrongPassphrase()
            return keys.getValue(String(passphrase))
        }
    }

    /** Seals by flipping bits, and opens only what it sealed. */
    private class XorVault : Vault {
        override fun seal(plain: ByteArray) =
            byteArrayOf(0x5e) + plain.map { (it.toInt() xor 0x55).toByte() }

        override fun open(sealed: ByteArray): ByteArray {
            if (sealed.firstOrNull() != 0x5e.toByte()) throw AEADBadTagException()
            return sealed.drop(1).map { (it.toInt() xor 0x55).toByte() }.toByteArray()
        }
    }

    private val codec = FastCodec()

    /** What is scheduled, by group. */
    private class FakePolls : Polls {
        val scheduled = ConcurrentHashMap<GroupId, Duration>()

        override fun schedule(id: GroupId, interval: Duration) {
            scheduled[id] = interval
        }

        override fun cancel(id: GroupId) {
            scheduled.remove(id)
        }

        override fun cancelAll() {
            scheduled.clear()
        }
    }

    private val polls = FakePolls()

    private fun syncing(keyring: Keyring = Keyring(codec), store: BucketStore = bucket) =
        Syncing(
            groups,
            secrets,
            keyring,
            codec,
            scope,
            { today },
            { at },
            { _, _ -> store },
            polls,
            quiet = 20.milliseconds,
            bound = 100.milliseconds,
            retry = 50.milliseconds,
        )

    private fun created() {
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna), at) {
                EventId.parse(id(0x100 + ids++))
            },
            today,
        )
    }

    private fun appended() {
        val n = ids++
        val renamed = GroupChanges(description = Change.To("Day $n"))
        groups.append(
            trip,
            listOf(Event.GroupUpdated(EventId.parse(id(0x100 + n)), anna.id, at, renamed)),
            today,
        )
    }

    /** Stops every runner, as the process ending would. */
    private fun restart() {
        scope.coroutineContext[Job]!!.children.forEach { it.cancel() }
    }

    private fun settled(until: (SyncState) -> Boolean): SyncState = runBlocking {
        withTimeout(5.seconds) { groups.backends.state(trip).first { it != null && until(it) }!! }
    }

    private fun BucketStore.at(n: Int): ByteArray? = objects["log/${ObjectNumber(n).name}"]

    @After
    fun stop() {
        scope.cancel()
    }

    @Test
    fun `a connected group goes up at once, and what is recorded later after a quiet spell (TR-14)`() {
        created()
        val syncing = syncing()

        assertTrue(runBlocking { syncing.connect(trip, settings, "secret") })
        settled { it.waiting == 0L && it.succeeded == at }
        assertNotNull(bucket.at(0))

        appended()
        settled { it.waiting == 0L }
        assertEquals(1, LogObject.read(bucket.at(1)!!).events.size)
        assertEquals(settings, groups.backends.settings(trip))
        assertEquals("secret", secrets.secretKey(trip))
    }

    @Test
    fun `an encrypted group publishes its root, and after a restart unlocks it with the kept passphrase (format-spec 5_1, TR-26)`() {
        created()
        val first = Keyring(codec)
        val syncing = syncing(first)
        val newKey = runBlocking { syncing.newKey(passphrase.toCharArray()) }

        assertTrue(runBlocking { syncing.connect(trip, settings, "secret", newKey) })
        settled { it.waiting == 0L }
        newKey.wipe()

        val root = "root of $passphrase".encodeToByteArray()
        assertContentEquals(root, bucket.at(0))
        assertContentEquals(passphrase.toCharArray(), secrets.passphrase(trip))
        assertEquals(0, codec.unlocks)
        val key = first[trip]!!
        val log =
            Sealing.Encrypted(codec, key).read(bucket.at(1)!!, Retained(ObjectNumber.ROOT, root))
        assertEquals(groups.events(trip), log.carried.events)

        restart()
        val restarted = Keyring(codec)
        syncing(restarted).start()
        appended()
        settled { it.waiting == 0L }
        assertEquals(1, codec.unlocks)
        assertNotNull(bucket.at(2))
    }

    @Test
    fun `a location holding another root object is refused, and nothing is kept (BE-9)`() {
        created()
        bucket.objects["log/0000000"] = "another group's root".encodeToByteArray()
        val syncing = syncing()
        val newKey = runBlocking { syncing.newKey(passphrase.toCharArray()) }

        assertFalse(runBlocking { syncing.connect(trip, settings, "secret", newKey) })

        assertNull(groups.backends.settings(trip))
        assertNull(secrets.secretKey(trip))
        assertNull(secrets.passphrase(trip))
        assertNull(groups.backends.root(trip))
    }

    @Test
    fun `an unreachable backend is reported, and a secret that no longer opens as locked (SY-6)`() {
        created()
        val down = BucketStore(reachable = false)
        assertTrue(runBlocking { syncing(store = down).connect(trip, settings, "secret") })
        assertEquals(Failure.UNREACHABLE, settled { it.failure != null }.failure)
        assertNull(settled { true }.succeeded)

        restart()
        secrets.forget(trip)
        syncing().start()
        appended()
        assertEquals(Failure.LOCKED, settled { it.failure == Failure.LOCKED }.failure)
    }

    @Test
    fun `a pass that could not reach the backend goes again by itself (SY-6)`() {
        created()
        val flaky = BucketStore(reachable = false)
        runBlocking { syncing(store = flaky).connect(trip, settings, "secret") }
        settled { it.failure == Failure.UNREACHABLE }

        flaky.reachable = true
        val state = settled { it.failure == null }
        assertEquals(at, state.succeeded)
        assertEquals(0L, state.waiting)
        assertNotNull(flaky.at(0))
    }

    @Test
    fun `an opened app fetches what others wrote meanwhile, once per process (SY-2, SY-9)`() {
        created()
        runBlocking { syncing().connect(trip, settings, "secret") }
        settled { it.waiting == 0L }
        restart()

        val renamed = GroupChanges(description = Change.To("Elsewhere"))
        val theirs = Event.GroupUpdated(EventId.parse(id(0x200)), anna.id, at, renamed)
        bucket.objects["log/0000001"] =
            LogObject(BatchId.parse(id(0x300)), null, listOf(theirs)).write()
        val lists = bucket.lists
        val syncing = syncing()
        syncing.start()
        syncing.opened()

        runBlocking { withTimeout(5.seconds) { while (theirs !in groups.events(trip)) delay(10) } }
        syncing.opened()
        Thread.sleep(200)
        assertEquals(lists + 1, bucket.lists)
    }

    @Test
    fun `a started process polls in the background and asks the backend nothing itself (TR-9, SY-13)`() {
        created()
        val syncing = syncing()
        runBlocking { syncing.connect(trip, settings, "secret") }
        settled { it.waiting == 0L }
        assertEquals(6.hours, polls.scheduled[trip])
        restart()
        polls.cancelAll()

        val lists = bucket.lists
        syncing().start()

        Thread.sleep(200)
        assertEquals(6.hours, polls.scheduled[trip])
        assertEquals(lists, bucket.lists)
    }

    @Test
    fun `a poll returns once its pass has ended, and a changed interval is scheduled (TR-9, SY-13)`() {
        created()
        val syncing = syncing()
        runBlocking { syncing.connect(trip, settings, "secret") }
        settled { it.waiting == 0L }

        val renamed = GroupChanges(description = Change.To("Elsewhere"))
        val theirs = Event.GroupUpdated(EventId.parse(id(0x200)), anna.id, at, renamed)
        bucket.objects["log/0000001"] =
            LogObject(BatchId.parse(id(0x300)), null, listOf(theirs)).write()
        runBlocking { syncing.poll(trip) }
        assertTrue(theirs in groups.events(trip))

        syncing.interval(trip, 15.minutes)
        assertEquals(15.minutes, polls.scheduled[trip])
        assertEquals(15.minutes, runBlocking { groups.backends.interval(trip).first() })
    }

    @Test
    fun `a poll for a group no longer shared cancels itself`() {
        polls.schedule(trip, 6.hours)

        runBlocking { syncing().poll(trip) }

        assertNull(polls.scheduled[trip])
        assertNull(runBlocking { groups.backends.state(trip).first() })
    }

    @Test
    fun `forgetting a group stops its sync, its polls and its secrets (GR-11)`() {
        created()
        val syncing = syncing()
        runBlocking { syncing.connect(trip, settings, "secret") }
        settled { it.waiting == 0L }

        syncing.forget(trip)
        appended()

        assertNull(secrets.secretKey(trip))
        assertNull(polls.scheduled[trip])
        Thread.sleep(200)
        assertNull(bucket.at(1))
    }
}
