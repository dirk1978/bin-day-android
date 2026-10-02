package uk.dirk1978.binday

import android.app.Application
import uk.dirk1978.binday.notify.RefreshWorker
import uk.dirk1978.binday.notify.Reminders

class BinDayApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    Reminders.createChannel(this)
    RefreshWorker.schedule(this)
  }
}
