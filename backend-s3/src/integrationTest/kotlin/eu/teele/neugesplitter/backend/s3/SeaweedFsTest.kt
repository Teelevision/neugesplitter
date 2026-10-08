package eu.teele.neugesplitter.backend.s3

import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.Write
import eu.teele.neugesplitter.sync.probe.Verdict
import eu.teele.neugesplitter.sync.probe.probe
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.BeforeClass
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

/** The store and the backend check against a real S3 implementation in a container (TR-48). */
class SeaweedFsTest {

    private fun store(
        key: String = "writer",
        secret: String = "writer-secret",
        bucket: String = BUCKET,
        pageSize: Int = 1000,
    ) =
        S3LogStore(
            S3Location(
                "http://${seaweed.host}:${seaweed.getMappedPort(S3_PORT)}",
                bucket,
                "it/${UUID.randomUUID()}",
                "us-east-1",
                pathStyle = true,
                Credentials(key, secret),
            ),
            OkHttpClient(),
            Clock.systemUTC(),
            pageSize,
        )

    @Test
    fun `a conditional write lands once and loses after (TR-48)`() = runBlocking {
        val store = store()
        assertEquals(Write.CREATED, store.createIfAbsent(ObjectNumber(1), "first".toByteArray()))
        assertEquals(Write.TAKEN, store.createIfAbsent(ObjectNumber(1), "second".toByteArray()))
        assertContentEquals("first".toByteArray(), store.get(ObjectNumber(1)))
    }

    @Test
    fun `of writes racing for one number exactly one lands (TR-48, sync-design 4)`() = runBlocking {
        val store = store()
        val writes =
            (1..8)
                .map { writer ->
                    async(Dispatchers.IO) {
                        writer to store.createIfAbsent(ObjectNumber(7), "$writer".toByteArray())
                    }
                }
                .awaitAll()
        val winners = writes.filter { it.second == Write.CREATED }
        assertEquals(1, winners.size, "$writes")
        assertContentEquals("${winners.single().first}".toByteArray(), store.get(ObjectNumber(7)))
    }

    @Test
    fun `an absent object is null, and the log lists from a number across pages`() = runBlocking {
        val store = store(pageSize = 2)
        assertEquals(null, store.get(ObjectNumber.ROOT))
        for (n in 0..4) store.createIfAbsent(ObjectNumber(n), "$n".toByteArray())
        store.createProbe()
        assertEquals((0..4).map(::ObjectNumber), store.listFrom(ObjectNumber.ROOT))
        assertEquals((3..4).map(::ObjectNumber), store.listFrom(ObjectNumber(3)))
        assertEquals(emptyList(), store.listFrom(ObjectNumber(5)))
    }

    @Test
    fun `SeaweedFS passes the backend check (BE-7)`() = runBlocking {
        val store = store()
        assertEquals(Verdict.Accepted, probe(store))
        assertEquals(Verdict.Accepted, probe(store))
    }

    @Test
    fun `a key that may only read is read-only (BE-8)`() = runBlocking {
        assertEquals(Verdict.ReadOnly, probe(store(key = "reader", secret = "reader-secret")))
    }

    @Test
    fun `a wrong secret and a missing bucket are unusable (BE-7)`(): Unit = runBlocking {
        val denied = assertIs<Verdict.Unusable>(probe(store(secret = "wrong")))
        assertIs<StoreException.Denied>(denied.failure)
        val missing = assertIs<Verdict.Unusable>(probe(store(bucket = "nowhere")))
        assertIs<StoreException.Missing>(missing.failure)
    }

    companion object {
        private const val S3_PORT = 8333
        private const val BUCKET = "groups"

        private val IDENTITIES =
            """
            {"identities": [
              {"name": "writer", "actions": ["Admin", "Read", "List", "Write"],
               "credentials": [{"accessKey": "writer", "secretKey": "writer-secret"}]},
              {"name": "reader", "actions": ["Read", "List"],
               "credentials": [{"accessKey": "reader", "secretKey": "reader-secret"}]}
            ]}
            """
                .trimIndent()

        private class SeaweedFs :
            GenericContainer<SeaweedFs>(DockerImageName.parse("chrislusf/seaweedfs:4.48"))

        private val seaweed =
            SeaweedFs()
                .withCopyToContainer(Transferable.of(IDENTITIES), "/etc/seaweedfs/s3.json")
                .withCommand(
                    "mini",
                    "-dir=/data",
                    "-s3.config=/etc/seaweedfs/s3.json",
                    "-s3.autoCreateBucket=false",
                )
                .withExposedPorts(S3_PORT)
                .waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT))

        @BeforeClass
        @JvmStatic
        fun start() {
            seaweed.start()
            val created =
                seaweed.execInContainer(
                    "sh",
                    "-c",
                    "echo 's3.bucket.create -name $BUCKET' | weed shell -master=localhost:9333",
                )
            check(created.exitCode == 0) { created.stderr }
        }

        @AfterClass @JvmStatic fun stop() = seaweed.stop()
    }
}
