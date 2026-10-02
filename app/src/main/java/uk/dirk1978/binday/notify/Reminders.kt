package uk.dirk1978.binday.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import uk.dirk1978.binday.MainActivity
import uk.dirk1978.binday.R
import uk.dirk1978.binday.core.Collection
import uk.dirk1978.binday.core.Collections
import uk.dirk1978.binday.data.Store
import uk.dirk1978.binday.ui.BinArt

/**
 * The evening-before reminder. [sync] is the single entry point: called after
 * every refresh, when the reminder alarm fires, after boot and when settings
 * change. From the cached schedule it either shows the notification (we're
 * inside the window), or sets an alarm for when the window opens.
 *
 * The window runs from the chosen time on the evening before until 9am on
 * collection day, when the notification removes itself (setTimeoutAfter).
 * Alarms are exact when the person has allowed "Alarms & reminders", and
 * otherwise inexact (see [canAlarmExactly]).
 */
object Reminders {
  const val CHANNEL_ID = "bin_night"
  private const val NOTIFICATION_ID = 1
  private const val ALARM_REQUEST = 1
  const val ACTION_DONE = "uk.dirk1978.binday.DONE"

  suspend fun sync(context: Context, now: LocalDateTime = LocalDateTime.now()) {
    val store = Store(context)
    val settings = store.currentSettings()
    val cache = store.currentCache()
    cancelAlarm(context)

    val schedule = cache.schedule?.takeIf { settings != null && cache.uprn == settings.uprn }
    if (settings == null || schedule == null || !settings.remind) {
      cancel(context)
      return
    }

    val next = Collections.state(schedule, now, settings.garden).next ?: return cancel(context)
    val window = Collections.reminderWindow(next, settings.reminderTime)
    if (now < window.start) {
      // Anything showing now is left over from an earlier collection.
      cancel(context)
      scheduleAlarm(context, window.start)
    } else {
      // Inside the window. Post once per collection, so "Done" isn't undone
      // by the next refresh. Wake again at 9am to set up the next window.
      if (cache.notifiedFor != next.date) {
        show(context, next, now)
        store.markNotified(next.date)
      }
      scheduleAlarm(context, window.endInclusive.plusMinutes(1))
    }
  }

  fun createChannel(context: Context) {
    val channel = NotificationChannel(CHANNEL_ID, "Bin night reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
      description = "Which bins to put out, on the evening before collection"
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

  private fun show(context: Context, next: Collection, now: LocalDateTime) {
    if (!canNotify(context)) return
    val day = next.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK))
    val bins = Collections.describe(next.bins)
    val until = Duration.between(now, next.date.atTime(Collections.COLLECTED_AT)).toMillis().coerceAtLeast(1)
    val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)

    val open = PendingIntent.getActivity(
      context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    val done = PendingIntent.getBroadcast(
      context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_DONE), PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_bin)
      .setLargeIcon(BinArt.bin(size, next.bins))
      .setColor(BinArt.colourOf(next.bins.first()))
      .setContentTitle("Bin night: ${bins}")
      .setContentText("Put the $bins out for ${Collections.whenLabel(next.date, now.toLocalDate())}'s collection ($day).")
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setTimeoutAfter(until)
      .setContentIntent(open)
      .addAction(0, "Done", done)
      .build()
    try {
      NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    } catch (_: SecurityException) {
      // Permission revoked between the check and the post; nothing to do.
    }
  }

  /**
   * Exact alarms need the "Alarms & reminders" special access, which Android
   * 14+ leaves off for new installs. Without it the alarm is inexact and an
   * idle phone may deliver it up to an hour late.
   */
  fun canAlarmExactly(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
      context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

  private fun scheduleAlarm(context: Context, at: LocalDateTime) {
    val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val alarms = context.getSystemService(AlarmManager::class.java)
    if (canAlarmExactly(context)) {
      alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent(context))
    } else {
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent(context))
    }
  }

  private fun cancelAlarm(context: Context) =
    context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))

  private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
    context, ALARM_REQUEST, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )
}
