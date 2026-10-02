package uk.dirk1978.binday.core

import java.time.LocalDate
import java.util.Locale

/**
 * Parsing for Bromsgrove District Council's two bin websites. Pure Kotlin with
 * no Android types, so it is covered by plain JVM unit tests against saved
 * copies of the real pages. A port of the Chrome extension's lib.js.
 */
object Council {
  const val BASE_URL = "https://bincollections.bromsgrove.gov.uk"

  /** The council's per-street garden waste (brown bin) table. Republished each year. */
  const val GARDEN_STREETS_URL =
    "https://www.bromsgrove.gov.uk/residents/my-place/bins-and-recycling/garden-waste-service/garden-waste-collection-dates-2026/"

  private val POSTCODE = Regex("^[A-Z]{1,2}\\d[A-Z\\d]? \\d[A-Z]{2}$")
  private val POSTCODE_IN_TEXT = Regex("[A-Z]{1,2}\\d[A-Z\\d]? ?\\d[A-Z]{2}")
  private val OPTION = Regex("<option[^>]*value=\"(\\d+)\"[^>]*>([^<]+)", RegexOption.IGNORE_CASE)
  private val HEADING = Regex("class=\"heading\">\\s*([^<]+?)\\s*<")
  private val DATE =
    Regex("\\b(?:Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day,\\s+(\\d{1,2})\\s+([A-Za-z]+)\\s+(\\d{4})")
  private val CELL = "<td[^>]*>([^<]*)</td>\\s*"
  private val GARDEN_ROW = Regex("<tr[^>]*>\\s*${CELL.repeat(5)}</tr>", RegexOption.IGNORE_CASE)

  /** "dy90lu" -> "DY9 0LU". */
  fun normalisePostcode(postcode: String): String {
    val compact = postcode.filterNot { it.isWhitespace() }.uppercase(Locale.UK)
    return if (compact.length < 5) compact else "${compact.dropLast(3)} ${compact.takeLast(3)}"
  }

  fun isValidPostcode(postcode: String): Boolean = POSTCODE.matches(normalisePostcode(postcode))

  /** The postcode search page -> addresses, sorted by house number. */
  fun parseAddresses(html: String): List<Address> =
    OPTION.findAll(html)
      .map { Address(uprn = it.groupValues[1], address = clean(it.groupValues[2])) }
      .sortedWith(houseNumberOrder)
      .toList()

  /** The details page -> {date: [colours]}. */
  fun parseSchedule(html: String): Schedule {
    val schedule = sortedMapOf<LocalDate, MutableSet<String>>()
    for (block in html.split("class=\"collection-container\"").drop(1)) {
      val colour = HEADING.find(block)?.groupValues?.get(1)
        ?.replace(Regex("\\s+bin$", RegexOption.IGNORE_CASE), "")?.trim() ?: continue
      for (m in DATE.findAll(block)) {
        val month = monthNumber(m.groupValues[2]) ?: continue
        val date = LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
        schedule.getOrPut(date) { sortedSetOf() }.add(colour)
      }
    }
    return schedule.mapValues { it.value.toList() }.toSortedMap()
  }

  /**
   * The garden waste street table -> rows. Some cells hold several postcodes
   * ("B61 7FT / B61 7FW"); header rows repeat down the page and are skipped.
   */
  fun parseGardenStreets(html: String): List<GardenStreet> =
    GARDEN_ROW.findAll(html).mapNotNull { m ->
      val (street, locality, postcodeCell, day, calendarCell) = m.groupValues.drop(1).map(::clean)
      val calendar = Regex("^Calendar ([A-Z])$", RegexOption.IGNORE_CASE)
        .find(calendarCell)?.groupValues?.get(1)?.uppercase(Locale.UK)
        ?.let { runCatching { GardenCalendar.valueOf(it) }.getOrNull() }
      val weekday = runCatching { java.time.DayOfWeek.valueOf(day.uppercase(Locale.UK)) }.getOrNull()
      if (calendar == null || weekday == null) return@mapNotNull null
      val postcodes = POSTCODE_IN_TEXT.findAll(postcodeCell.uppercase(Locale.UK))
        .map { normalisePostcode(it.value) }.toList()
      GardenStreet(street, locality, postcodes, weekday, calendar)
    }.toList()

  private fun monthNumber(name: String): Int? =
    java.time.Month.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

  private fun clean(text: String): String =
    text.replace("&#39;", "'").replace("&#x27;", "'").replace("&quot;", "\"")
      .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
      .replace(Regex("[\\s\\u00a0]+"), " ").trim()

  // The council sorts as strings, so "10" comes before "3". Named houses go last.
  private val houseNumberOrder = Comparator<Address> { a, b ->
    val na = a.address.takeWhile { it.isDigit() }.toIntOrNull()
    val nb = b.address.takeWhile { it.isDigit() }.toIntOrNull()
    when {
      (na == null) != (nb == null) -> if (na == null) 1 else -1
      na != null && nb != null && na != nb -> na.compareTo(nb)
      else -> a.address.compareTo(b.address)
    }
  }
}

data class Address(val uprn: String, val address: String)

data class GardenStreet(
  val street: String,
  val locality: String,
  val postcodes: List<String>,
  val day: java.time.DayOfWeek,
  val calendar: GardenCalendar,
)

/** Collection dates to bin colours, in date order. */
typealias Schedule = Map<LocalDate, List<String>>
