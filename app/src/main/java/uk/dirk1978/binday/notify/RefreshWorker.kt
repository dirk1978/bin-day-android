package uk.dirk1978.binday.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import uk.dirk1978.binday.data.BinRepository

/** Refreshes the schedule from the council every 6 hours, when there's a network. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    BinRepository(applicationContext).refresh()
    return Result.success()
  }

  companion object {
    private const val NAME = "refresh-schedule"

    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
