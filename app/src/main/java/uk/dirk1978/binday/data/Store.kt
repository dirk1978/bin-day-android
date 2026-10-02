package uk.dirk1978.binday.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import uk.dirk1978.binday.core.GardenCalendar
import uk.dirk1978.binday.core.GardenChoice
import uk.dirk1978.binday.core.GardenRound
import uk.dirk1978.binday.core.Schedule

/** What the person chose in setup. */
data class Settings(
  val postcode: String,
  val uprn: String,
  val address: String,
  val garden: GardenChoice,
  val remind: Boolean,
  val reminderTime: LocalTime,
) {
  companion object {
    val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(17, 0)
  }
}

/** The last schedule fetched from the council, and what we've done with it. */
data class Cache(
  val uprn: String? = null,
  val schedule: Schedule? = null,
  val fetchedAt: Instant? = null,
  val error: String? = null,
  val notifiedFor: LocalDate? = null,
)

private val Context.dataStore by preferencesDataStore(name = "bin_day")

/** Settings and cache in one Preferences DataStore. */
class Store(private val context: Context) {
  private object Keys {
    val postcode = stringPreferencesKey("postcode")
    val uprn = stringPreferencesKey("uprn")
    val address = stringPreferencesKey("address")
    val garden = stringPreferencesKey("garden")
    val remind = booleanPreferencesKey("remind")
    val reminderMinute = longPreferencesKey("reminder_minute_of_day")

    val cacheUprn = stringPreferencesKey("cache_uprn")
    val schedule = stringPreferencesKey("cache_schedule")
    val fetchedAt = longPreferencesKey("cache_fetched_at")
    val error = stringPreferencesKey("cache_error")
    val notifiedFor = stringPreferencesKey("notified_for")
  }

  val settings: Flow<Settings?> = context.dataStore.data.map(::readSettings)
  val cache: Flow<Cache> = context.dataStore.data.map(::readCache)

  suspend fun currentSettings(): Settings? = settings.first()

  suspend fun currentCache(): Cache = cache.first()

  suspend fun saveSettings(s: Settings) {
    context.dataStore.edit {
      it[Keys.postcode] = s.postcode
      it[Keys.uprn] = s.uprn
      it[Keys.address] = s.address
      it[Keys.garden] = encodeGarden(s.garden)
      it[Keys.remind] = s.remind
      it[Keys.reminderMinute] = s.reminderTime.toSecondOfDay() / 60L
    }
  }

  suspend fun saveSchedule(uprn: String, schedule: Schedule, at: Instant) {
    context.dataStore.edit {
      it[Keys.cacheUprn] = uprn
      it[Keys.schedule] = json.encodeToString(scheduleSerializer, schedule.mapKeys { (d, _) -> d.toString() })
      it[Keys.fetchedAt] = at.toEpochMilli()
      it.remove(Keys.error)
    }
  }

  /** Keep the last good schedule for the same house; just record the failure. */
  suspend fun saveError(uprn: String, message: String) {
    context.dataStore.edit {
      if (it[Keys.cacheUprn] != uprn) {
        it.remove(Keys.schedule)
        it.remove(Keys.fetchedAt)
        it[Keys.cacheUprn] = uprn
      }
      it[Keys.error] = message
    }
  }

  suspend fun markNotified(date: LocalDate) {
    context.dataStore.edit { it[Keys.notifiedFor] = date.toString() }
  }

  private fun readSettings(p: Preferences): Settings? {
    val uprn = p[Keys.uprn] ?: return null
    return Settings(
      postcode = p[Keys.postcode].orEmpty(),
      uprn = uprn,
      address = p[Keys.address].orEmpty(),
      garden = decodeGarden(p[Keys.garden]),
      remind = p[Keys.remind] ?: true,
      reminderTime = p[Keys.reminderMinute]?.let { LocalTime.ofSecondOfDay(it * 60) } ?: Settings.DEFAULT_REMINDER_TIME,
    )
  }

  private fun readCache(p: Preferences) = Cache(
    uprn = p[Keys.cacheUprn],
    schedule = p[Keys.schedule]?.let { raw ->
      runCatching { json.decodeFromString(scheduleSerializer, raw) }.getOrNull()
        ?.mapKeys { (d, _) -> LocalDate.parse(d) }?.toSortedMap()
    },
    fetchedAt = p[Keys.fetchedAt]?.let(Instant::ofEpochMilli),
    error = p[Keys.error],
    notifiedFor = p[Keys.notifiedFor]?.let(LocalDate::parse),
  )

  private companion object {
    val json = Json
    val scheduleSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /** "none" | "round:B:FRIDAY" | "pair:Green". Plain strings keep the stored format obvious. */
    fun encodeGarden(choice: GardenChoice): String = when (choice) {
      GardenChoice.None -> "none"
      is GardenChoice.Round -> "round:${choice.round.calendar.name}:${choice.round.day.name}"
      is GardenChoice.PairWith -> "pair:${choice.colour}"
    }

    fun decodeGarden(raw: String?): GardenChoice {
      val parts = raw?.split(":") ?: return GardenChoice.None
      return runCatching {
        when (parts[0]) {
          "round" -> GardenChoice.Round(GardenRound(GardenCalendar.valueOf(parts[1]), DayOfWeek.valueOf(parts[2])))
          "pair" -> GardenChoice.PairWith(parts[1])
          else -> GardenChoice.None
        }
      }.getOrDefault(GardenChoice.None)
    }
  }
}
