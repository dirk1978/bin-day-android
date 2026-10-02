package uk.dirk1978.binday.core

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live pages for DY9 0LU (the uk_bin_collection project's public test
 * property) and the council's 2026 garden waste street table, saved on
 * 1 Oct 2026. Collections at DY9 0LU are on Thursdays.
 */
class CouncilTest {
  private fun fixture(name: String) =
    requireNotNull(javaClass.classLoader?.getResource(name)) { "missing $name" }.readText()

  private val schedule = Council.parseSchedule(fixture("fixture-schedule.html"))
  private val gardenRows = Council.parseGardenStreets(fixture("fixture-garden-streets.html"))

  private fun d(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)
  private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, month, day, hour, minute)
  private fun List<Collection>.summary() = map { "${it.date} ${it.bins.joinToString("+")}" }

  @Test fun postcodesAreNormalisedAndValidated() {
    assertEquals("B61 7DN", Council.normalisePostcode(" b617dn "))
    assertEquals("DY9 0LU", Council.normalisePostcode("dy9  0lu"))
    assertTrue(Council.isValidPostcode("SW1A1AA"))
    assertFalse(Council.isValidPostcode("hello"))
    assertFalse(Council.isValidPostcode(""))
  }

  @Test fun addressesAreParsedAndSortedByHouseNumber() {
    val addresses = Council.parseAddresses(fixture("fixture-addresses.html"))
    assertEquals(47, addresses.size)
    assertEquals(
      listOf("1 WILLOW CLOSE", "3 WILLOW CLOSE", "4 WILLOW CLOSE", "5 WILLOW CLOSE"),
      addresses.take(4).map { it.address },
    )
    assertEquals("100120584653", addresses[1].uprn)
    assertEquals("51 WILLOW CLOSE", addresses.last().address)
  }

  @Test fun namedHousesSortAfterNumberedOnes() {
    val html = """<option value="1">THE OAST</option><option value="2">10 A ROAD</option><option value="3">2 A ROAD</option>"""
    assertEquals(listOf("3", "2", "1"), Council.parseAddresses(html).map { it.uprn })
  }

  @Test fun aPostcodeOutsideTheDistrictHasNoAddresses() {
    assertTrue(Council.parseAddresses(fixture("fixture-no-addresses.html")).isEmpty())
  }

  @Test fun theSchedulePageIsParsed() {
    assertEquals(
      mapOf(d(10, 1) to listOf("Green"), d(10, 8) to listOf("Grey"), d(10, 15) to listOf("Green"), d(10, 22) to listOf("Grey")),
      schedule,
    )
  }

  @Test fun theGardenStreetTableIsParsedSkippingHeaders() {
    assertEquals(1339, gardenRows.size)
    assertEquals(
      GardenStreet("WILLOW CLOSE", "HAGLEY", listOf("DY9 0LU"), THURSDAY, GardenCalendar.A),
      gardenRows.first { it.street == "WILLOW CLOSE" && it.locality == "HAGLEY" },
    )
  }

  @Test fun aPostcodeFindsItsBrownBinRound() {
    assertEquals(listOf(GardenRound(GardenCalendar.A, THURSDAY)), Collections.gardenRoundsFor(gardenRows, "dy90lu", "3 WILLOW CLOSE"))
    assertTrue(Collections.gardenRoundsFor(gardenRows, "SW1A 1AA").isEmpty())
  }

  @Test fun cellsWithSeveralPostcodesMatchEach() {
    // "B61 7FT / B61 7FW" in one cell.
    assertEquals(listOf(GardenRound(GardenCalendar.B, THURSDAY)), Collections.gardenRoundsFor(gardenRows, "B61 7FW"))
    assertEquals(listOf(GardenRound(GardenCalendar.B, THURSDAY)), Collections.gardenRoundsFor(gardenRows, "B61 7FT"))
  }

  @Test fun aPostcodeWithTwoRoundsReturnsBoth() {
    assertEquals(
      listOf(GardenRound(GardenCalendar.B, WEDNESDAY), GardenRound(GardenCalendar.B, THURSDAY)),
      Collections.gardenRoundsFor(gardenRows, "B45 9QW"),
    )
  }

  @Test fun calendarWeeksMatchTheLeaflets() {
    val b = Collections.brownDates(GardenRound(GardenCalendar.B, FRIDAY))
    assertEquals(d(3, 6), b.first()) // W/C 2 Mar
    assertEquals(d(12, 11), b.last()) // W/E 11 Dec
    assertTrue(d(10, 2) in b)
    assertFalse(d(10, 9) in b)
    val a = Collections.brownDates(GardenRound(GardenCalendar.A, MONDAY))
    assertEquals(listOf(d(2, 23), d(11, 30)), listOf(a.first(), a.last())) // W/E 4 Dec
    val c = Collections.brownDates(GardenRound(GardenCalendar.C, MONDAY))
    assertEquals(listOf(d(2, 23), d(11, 16)), listOf(c.first(), c.last())) // W/E 20 Nov
  }

  @Test fun brownIsAddedOnTheRoundsWeeks() {
    assertEquals(
      listOf("2026-10-01 Green", "2026-10-08 Grey", "2026-10-15 Green", "2026-10-22 Grey"),
      Collections.upcoming(schedule, d(9, 30), GardenChoice.None).summary(),
    )
    assertEquals(
      listOf("2026-10-01 Green", "2026-10-08 Grey+Brown", "2026-10-15 Green", "2026-10-22 Grey+Brown"),
      Collections.upcoming(schedule, d(9, 30), GardenChoice.Round(GardenRound(GardenCalendar.A, THURSDAY))).summary(),
    )
  }

  @Test fun aDifferentBrownDayIsItsOwnCollection() {
    assertEquals(
      listOf("2026-10-01 Green", "2026-10-06 Brown", "2026-10-08 Grey", "2026-10-15 Green", "2026-10-20 Brown", "2026-10-22 Grey"),
      Collections.upcoming(schedule, d(9, 30), GardenChoice.Round(GardenRound(GardenCalendar.A, TUESDAY))).summary(),
    )
  }

  @Test fun brownStopsAtTheHorizonAndTheSeason() {
    val late = mapOf(d(12, 10) to listOf("Green"), d(12, 17) to listOf("Grey"))
    assertEquals(
      listOf("2026-12-10 Green+Brown", "2026-12-17 Grey"),
      Collections.upcoming(late, d(12, 1), GardenChoice.Round(GardenRound(GardenCalendar.B, THURSDAY))).summary(),
    )
    val nextYear = mapOf(d(3, 4, 2027) to listOf("Green"))
    assertEquals(
      listOf("2027-03-04 Green"),
      Collections.upcoming(nextYear, d(3, 1, 2027), GardenChoice.Round(GardenRound(GardenCalendar.B, THURSDAY))).summary(),
    )
  }

  @Test fun pairWithFallbackResolvesToACalendar() {
    assertEquals(GardenRound(GardenCalendar.A, THURSDAY), Collections.resolveGardenRound(schedule, GardenChoice.PairWith("Grey")))
    assertEquals(GardenRound(GardenCalendar.B, THURSDAY), Collections.resolveGardenRound(schedule, GardenChoice.PairWith("Green")))
    assertNull(Collections.resolveGardenRound(schedule, GardenChoice.None))
  }

  @Test fun before9amOnCollectionDayTheBinsAreStillToGo() {
    val state = Collections.state(schedule, at(10, 1, 8, 59), GardenChoice.PairWith("Green"))
    assertNull(state.collected)
    assertEquals(Collection(d(10, 1), listOf("Green", "Brown")), state.next)
  }

  @Test fun from9amOnCollectionDayTheVanHasBeen() {
    val state = Collections.state(schedule, at(10, 1, 9, 0), GardenChoice.PairWith("Green"))
    assertEquals(Collection(d(10, 1), listOf("Green", "Brown")), state.collected)
    assertEquals(Collection(d(10, 8), listOf("Grey")), state.next)
  }

  @Test fun theEveningBeforeIsNeverCollected() {
    assertNull(Collections.state(schedule, at(9, 30, 21, 0), GardenChoice.None).collected)
  }

  @Test fun theLastCollectionOnceDoneLeavesNextEmpty() {
    val state = Collections.state(mapOf(d(10, 22) to listOf("Grey")), at(10, 22, 12, 0), GardenChoice.None)
    assertEquals(CollectionState(Collection(d(10, 22), listOf("Grey")), null), state)
  }

  @Test fun theReminderRunsFromTheEveningBeforeUntil9am() {
    val window = Collections.reminderWindow(Collection(d(10, 1), listOf("Green")), LocalTime.of(17, 0))
    assertEquals(at(9, 30, 17, 0), window.start)
    assertEquals(at(10, 1, 9, 0), window.endInclusive)
  }

  @Test fun descriptionsReadNaturally() {
    assertEquals("Green bin", Collections.describe(listOf("Green")))
    assertEquals("Green and Brown bins", Collections.describe(listOf("Green", "Brown")))
    assertEquals("Green, Grey and Brown bins", Collections.describe(listOf("Green", "Grey", "Brown")))
    assertEquals("tomorrow", Collections.whenLabel(d(10, 2), d(10, 1)))
    assertEquals("Thursday", Collections.whenLabel(d(10, 8), d(10, 2)))
    assertEquals("Fridays, Calendar B (fortnightly, 2 Mar to 11 Dec)", GardenRound(GardenCalendar.B, FRIDAY).describe())
  }
}
