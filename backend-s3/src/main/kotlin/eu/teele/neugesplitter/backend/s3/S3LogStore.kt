package eu.teele.neugesplitter.backend.s3

import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.Write
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xml.sax.SAXException

/**
 * The [LogStore] on an S3-compatible backend: `GET`, conditional `PUT` and `ListObjectsV2`, signed
 * with SigV4, and nothing an SDK would add (TR-27).
 */
class S3LogStore
internal constructor(
    private val location: S3Location,
    private val http: OkHttpClient,
    private val clock: Clock,
    private val pageSize: Int,
) : LogStore {

    constructor(location: S3Location) : this(location, CLIENT, Clock.systemUTC(), 1000)

    override suspend fun createIfAbsent(number: ObjectNumber, bytes: ByteArray): Write =
        create(log(number), bytes)

    override suspend fun get(number: ObjectNumber): ByteArray? {
        val answer = send("GET", log(number))
        return when {
            answer.status == 200 -> answer.body
            answer.status == 404 && answer.error().code == "NoSuchKey" -> null
            else -> fail(answer)
        }
    }

    override suspend fun listFrom(first: ObjectNumber): List<ObjectNumber> {
        val prefix = "${location.keyPrefix}log/"
        val numbers = ArrayList<ObjectNumber>()
        var token: String? = null
        do {
            val query = buildList {
                add("list-type" to "2")
                add("prefix" to prefix)
                add("max-keys" to "$pageSize")
                if (first != ObjectNumber.ROOT) {
                    add("start-after" to prefix + ObjectNumber(first.value - 1).name)
                }
                token?.let { add("continuation-token" to it) }
            }
            val answer = send("GET", null, query)
            if (answer.status != 200) fail(answer)
            token = null
            var truncated = false
            answer.xml { path, text ->
                when (path.lastOrNull()) {
                    "Key" ->
                        if (path.size == 3 && path[1] == "Contents" && text.startsWith(prefix)) {
                            ObjectNumber.parse(text.removePrefix(prefix))
                                ?.takeIf { it >= first }
                                ?.let(numbers::add)
                        }
                    "IsTruncated" -> truncated = text == "true"
                    "NextContinuationToken" -> token = text
                }
            }
            if (truncated && token == null) {
                throw StoreException.Failed("the backend cut a listing short with no way on")
            }
        } while (truncated)
        return numbers.sorted()
    }

    override suspend fun createProbe(): Write = create("${location.keyPrefix}probe", PROBE)

    private suspend fun create(key: String, bytes: ByteArray): Write {
        val answer = send("PUT", key, body = bytes, conditional = true)
        return when (answer.status) {
            200 -> Write.CREATED
            412 -> Write.TAKEN
            else -> fail(answer)
        }
    }

    private fun log(number: ObjectNumber): String = "${location.keyPrefix}log/${number.name}"

    /** One signed request for [key], or for the bucket when there is none. */
    private suspend fun send(
        method: String,
        key: String?,
        parameters: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        conditional: Boolean = false,
    ): Answer {
        val endpoint = location.endpoint
        val bucket = SigV4.encode(location.bucket)
        val encodedKey = key?.let { SigV4.encode(it, path = true) }
        val host = if (location.pathStyle) endpoint.host else "${location.bucket}.${endpoint.host}"
        val path =
            if (location.pathStyle) "/$bucket" + (encodedKey?.let { "/$it" } ?: "")
            else "/" + (encodedKey ?: "")
        val query = SigV4.query(parameters)
        val url =
            endpoint
                .newBuilder()
                .host(host)
                .encodedPath(path)
                .encodedQuery(query.ifEmpty { null })
                .build()

        val headers = buildMap {
            put("host", hostHeader(url.host, url.port, url.scheme))
            put("x-amz-date", SigV4.stamp(clock.instant()))
            put("x-amz-content-sha256", SigV4.sha256(body ?: ByteArray(0)))
            if (conditional) put("if-none-match", "*")
        }
        val signed = Signed(method, path, query, headers, headers.getValue("x-amz-content-sha256"))
        val authorization = SigV4.authorization(signed, location.credentials, location.region, "s3")

        val request =
            Request.Builder()
                .url(url)
                .method(method, body?.toRequestBody())
                .apply {
                    headers.forEach { (name, value) -> if (name != "host") header(name, value) }
                }
                .header("Authorization", authorization)
                .build()

        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val source = response.body.source()
                    if (source.request(MAX_BODY + 1L)) {
                        throw StoreException.Failed("the backend sent more than $MAX_BODY bytes")
                    }
                    Answer(response.code, source.buffer.readByteArray())
                }
            } catch (e: IOException) {
                throw StoreException.Unreachable(e)
            }
        }
    }

    private class Answer(val status: Int, val body: ByteArray) {

        class Error(val code: String?, val message: String?)

        /** S3's `<Error>` document, when there is one. */
        fun error(): Error {
            var code: String? = null
            var message: String? = null
            try {
                xml { path, text ->
                    if (path.size == 2 && path[0] == "Error") {
                        when (path[1]) {
                            "Code" -> code = text
                            "Message" -> message = text
                        }
                    }
                }
            } catch (_: StoreException) {}
            return Error(code, message)
        }

        fun xml(element: (path: List<String>, text: String) -> Unit) {
            try {
                readXml(body, element)
            } catch (e: SAXException) {
                throw StoreException.Failed(
                    "the backend answered $status with no XML: ${e.message}"
                )
            }
        }
    }

    /** Maps what is not a success onto the port's failures (TR-30). */
    private fun fail(answer: Answer): Nothing {
        val error = answer.error()
        val detail =
            listOfNotNull("HTTP ${answer.status}", error.code, error.message).joinToString(": ")
        throw when {
            answer.status == 401 || answer.status == 403 -> StoreException.Denied(detail)
            answer.status == 404 && error.code == "NoSuchBucket" -> StoreException.Missing(detail)
            else -> StoreException.Failed(detail)
        }
    }

    private companion object {
        /** Twice format-spec 2.2's limit, so that an age file around a limit-sized object fits. */
        const val MAX_BODY = 16 * 1024 * 1024

        val PROBE =
            "Left by Neugesplitter's backend check (BE-7). Nothing reads it.\n".toByteArray()

        /**
         * Retries and redirects off: a resent conditional write could lose to itself, and a
         * redirect would carry a signature made for another host.
         */
        val CLIENT: OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()

        fun hostHeader(host: String, port: Int, scheme: String): String {
            val name = if (':' in host) "[$host]" else host
            return if (port == HttpUrl.defaultPort(scheme)) name else "$name:$port"
        }
    }
}
