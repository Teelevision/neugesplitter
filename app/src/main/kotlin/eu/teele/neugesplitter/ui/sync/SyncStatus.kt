package eu.teele.neugesplitter.ui.sync

import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.storage.settings.SyncState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Whether [this] needs looking at: the sync halted or the last passes failed. */
val SyncState.troubled: Boolean
    get() = halted != null || failure != null

/**
 * How [sync] stands as a cloud (SY-6, SY-15), labelled for screen readers; [tint] defaults to the
 * error colour when it is [troubled].
 */
@Composable
fun SyncIcon(
    sync: SyncState,
    modifier: Modifier = Modifier,
    tint: Color = if (sync.troubled) MaterialTheme.colorScheme.error else LocalContentColor.current,
) {
    val (icon, label) =
        when {
            sync.halted != null -> R.drawable.ic_cloud_off to R.string.sync_halted
            sync.failure != null -> R.drawable.ic_cloud_off to R.string.sync_problem
            sync.waiting > 0 -> R.drawable.ic_cloud_upload to R.string.sync_pending
            else -> R.drawable.ic_cloud_done to R.string.sync_in_sync
        }
    Icon(painterResource(icon), stringResource(label), modifier, tint = tint)
}

/**
 * A group's [title] for the top bars of its destinations, with how its [sync] stands at the right;
 * a group kept only on this device, with null [sync], shows none (ui-design 4).
 */
@Composable
fun GroupTitle(title: String, sync: SyncState?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        sync?.let { SyncIcon(it, Modifier.padding(horizontal = 12.dp)) }
    }
}

/**
 * How long before [now] [at] was, as [locale] says it: minutes, hours, yesterday, days, then the
 * date.
 */
fun ago(at: Instant, now: Instant, locale: Locale): String {
    val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
    val zone = ZoneId.systemDefault()
    val elapsed = Duration.between(at, now)
    val days =
        ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        elapsed.toMinutes() < 1 -> formatter.format(Direction.PLAIN, AbsoluteUnit.NOW)
        elapsed.toHours() < 1 ->
            formatter.format(elapsed.toMinutes().toDouble(), Direction.LAST, RelativeUnit.MINUTES)
        days < 1 ->
            formatter.format(elapsed.toHours().toDouble(), Direction.LAST, RelativeUnit.HOURS)
        days == 1L -> formatter.format(Direction.LAST, AbsoluteUnit.DAY)
        days < 7 -> formatter.format(days.toDouble(), Direction.LAST, RelativeUnit.DAYS)
        else ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(at.atZone(zone))
    }
}
