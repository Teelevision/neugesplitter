package eu.teele.neugesplitter

import android.app.Application
import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The composition root: everything is constructed here, once (architecture.md 2). */
class AppGraph(context: Context) {

    val groups = GroupRepository(AndroidSqliteDriver(Database.Schema, context, "neugesplitter.db"))

    /** Work that outlives any screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        graph.scope.launch { graph.groups.refresh(graph.today()) }
    }
}
