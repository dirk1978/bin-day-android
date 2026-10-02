package uk.dirk1978.binday.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
  val pending = goAsync()
  CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
    try {
      block()
    } finally {
      pending.finish()
    }
  }
}

/** The reminder alarm, and the notification's "Done" button. */
class ReminderReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Reminders.ACTION_DONE) {
      Reminders.cancel(context)
      return
    }
    runAsync { Reminders.sync(context) }
  }
}

/**
 * Alarms don't survive a reboot, and a clock or time zone change moves the
 * reminder, so re-sync on all of those, after an app update, and when exact
 * alarms are allowed (so the pending alarm is re-set as an exact one).
 */
class SystemEventsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    RefreshWorker.schedule(context)
    runAsync { Reminders.sync(context) }
  }
}
