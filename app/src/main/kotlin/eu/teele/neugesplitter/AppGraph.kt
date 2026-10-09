package eu.teele.neugesplitter

import android.app.Application
import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.backend.s3.Credentials
import eu.teele.neugesplitter.backend.s3.S3Location
import eu.teele.neugesplitter.backend.s3.S3LogStore
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.storage.settings.KeystoreVault
import eu.teele.neugesplitter.storage.settings.Secrets
import eu.teele.neugesplitter.storage.settings.SecretsDatabase
import eu.teele.neugesplitter.sync.Keyring
import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.work.Syncing
import eu.teele.neugesplitter.work.WorkPolls
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The composition root: everything is constructed here, once (architecture.md 2). */
class AppGraph(context: Context) {

    val groups = GroupRepository(AndroidSqliteDriver(Database.Schema, context, "neugesplitter.db"))

    val preferences = Preferences(context)

    val languages: Languages = SystemLanguages(context)

    /** Work that outlives any screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val codec = KageCodec()

    val keyring = Keyring(codec)

    /** The backend at a location, given its secret key. */
    val stores: (BackendSettings, String) -> LogStore = { at, secretKey ->
        S3LogStore(
            S3Location(
                at.endpoint,
                at.bucket,
                at.prefix,
                at.region,
                at.pathStyle,
                Credentials(at.accessKey, secretKey),
            )
        )
    }

    val syncing =
        Syncing(
            groups = groups,
            secrets =
                Secrets(
                    AndroidSqliteDriver(SecretsDatabase.Schema, context, "secrets.db"),
                    KeystoreVault(),
                ),
            keyring = keyring,
            codec = codec,
            scope = scope,
            today = ::today,
            now = ::now,
            stores = stores,
            polls = WorkPolls(context),
        )

    private val random = SecureRandom()

    /** A fresh random identifier, as 32 hex characters (TR-24). */
    fun identifier(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** The installation's local date, which decides which occurrences are due (RE-14). */
    fun today(): EntryDate = EntryDate.of(LocalDate.now())

    fun now(): Instant = Instant.now()
}

class Neugesplitter : Application() {

    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.syncing.start()
    }
}
