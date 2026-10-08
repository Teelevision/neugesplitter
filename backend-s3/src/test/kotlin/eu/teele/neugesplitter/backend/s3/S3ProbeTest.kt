package eu.teele.neugesplitter.backend.s3

import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.probe.Verdict
import eu.teele.neugesplitter.sync.probe.probe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** The backend check over HTTP, against fakes of the backends it has to tell apart. */
class S3ProbeTest {

    private val server = Server()

    @AfterTest fun close() = server.close()

    @Test
    fun `an S3 that ignores If-None-Match is refused (TR-49, BE-7)`() = runBlocking {
        val bucket = FakeS3(conditional = false)
        server.server.dispatcher = bucket
        assertEquals(Verdict.Refused, probe(server.store()))
        assertEquals(setOf("/bucket/groups/trip/probe"), bucket.objects.keys)
    }

    @Test
    fun `an S3 that honours it is accepted (BE-7)`() = runBlocking {
        server.server.dispatcher = FakeS3()
        assertEquals(Verdict.Accepted, probe(server.store()))
        assertEquals(Verdict.Accepted, probe(server.store()))
    }

    @Test
    fun `an S3 that refuses writes is read-only (BE-8)`() = runBlocking {
        server.server.dispatcher = FakeS3(writable = false)
        assertEquals(Verdict.ReadOnly, probe(server.store()))
    }

    @Test
    fun `a bucket that does not exist is unusable (BE-7)`(): Unit = runBlocking {
        server.enqueue(404, error("NoSuchBucket"))
        val verdict = assertIs<Verdict.Unusable>(probe(server.store()))
        assertIs<StoreException.Missing>(verdict.failure)
    }
}
