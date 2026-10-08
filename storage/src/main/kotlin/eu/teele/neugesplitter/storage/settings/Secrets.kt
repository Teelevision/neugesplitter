package eu.teele.neugesplitter.storage.settings

import app.cash.sqldelight.db.SqlDriver
import eu.teele.neugesplitter.domain.value.GroupId
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException

/**
 * Each shared group's backend secret key and, for an encrypted group, its passphrase, sealed by
 * [vault] and never written in the clear (TR-25, TR-36). One that no longer opens, as after the
 * keystore lost its key, reads as absent and has to be entered again.
 *
 * [driver] must have [SecretsDatabase.Schema] created, in a file no backup takes (TR-37).
 */
class Secrets(driver: SqlDriver, private val vault: Vault) {

    private val queries = SecretsDatabase(driver).secretQueries

    fun keepSecretKey(id: GroupId, secretKey: String) {
        keep(id, Kind.SECRET_KEY, secretKey.toCharArray())
    }

    fun secretKey(id: GroupId): String? = opened(id, Kind.SECRET_KEY)?.let(::String)

    /** Keeps [passphrase] sealed; wiping it stays the caller's. */
    fun keepPassphrase(id: GroupId, passphrase: CharArray) {
        keep(id, Kind.PASSPHRASE, passphrase)
    }

    /** Group [id]'s passphrase, for the caller to wipe once used. */
    fun passphrase(id: GroupId): CharArray? = opened(id, Kind.PASSPHRASE)

    fun forget(id: GroupId) {
        queries.forget(id.hex)
    }

    fun forgetAll() {
        queries.forgetAll()
    }

    private fun keep(id: GroupId, kind: Kind, chars: CharArray) {
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val plain = ByteArray(encoded.remaining()).also(encoded::get)
        encoded.array().fill(0)
        try {
            queries.keep(id.hex, kind.name, vault.seal(plain))
        } finally {
            plain.fill(0)
        }
    }

    private fun opened(id: GroupId, kind: Kind): CharArray? {
        val sealed = queries.sealed(id.hex, kind.name).executeAsOneOrNull() ?: return null
        val plain =
            try {
                vault.open(sealed)
            } catch (_: GeneralSecurityException) {
                return null
            }
        val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
        plain.fill(0)
        val chars = CharArray(decoded.remaining()).also(decoded::get)
        decoded.array().fill('\u0000')
        return chars
    }

    private enum class Kind {
        SECRET_KEY,
        PASSPHRASE,
    }
}

/** Seals and opens secrets with a key that never leaves where it is held (TR-36). */
interface Vault {

    fun seal(plain: ByteArray): ByteArray

    /** @throws GeneralSecurityException if [sealed] was not sealed with this vault's key. */
    fun open(sealed: ByteArray): ByteArray
}
