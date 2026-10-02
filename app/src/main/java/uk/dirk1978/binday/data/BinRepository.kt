package uk.dirk1978.binday.data

import android.content.Context
import java.time.Duration
import java.time.Instant
import uk.dirk1978.binday.notify.Reminders

/** Fetches the schedule from the council and keeps the reminder in step with it. */
class BinRepository(private val context: Context) {
  val store = Store(context)

  /**
   * Fetch the schedule for the saved address. With [ifStale], skip the
   * network if the cache for this house is fresher than [STALE_AFTER].
   * Always re-syncs the reminder afterwards, from whatever is cached.
   */
  suspend fun refresh(ifStale: Boolean = false) {
    val settings = store.currentSettings() ?: return
    val cache = store.currentCache()
    val fresh = cache.uprn == settings.uprn && cache.schedule != null &&
      cache.fetchedAt?.let { Duration.between(it, Instant.now()) < STALE_AFTER } == true
    if (!(ifStale && fresh)) {
      try {
        store.saveSchedule(settings.uprn, CouncilApi.fetchSchedule(settings.uprn), Instant.now())
      } catch (e: CouncilException) {
        store.saveError(settings.uprn, e.message.orEmpty())
      }
    }
    Reminders.sync(context)
  }

  companion object {
    val STALE_AFTER: Duration = Duration.ofHours(6)
  }
}
