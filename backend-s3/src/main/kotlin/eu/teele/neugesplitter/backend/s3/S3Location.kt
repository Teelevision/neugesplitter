package eu.teele.neugesplitter.backend.s3

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Where a group lives on an S3-compatible backend and how to reach it (BE-2). [prefix] may be
 * empty; [pathStyle] puts the bucket in the path rather than in the host name, which most servers
 * other than AWS need (sync-design 10).
 */
class S3Location(
    endpoint: String,
    val bucket: String,
    val prefix: String,
    val region: String,
    val pathStyle: Boolean,
    val credentials: Credentials,
) {
    /** @throws IllegalArgumentException for anything but an `http` or `https` URL. */
    internal val endpoint: HttpUrl = endpoint.toHttpUrl()

    /** What every key of the group starts with. */
    internal val keyPrefix: String = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    init {
        require(bucket.isNotEmpty()) { "no bucket" }
    }
}

/** An access key and its secret. The secret stays out of [toString], and so out of logs. */
class Credentials(val accessKey: String, val secretKey: String) {
    override fun toString(): String = "Credentials($accessKey)"
}
