package eu.teele.neugesplitter.backend.s3

import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/** A MockWebServer and the stores that talk to it, with the clock stopped. */
class Server : AutoCloseable {

    val server = MockWebServer().apply { start() }

    private val http =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            // So that virtual-hosted names reach the server too.
            .dns { listOf(InetAddress.getLoopbackAddress()) }
            .build()

    fun store(pathStyle: Boolean = true, prefix: String = "groups/trip", pageSize: Int = 1000) =
        S3LogStore(
            S3Location(
                server.url("/").toString(),
                "bucket",
                prefix,
                "eu-central-1",
                pathStyle,
                Credentials("AKID", "secret"),
            ),
            http,
            Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC),
            pageSize,
        )

    fun enqueue(code: Int, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    fun take(): RecordedRequest = server.takeRequest()

    override fun close() = server.close()
}

fun error(code: String) =
    "<?xml version=\"1.0\"?><Error><Code>$code</Code><Message>m</Message></Error>"

/**
 * A bucket over HTTP. [conditional] false makes it overwrite whatever `If-None-Match` says, like an
 * S3 that does not implement conditional writes; [writable] false refuses every `PUT`.
 */
class FakeS3(private val conditional: Boolean = true, private val writable: Boolean = true) :
    Dispatcher() {

    val objects = HashMap<String, ByteArray>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val key = request.url.encodedPath
        val answer = MockResponse.Builder()
        when (request.method) {
            "GET" ->
                objects[key]?.let { answer.code(200).body(okio.Buffer().write(it)) }
                    ?: answer.code(404).body(error("NoSuchKey"))
            "PUT" ->
                when {
                    !writable -> answer.code(403).body(error("AccessDenied"))
                    conditional && request.headers["If-None-Match"] == "*" && key in objects ->
                        answer.code(412).body(error("PreconditionFailed"))
                    else -> {
                        objects[key] = request.body?.toByteArray() ?: ByteArray(0)
                        answer.code(200)
                    }
                }
            else -> answer.code(405)
        }
        return answer.build()
    }
}
