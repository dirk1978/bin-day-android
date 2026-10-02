package uk.dirk1978.binday.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Brown bin weeks from the council's 2026 leaflets: fortnightly, from the week
 * commencing [first] to the week commencing [last] (both Mondays). A and C
 * share weeks; C is Redditch's calendar for 3 border streets and stops
 * earlier. Update these, and Council.GARDEN_STREETS_URL, each January.
 */
enum class GardenCalendar(val first: LocalDate, val last: LocalDate, val label: String) {
  A(LocalDate.of(2026, 2, 23), LocalDate.of(2026, 11, 30), "fortnightly, 23 Feb to 4 Dec"),
  B(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 12, 7), "fortnightly, 2 Mar to 11 Dec"),
  C(LocalDate.of(2026, 2, 23), LocalDate.of(2026, 11, 16), "fortnightly, 23 Feb to 20 Nov"),
}

/** A household's brown bin: which calendar's weeks, and which weekday. */
data class GardenRound(val calendar: GardenCalendar, val day: DayOfWeek) {
  /** "Fridays, Calendar B (fortnightly, 2 Mar to 11 Dec)". */
  fun describe(): String {
    val name = day.name.lowercase(Locale.UK).replaceFirstChar { it.titlecase(Locale.UK) }
    return "${name}s, Calendar ${calendar.name} (${calendar.label})"
  }
}

/** What the household chose for garden waste. */
sealed interface GardenChoice {
  data object None : GardenChoice

  /** From the council's street table. */
  data class Round(val round: GardenRound) : GardenChoice

  /** Fallback when the street isn't listed: "goes out with my Green bin". */
  data class PairWith(val colour: String) : GardenChoice
}

data class Collection(val date: LocalDate, val bins: List<String>)

/**
 * Where we are relative to collections. [collected] is today's collection once
 * it is past [Collections.COLLECTED_AT] (the van's been), and [next] is then
 * the one after it.
 */
data class CollectionState(val collected: Collection?, val next: Collection?)

object Collections {
  /** From this time on collection day we assume the van has been. */
  val COLLECTED_AT: LocalTime = LocalTime.of(9, 0)

  /** The garden waste rounds the council lists for a postcode. Usually one; a few have two. */
  fun gardenRoundsFor(rows: List<GardenStreet>, postcode: String, address: String = ""): List<GardenRound> {
    val pc = Council.normalisePostcode(postcode)
    var matches = rows.filter { pc in it.postcodes }
    // Prefer rows whose street is in the address, when that narrows it down.
    val byStreet = matches.filter { address.uppercase(Locale.UK).contains(it.street.uppercase(Locale.UK)) }
    if (byStreet.isNotEmpty()) matches = byStreet
    return matches.map { GardenRound(it.calendar, it.day) }.distinct()
  }

  /** Brown bin dates for a round. */
  fun brownDates(round: GardenRound): List<LocalDate> {
    if (round.day == DayOfWeek.SATURDAY || round.day == DayOfWeek.SUNDAY) return emptyList()
    val offset = (round.day.value - DayOfWeek.MONDAY.value).toLong()
    return generateSequence(round.calendar.first) { it.plusWeeks(2) }
      .takeWhile { !it.isAfter(round.calendar.last) }
      .map { it.plusDays(offset) }
      .toList()
  }

  /**
   * The household's brown bin round. For the "goes out with my Green bin"
   * fallback it's the calendar whose weeks contain that bin's collections, on
   * that bin's day.
   */
  fun resolveGardenRound(schedule: Schedule, choice: GardenChoice): GardenRound? = when (choice) {
    GardenChoice.None -> null
    is GardenChoice.Round -> choice.round
    is GardenChoice.PairWith -> {
      val dates = schedule.filterValues { choice.colour in it }.keys
      dates.firstOrNull()?.let { first ->
        listOf(GardenCalendar.B, GardenCalendar.A)
          .map { GardenRound(it, first.dayOfWeek) }
          .firstOrNull { round -> brownDates(round).any { it in dates } }
      }
    }
  }

  /**
   * The schedule with the brown bin merged in. The council's bin page never
   * lists it (it's a paid extra) and it can be on a different day from the
   * other bins. Brown dates stop at the last date the council page covers, so
   * the list doesn't run on with brown-only weeks.
   */
  fun withGarden(schedule: Schedule, round: GardenRound?): Schedule {
    if (round == null || schedule.isEmpty()) return schedule
    val horizon = schedule.keys.max()
    val merged = schedule.mapValues { it.value.toMutableList() }.toSortedMap()
    for (date in brownDates(round)) {
      if (date.isAfter(horizon)) break
      val bins = merged.getOrPut(date) { mutableListOf() }
      if ("Brown" !in bins) bins.add("Brown")
    }
    return merged
  }

  /** Collections on or after [today], with the household's brown bin added. */
  fun upcoming(schedule: Schedule, today: LocalDate, garden: GardenChoice): List<Collection> =
    withGarden(schedule, resolveGardenRound(schedule, garden))
      .filterKeys { !it.isBefore(today) }
      .toSortedMap()
      .map { (date, bins) -> Collection(date, bins) }

  fun state(schedule: Schedule, now: LocalDateTime, garden: GardenChoice): CollectionState {
    val list = upcoming(schedule, now.toLocalDate(), garden)
    val first = list.firstOrNull()
    return if (first != null && first.date == now.toLocalDate() && !now.toLocalTime().isBefore(COLLECTED_AT)) {
      CollectionState(collected = first, next = list.getOrNull(1))
    } else {
      CollectionState(collected = null, next = first)
    }
  }

  /**
   * When the reminder for [collection] shows: [reminderTime] on the evening
   * before, until [COLLECTED_AT] on the day.
   */
  fun reminderWindow(collection: Collection, reminderTime: LocalTime): ClosedRange<LocalDateTime> =
    collection.date.minusDays(1).atTime(reminderTime)..collection.date.atTime(COLLECTED_AT)

  /** ["Green", "Brown"] -> "Green and Brown bins". */
  fun describe(bins: List<String>): String = when (bins.size) {
    0 -> "no bins"
    1 -> "${bins[0]} bin"
    else -> "${bins.dropLast(1).joinToString(", ")} and ${bins.last()} bins"
  }

  /** "today", "tomorrow" or the weekday name. */
  fun whenLabel(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
    0L -> "today"
    1L -> "tomorrow"
    else -> date.dayOfWeek.name.lowercase(Locale.UK).replaceFirstChar { it.titlecase(Locale.UK) }
  }
}
