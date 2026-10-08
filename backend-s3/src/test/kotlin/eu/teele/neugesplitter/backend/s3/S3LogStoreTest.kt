package eu.teele.neugesplitter.backend.s3

import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.Write
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class S3LogStoreTest {

    private val server = Server()

    @AfterTest fun close() = server.close()

    @Test
    fun `a write is a signed PUT with If-None-Match (TR-27, TR-28)`() = runBlocking {
        server.enqueue(200)
        assertEquals(
            Write.CREATED,
            server.store().createIfAbsent(ObjectNumber(1), "{}".toByteArray()),
        )
        val request = server.take()
        assertEquals("PUT", request.method)
        assertEquals("/bucket/groups/trip/log/0000001", request.target)
        assertEquals("{}", request.body?.utf8())
        assertEquals("*", request.headers["If-None-Match"])
        assertEquals("20261008T120000Z", request.headers["x-amz-date"])
        assertEquals(SigV4.sha256("{}".toByteArray()), request.headers["x-amz-content-sha256"])
        val authorization = request.headers["Authorization"].orEmpty()
        assertTrue(
            authorization.startsWith(
                "AWS4-HMAC-SHA256 Credential=AKID/20261008/eu-central-1/s3/aws4_request, " +
                    "SignedHeaders=host;if-none-match;x-amz-content-sha256;x-amz-date, Signature="
            ),
            authorization,
        )
    }

    @Test
    fun `a 412 is a lost write, not a failure (TR-30)`() = runBlocking {
        server.enqueue(412, error("PreconditionFailed"))
        assertEquals(Write.TAKEN, server.store().createIfAbsent(ObjectNumber(1), ByteArray(0)))
    }

    @Test
    fun `an object comes back as stored, and an absent one as null`() = runBlocking {
        server.enqueue(200, "{\"a\":1}")
        server.enqueue(404, error("NoSuchKey"))
        val store = server.store()
        assertContentEquals("{\"a\":1}".toByteArray(), store.get(ObjectNumber.ROOT))
        assertEquals("/bucket/groups/trip/log/0000000", server.take().target)
        assertNull(store.get(ObjectNumber(2)))
    }

    @Test
    fun `everything else is a failure of its own kind (TR-30)`(): Unit = runBlocking {
        val store = server.store()
        server.enqueue(404, error("NoSuchBucket"))
        assertFailsWith<StoreException.Missing> { store.get(ObjectNumber.ROOT) }
        server.enqueue(403, error("SignatureDoesNotMatch"))
        assertFailsWith<StoreException.Denied> { store.get(ObjectNumber.ROOT) }
        server.enqueue(404)
        assertFailsWith<StoreException.Failed> { store.get(ObjectNumber.ROOT) }
        server.enqueue(500, error("InternalError"))
        assertFailsWith<StoreException.Failed> {
            store.createIfAbsent(ObjectNumber(1), ByteArray(0))
        }
        server.close()
        assertFailsWith<StoreException.Unreachable> { store.get(ObjectNumber.ROOT) }
    }

    @Test
    fun `virtual-hosted addressing puts the bucket in the host name (sync-design 10)`() =
        runBlocking {
            server.enqueue(200, "x")
            server.store(pathStyle = false, prefix = "").get(ObjectNumber.ROOT)
            val request = server.take()
            assertEquals("bucket.localhost:${server.server.port}", request.headers["Host"])
            assertEquals("/log/0000000", request.target)
        }

    @Test
    fun `a listing starts one below, pages on, and ignores other names (sync-design 5)`() =
        runBlocking {
            server.enqueue(200, listing(true, "t/1", "0000004", "0000005"))
            server.enqueue(200, listing(false, null, "0000006", "notes.txt", "0000007a"))
            val store = server.store(pageSize = 2)
            assertEquals(listOf(ObjectNumber(5), ObjectNumber(6)), store.listFrom(ObjectNumber(5)))
            val first = server.take().url
            assertEquals("/bucket", first.encodedPath)
            assertEquals("2", first.queryParameter("list-type"))
            assertEquals("groups/trip/log/", first.queryParameter("prefix"))
            assertEquals("groups/trip/log/0000004", first.queryParameter("start-after"))
            assertEquals("2", first.queryParameter("max-keys"))
            assertEquals("t/1", server.take().url.queryParameter("continuation-token"))

            server.enqueue(200, listing(false, null))
            assertEquals(emptyList(), store.listFrom(ObjectNumber.ROOT))
            assertNull(server.take().url.queryParameter("start-after"))
        }

    private fun listing(truncated: Boolean, token: String?, vararg names: String) =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
          <Name>bucket</Name>
          <Prefix>groups/trip/log/</Prefix>
          <KeyCount>${names.size}</KeyCount>
          <IsTruncated>$truncated</IsTruncated>
          ${token?.let { "<NextContinuationToken>$it</NextContinuationToken>" } ?: ""}
          ${names.joinToString("") { "<Contents><Key>groups/trip/log/$it</Key><Size>1</Size></Contents>" }}
        </ListBucketResult>
        """
            .trimIndent()
}
