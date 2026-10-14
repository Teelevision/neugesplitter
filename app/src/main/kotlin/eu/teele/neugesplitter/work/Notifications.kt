package eu.teele.neugesplitter.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import eu.teele.neugesplitter.MainActivity
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.history.history
import eu.teele.neugesplitter.domain.history.steps
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.history.told
import kotlinx.coroutines.flow.first

/**
 * Tells about changes others made to a shared group once a pass in the background has received them
 * (SY-10): one notification per group, listing what is not seen yet, newest first, which opening
 * the app clears. Each change is told about once; while the app is on screen, Activity shows them
 * instead (HI-5).
 */
class Notifications(
    private val context: Context,
    private val groups: GroupRepository,
    /** Whether the app is on screen. */
    private val shown: () -> Boolean,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Each group's newest log position told about or received on screen, by hex id. */
    private val told = context.getSharedPreferences("notified", Context.MODE_PRIVATE)

    /** Creates the channel, or renames it after a change of language. */
    fun channel() {
        val channel =
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        channel.description = context.getString(R.string.notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    /** Tells about what a pass over group [id] received that is newer than anything told yet. */
    suspend fun received(id: GroupId) {
        val unseen = groups.unseen(id).first()
        val newest = unseen.maxOrNull() ?: return
        if (newest <= told.getInt(id.hex, -1)) return
        told.edit().putInt(id.hex, newest).apply()
        if (shown() || !manager.areNotificationsEnabled()) return
        val group = groups.load(id) ?: return
        val events = groups.events(id)
        val news =
            steps(history(events)).asReversed().filter { step -> step.events.any { it in unseen } }
        if (news.isEmpty()) return
        val resources = context.resources
        val locale = resources.configuration.locales[0]
        val names = group.members.mapValues { it.value.name }
        val headlines =
            news.map { resources.told(it.happening, names, group.scale, locale).headline }
        val lines = Notification.InboxStyle()
        headlines.take(LINES).forEach(lines::addLine)
        val more = headlines.size - LINES
        if (more > 0) {
            lines.setSummaryText(
                resources.getQuantityString(R.plurals.notification_more, more, more)
            )
        }
        val open =
            Intent(context, MainActivity::class.java)
                .setIdentifier(id.hex)
                .putExtra(MainActivity.GROUP, id.hex)
        val notification =
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_activity)
                .setContentTitle(listOfNotNull(group.emoji, group.name).joinToString(" "))
                .setContentText(headlines.first())
                .setStyle(lines)
                .setNumber(headlines.size)
                .setWhen(news.first().happening.at.toEpochMilli())
                .setShowWhen(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .setAutoCancel(true)
                .build()
        manager.notify(id.hex, NOTIFICATION, notification)
    }

    /** Takes back every notification, as the app coming on screen does. */
    fun clear() {
        manager.cancelAll()
    }

    /** Forgets group [id], as deleting it does. */
    fun forget(id: GroupId) {
        manager.cancel(id.hex, NOTIFICATION)
        told.edit().remove(id.hex).apply()
    }

    /** Forgets every group (PR-9). */
    fun forgetAll() {
        manager.cancelAll()
        told.edit().clear().apply()
    }

    private companion object {
        const val CHANNEL = "changes"
        const val NOTIFICATION = 1
        /** How many changes a notification lists before counting the rest. */
        const val LINES = 5
    }
}
