package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Die Erfolge ("Achievements"): welche Ziele ein Logbuch erreicht und wann.
 * Die Tests prüfen jede Gruppe - Flugmeilensteine, Fluggesellschaften,
 * Flugzeugtypen, Distanz, Zeit, Strecken, Geografie, Kontinente, Länder,
 * Reiseklassen und Besatzung - sowie die Rolle des Nutzers.
 */
class AchievementsTest {

    private val countries: (String) -> String? = mapOf(
        "FRA" to "DE",
        "MUC" to "DE",
        "BER" to "DE",
        "JFK" to "US",
        "SOF" to "BG",
        "DXB" to "AE"
    )::get

    private val locations: (String) -> Achievements.LatLon? = mapOf(
        "NRT" to Achievements.LatLon(35.7, 140.4),
        "SYD" to Achievements.LatLon(-33.9, 151.2),
        "ACC" to Achievements.LatLon(5.6, -0.2),
        "LOS" to Achievements.LatLon(6.5, 3.4)
    )::get

    private fun entry(
        id: Long,
        date: LocalDate,
        from: String = "FRA",
        to: String = "JFK",
        flightType: String? = null,
        classType: String? = null,
        function: String? = null,
        airline: String? = null,
        aircraftType: String? = null,
        distanceKm: Int? = null,
        flightMinutes: Int? = null,
        layover: Boolean = false,
        fromCountry: String? = null,
        toCountry: String? = null
    ) = LogbookEntry(
        id = id,
        date = date,
        fromAirport = from,
        toAirport = to,
        flightType = flightType,
        classType = classType,
        function = function,
        airline = airline,
        aircraftType = aircraftType,
        distanceKm = distanceKm,
        flightMinutes = flightMinutes,
        layover = layover,
        fromCountry = fromCountry,
        toCountry = toCountry
    )

    private fun evaluate(
        entries: List<LogbookEntry>,
        role: Role = Role.CREW,
        lookups: Achievements.Lookups = Achievements.Lookups(countryOf = countries)
    ) = Achievements.evaluate(entries, role, lookups)

    @Test
    fun anzahlDerErfolgeHaengtAnDerRolle() {
        // 12 Flüge, 3 Gesellschaften, 2 Typen, 2 Distanz, 4 Zeit, 1 Strecke,
        // 2 Geografie, 7 Kontinente, 7 Länder, 3 Klassen, 6 Besatzung.
        assertEquals(49, Achievements.definitionsFor(Role.CREW).size)
        assertEquals(43, Achievements.definitionsFor(Role.PASSENGER).size)
        assertTrue(
            Achievements.definitionsFor(Role.PASSENGER).none { it.crewOnly }
        )
    }

    @Test
    fun flugmeilensteineRichtenSichNachDerZeitlichenReihenfolge() {
        // Absichtlich verkehrt herum übergeben: Die Reihenfolge der Liste darf
        // für "der n-te Flug" keine Rolle spielen.
        val start = LocalDate.of(2024, 1, 1)
        val entries = (1..12).map {
            entry(it.toLong(), start.plusDays(it.toLong()))
        }.reversed()

        val result = evaluate(entries)

        assertTrue("flight_1" in result.reachedIds)
        assertTrue("flight_10" in result.reachedIds)
        assertFalse("flight_50" in result.reachedIds)
        assertEquals(
            start.plusDays(1),
            result.reached.first { it.definition.id == "flight_1" }.date
        )
        assertEquals(
            start.plusDays(10),
            result.reached.first { it.definition.id == "flight_10" }.date
        )
    }

    @Test
    fun einGroundTransferZaehltWieDieKachelFluege() {
        // Die Kachel "Flüge" des Dashboards zählt jeden Eintrag, ein Ground
        // Transfer deshalb auch - sonst stünde auf der Kachel eine Zahl, zu der
        // der passende Erfolg fehlt.
        val entries = listOf(
            entry(1, LocalDate.of(2024, 1, 1), flightType = "Ground Transfer")
        )

        assertTrue("flight_1" in evaluate(entries).reachedIds)
    }

    @Test
    fun fluggesellschaftenAbZehnVerschiedenen() {
        val start = LocalDate.of(2024, 1, 1)
        val entries = (1..10).map {
            entry(it.toLong(), start.plusDays(it.toLong()), airline = "Airline $it")
        }

        val result = evaluate(entries)

        assertTrue("airlines_10" in result.reachedIds)
        assertFalse("airlines_25" in result.reachedIds)
        assertEquals(
            start.plusDays(10),
            result.reached.first { it.definition.id == "airlines_10" }.date
        )
    }

    @Test
    fun fluggesellschaftenZaehlenUnabhaengigVonDerSchreibweise() {
        // "Airline 1" und "airline 1" sind dieselbe Gesellschaft, deshalb gibt
        // es keinen zweiten Zähler.
        val start = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, start, airline = "Airline 1"),
            entry(2, start.plusDays(1), airline = " airline 1 ")
        )

        assertFalse("airlines_10" in evaluate(entries).reachedIds)
    }

    @Test
    fun flugzeugtypenA380UndB747() {
        val start = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, start, aircraftType = "Airbus A380-800"),
            entry(2, start.plusDays(1), aircraftType = "Boeing 747-400")
        )

        val result = evaluate(entries)

        assertTrue("aircraft_a380" in result.reachedIds)
        assertTrue("aircraft_b747" in result.reachedIds)
    }

    @Test
    fun distanzUltraLangstreckeUndEinmalUmDieErde() {
        val start = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, start, distanceKm = 11_000),
            entry(2, start.plusDays(1), distanceKm = 15_000),
            entry(3, start.plusDays(2), distanceKm = 14_075)
        )

        val result = evaluate(entries)

        assertTrue("distance_ultra" in result.reachedIds)
        assertTrue("distance_earth" in result.reachedIds)
        assertEquals(
            start.plusDays(2),
            result.reached.first { it.definition.id == "distance_earth" }.date
        )
        // Vor dem letzten Flug sind erst 26.000 km zusammen.
        assertFalse("distance_earth" in evaluate(entries.dropLast(1)).reachedIds)
    }

    @Test
    fun groundTransferZaehltNichtZurDistanz() {
        val entries = listOf(
            entry(
                1, LocalDate.of(2024, 1, 1),
                flightType = "Ground Transfer", distanceKm = 12_000
            )
        )

        assertFalse("distance_ultra" in evaluate(entries).reachedIds)
    }

    @Test
    fun tausendStundenInDerLuft() {
        val start = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, start, flightMinutes = 999 * 60),
            entry(2, start.plusDays(1), flightMinutes = 60)
        )

        assertTrue("hours_1000" in evaluate(entries).reachedIds)
        assertFalse("hours_1000" in evaluate(entries.dropLast(1)).reachedIds)
    }

    @Test
    fun flugdauerGrenzen() {
        val start = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, start, flightMinutes = 45),
            entry(2, start.plusDays(1), flightMinutes = 11 * 60),
            entry(3, start.plusDays(2), flightMinutes = 16 * 60)
        )

        val result = evaluate(entries)

        assertTrue("duration_under_1h" in result.reachedIds)
        assertTrue("duration_over_10h" in result.reachedIds)
        assertTrue("duration_over_15h" in result.reachedIds)
    }

    @Test
    fun zehnMalDieselbeStrecke() {
        val start = LocalDate.of(2024, 1, 1)
        val entries = (1..10).map {
            entry(it.toLong(), start.plusDays(it.toLong()), from = "FRA", to = "JFK")
        }

        val result = evaluate(entries)

        assertTrue("route_repeat" in result.reachedIds)
        assertEquals(
            start.plusDays(10),
            result.reached.first { it.definition.id == "route_repeat" }.date
        )
    }

    @Test
    fun aequatorUndNullmeridian() {
        val start = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, start, from = "NRT", to = "SYD"),
            entry(2, start.plusDays(1), from = "ACC", to = "LOS")
        )

        val result = Achievements.evaluate(
            entries, Role.CREW, Achievements.Lookups(locationOf = locations)
        )

        assertTrue("equator" in result.reachedIds)
        assertTrue("prime_meridian" in result.reachedIds)
    }

    @Test
    fun datumsgrenzeIstNichtDerNullmeridian() {
        val lookups = Achievements.Lookups(
            locationOf = { code ->
                when (code) {
                    "AAA" -> Achievements.LatLon(0.0, 179.0)
                    "BBB" -> Achievements.LatLon(0.0, -179.0)
                    else -> null
                }
            }
        )
        val entries = listOf(entry(1, LocalDate.of(2024, 1, 1), from = "AAA", to = "BBB"))

        val result = Achievements.evaluate(entries, Role.CREW, lookups)

        assertFalse("prime_meridian" in result.reachedIds)
    }

    @Test
    fun ersterKontinentAmErstenFlug() {
        val d1 = LocalDate.of(2024, 1, 1)
        val d2 = LocalDate.of(2024, 1, 2)
        val entries = listOf(
            entry(1, d1, from = "FRA", to = "JFK"),
            entry(2, d2, from = "FRA", to = "DXB")
        )

        val result = evaluate(entries)

        assertTrue("continent_first_europe" in result.reachedIds)
        assertTrue("continent_first_north_america" in result.reachedIds)
        assertTrue("continent_first_asia" in result.reachedIds)
        assertEquals(
            d1,
            result.reached.first { it.definition.id == "continent_first_europe" }.date
        )
        assertEquals(
            d2,
            result.reached.first { it.definition.id == "continent_first_asia" }.date
        )
        assertFalse("continent_first_africa" in result.reachedIds)
    }

    @Test
    fun alleLaenderEinesKontinentsErstMitDemLetztenLand() {
        // Die Antarktis hat in der Länderliste nur fünf Einträge, deshalb lässt
        // sich "alle Länder" hier vollständig durchspielen.
        val start = LocalDate.of(2024, 3, 1)
        val codes = listOf("AQ", "BV", "TF", "HM", "GS")
        val entries = codes.mapIndexed { index, code ->
            entry(
                id = index + 1L,
                date = start.plusDays(index.toLong()),
                fromCountry = code,
                toCountry = code
            )
        }

        val complete = evaluate(entries)
        assertTrue("continent_all_antarctica" in complete.reachedIds)
        assertEquals(
            start.plusDays(4),
            complete.reached.first { it.definition.id == "continent_all_antarctica" }.date
        )

        val missing = evaluate(entries.dropLast(1))
        assertFalse("continent_all_antarctica" in missing.reachedIds)
    }

    @Test
    fun alleLaenderNutztDieUebergebeneLaenderliste() {
        // Die Oberfläche übergibt die Länderliste der App; hier eine kurze
        // Europa-Liste, damit der Erfolg ohne die vollen fünfzig Länder prüfbar
        // ist.
        val d = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, d, fromCountry = "DE", toCountry = "DE"),
            entry(2, d.plusDays(1), fromCountry = "FR", toCountry = "FR")
        )

        val result = Achievements.evaluate(
            entries,
            Role.CREW,
            Achievements.Lookups(
                countriesOfContinent = { continent ->
                    if (continent == Continents.EUROPE) listOf("DE", "FR") else emptyList()
                }
            )
        )

        assertTrue("continent_all_europe" in result.reachedIds)
    }

    @Test
    fun reiseklassenWerdenNormalisiert() {
        val d = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, d, classType = "business class"),
            entry(2, d.plusDays(1), classType = "First")
        )

        val result = evaluate(entries)

        assertTrue("class_business" in result.reachedIds)
        assertTrue("class_first" in result.reachedIds)
        assertFalse("class_premium_eco" in result.reachedIds)
    }

    @Test
    fun besatzungserfolgeAusFlugartFunktionUndLayover() {
        val d = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, d, flightType = "On Duty", function = "Purser II", layover = true),
            entry(2, d.plusDays(1), flightType = "Deadhead", function = "Flugbegleiter"),
            entry(3, d.plusDays(2), function = "Purser I")
        )

        val result = evaluate(entries)

        assertTrue("crew_on_duty" in result.reachedIds)
        assertTrue("crew_deadhead" in result.reachedIds)
        assertTrue("crew_layover" in result.reachedIds)
        assertTrue("crew_function_purser_ii" in result.reachedIds)
        assertTrue("crew_function_flight_attendant" in result.reachedIds)
        assertTrue("crew_function_purser_i" in result.reachedIds)
    }

    @Test
    fun fluggastOhneBesatzungserfolge() {
        val d = LocalDate.of(2024, 1, 1)
        val entries = listOf(
            entry(1, d, flightType = "On Duty", function = "Purser II", layover = true)
        )

        val result = evaluate(entries, Role.PASSENGER)

        assertTrue(result.reachedIds.none { it.startsWith("crew_") })
        assertTrue(result.definitions.none { it.crewOnly })
    }

    @Test
    fun zeitstrahlJuengsterZuerst() {
        val start = LocalDate.of(2024, 1, 1)
        val entries = (1..3).map { entry(it.toLong(), start.plusDays(it.toLong())) }

        val newest = evaluate(entries).reachedNewestFirst()

        assertTrue(newest.zipWithNext().all { (a, b) -> a.date >= b.date })
    }

    @Test
    fun crewFunctionOfErkenntDieSchreibweisen() {
        assertEquals(Achievements.CrewFunction.PURSER_I, Achievements.crewFunctionOf("Purser I"))
        assertEquals(Achievements.CrewFunction.PURSER_II, Achievements.crewFunctionOf("Purser II"))
        assertEquals(
            Achievements.CrewFunction.FLIGHT_ATTENDANT,
            Achievements.crewFunctionOf("Flugbegleiter")
        )
        assertEquals(
            Achievements.CrewFunction.FLIGHT_ATTENDANT,
            Achievements.crewFunctionOf("Flight Attendant")
        )
        assertNull(Achievements.crewFunctionOf("Pilot"))
        assertNull(Achievements.crewFunctionOf("  "))
        assertNull(Achievements.crewFunctionOf(null))
    }

    @Test
    fun prozentUndSummeDerAuswertung() {
        val d = LocalDate.of(2024, 1, 1)
        val result = evaluate(listOf(entry(1, d)))

        // Ein Flug erreicht: der 1. Flug sowie die beiden Kontinente des Flugs.
        assertEquals(3, result.reachedCount)
        assertEquals(49, result.total)
        assertEquals((3 * 100f / 49f).roundToInt(), result.percent)
    }
}
