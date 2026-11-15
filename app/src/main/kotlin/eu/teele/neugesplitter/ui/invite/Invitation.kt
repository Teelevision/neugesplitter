package eu.teele.neugesplitter.ui.invite

import eu.teele.neugesplitter.ui.backend.Location
import java.net.URLDecoder
import java.net.URLEncoder

private const val PREFIX = "neugesplitter:join?"

/**
 * Everything joining a shared group takes, as its QR code carries it (BE-18): the location with its
 * secret key, and an encrypted group's passphrase.
 */
data class Invitation(val location: Location, val passphrase: String? = null) {

    /** One line, `neugesplitter:join?` and the fields as a query, empty ones left out. */
    fun encoded(): String {
        val settings = location.settings
        val fields =
            listOf(
                "endpoint" to settings.endpoint,
                "bucket" to settings.bucket,
                "prefix" to settings.prefix,
                "region" to settings.region,
                "pathStyle" to settings.pathStyle.toString(),
                "accessKey" to settings.accessKey,
                "secretKey" to location.secretKey.trim(),
                "passphrase" to passphrase.orEmpty(),
            )
        return PREFIX +
            fields
                .filter { it.second.isNotEmpty() }
                .joinToString("&") { (key, value) ->
                    "$key=${URLEncoder.encode(value, Charsets.UTF_8)}"
                }
    }

    override fun toString(): String = "Invitation(redacted)"

    companion object {
        /** The invitation [text] is, or null if it is none or lacks what joining needs. */
        fun parse(text: String): Invitation? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val fields =
                try {
                    trimmed
                        .substring(PREFIX.length)
                        .split('&')
                        .filter { '=' in it }
                        .associate {
                            val (key, value) = it.split('=', limit = 2)
                            key to URLDecoder.decode(value, Charsets.UTF_8)
                        }
                } catch (_: IllegalArgumentException) {
                    return null
                }
            val location =
                Location(
                    endpoint = fields["endpoint"].orEmpty(),
                    bucket = fields["bucket"].orEmpty(),
                    prefix = fields["prefix"].orEmpty(),
                    region = fields["region"].orEmpty(),
                    pathStyle = fields["pathStyle"] == "true",
                    accessKey = fields["accessKey"].orEmpty(),
                    secretKey = fields["secretKey"].orEmpty(),
                )
            if (!location.complete) return null
            return Invitation(location, fields["passphrase"]?.takeIf { it.isNotEmpty() })
        }
    }
}
