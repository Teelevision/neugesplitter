package eu.teele.neugesplitter.storage.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.value.GroupId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretsTest {

    private val driver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecretsDatabase.Schema.create(it) }
    private val vault = MemoryVault()
    private val secrets = Secrets(driver, vault)
    private val other = GroupId.parse("%032x".format(0x7e57))

    private val secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val passphrase = "ähnlich fluss kabel nebel raupe wolke"
    private val groupKey =
        "AGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ"

    private fun stored(): List<ByteArray> =
        SecretsDatabase(driver).secretQueries.let { queries ->
            listOf("SECRET_KEY", "PASSPHRASE", "GROUP_KEY").mapNotNull {
                queries.sealed(GROUP.hex, it).executeAsOneOrNull()
            }
        }

    @Test
    fun `secrets are kept sealed and come back as they were (TR-25, TR-36)`() {
        secrets.keepSecretKey(GROUP, secretKey)
        secrets.keepPassphrase(GROUP, passphrase.toCharArray())
        secrets.keepGroupKey(GROUP, groupKey)

        assertEquals(secretKey, secrets.secretKey(GROUP))
        assertContentEquals(passphrase.toCharArray(), secrets.passphrase(GROUP))
        assertEquals(groupKey, secrets.groupKey(GROUP))
        val clear = listOf(secretKey, passphrase, groupKey).map { it.encodeToByteArray() }
        for (sealed in stored()) {
            for (text in clear) {
                val found = sealed.asList().windowed(text.size).any { it == text.asList() }
                assertEquals(false, found)
            }
        }
        assertEquals(3, stored().size)
    }

    @Test
    fun `a secret that no longer opens reads as absent`() {
        secrets.keepSecretKey(GROUP, secretKey)
        secrets.keepPassphrase(GROUP, passphrase.toCharArray())

        val elsewhere = Secrets(driver, MemoryVault())

        assertNull(elsewhere.secretKey(GROUP))
        assertNull(elsewhere.passphrase(GROUP))
    }

    @Test
    fun `forgetting a group's secrets leaves the others`() {
        secrets.keepSecretKey(GROUP, secretKey)
        secrets.keepPassphrase(GROUP, passphrase.toCharArray())
        secrets.keepGroupKey(GROUP, groupKey)
        secrets.keepSecretKey(other, "another")

        secrets.forget(GROUP)

        assertNull(secrets.secretKey(GROUP))
        assertNull(secrets.passphrase(GROUP))
        assertNull(secrets.groupKey(GROUP))
        assertEquals("another", secrets.secretKey(other))

        secrets.forgetAll()
        assertNull(secrets.secretKey(other))
    }
}
