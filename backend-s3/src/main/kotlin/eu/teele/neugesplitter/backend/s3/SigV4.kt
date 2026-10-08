package eu.teele.neugesplitter.backend.s3

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 (TR-28). S3 encodes a path once and never normalises it, so [encode] is
 * all it takes to make a path canonical.
 */
internal object SigV4 {

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** The `x-amz-date` for [time]. */
    fun stamp(time: Instant): String = STAMP.format(time)

    /** The `Authorization` header for [request]. */
    fun authorization(
        request: Signed,
        credentials: Credentials,
        region: String,
        service: String,
    ): String {
        val scope = "${request.stamp.take(8)}/$region/$service/aws4_request"
        val signingKey = scope.split('/').fold("AWS4${credentials.secretKey}".toByteArray(), ::hmac)
        val signature = hex(hmac(signingKey, stringToSign(request, scope)))
        return "AWS4-HMAC-SHA256 Credential=${credentials.accessKey}/$scope, " +
            "SignedHeaders=${request.signedHeaders}, Signature=$signature"
    }

    fun canonicalRequest(request: Signed): String =
        listOf(
                request.method,
                request.path,
                request.query,
                request.canonicalHeaders,
                request.signedHeaders,
                request.payloadHash,
            )
            .joinToString("\n")

    fun stringToSign(request: Signed, scope: String): String =
        listOf(
                "AWS4-HMAC-SHA256",
                request.stamp,
                scope,
                sha256(canonicalRequest(request).toByteArray()),
            )
            .joinToString("\n")

    /** Percent-encodes all but the unreserved characters, and `/` too unless in a [path]. */
    fun encode(text: String, path: Boolean = false): String = buildString {
        for (byte in text.toByteArray()) {
            val c = (byte.toInt() and 0xff).toChar()
            if (
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.~" || path && c == '/'
            ) {
                append(c)
            } else {
                append('%').append("%02X".format(byte))
            }
        }
    }

    /** [parameters] encoded and sorted: both the canonical query and the one to send. */
    fun query(parameters: List<Pair<String, String>>): String =
        parameters
            .map { (name, value) -> encode(name) to encode(value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data.toByteArray())
        }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** A request as SigV4 sees it: [path] and [query] canonical, [headers] the ones signed. */
internal class Signed(
    val method: String,
    val path: String,
    val query: String,
    headers: Map<String, String>,
    val payloadHash: String,
) {
    private val sorted =
        headers.entries
            .map { (name, value) -> name.lowercase() to value.trim().replace(SPACES, " ") }
            .sortedBy { it.first }

    val stamp: String = sorted.single { it.first == "x-amz-date" }.second

    val canonicalHeaders: String = sorted.joinToString("") { (name, value) -> "$name:$value\n" }

    val signedHeaders: String = sorted.joinToString(";") { it.first }

    private companion object {
        val SPACES = Regex(" +")
    }
}
