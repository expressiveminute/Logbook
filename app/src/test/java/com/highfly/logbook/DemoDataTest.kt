package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Der Demodatensatz ist Testmaterial fuer die ganze App: Wer ihn einschaltet,
 * prueft Kacheln, Filter und Listen an ihm. Deshalb wird hier festgehalten,
 * dass er das kann - genug Fluege, genug Verschiedenes und keine Eintraege, an
 * denen eine Auswertung scheitern kann.
 */
class DemoDataTest {

    private val today = LocalDate.of(2026, 10, 4)

    /** Der Bestand, den die App bekommt: feste und erzeugte Flugege zusammen. */
    private val all = DemoData.entries(today)

    private val flown = all.filter { !it.date.isAfter(today) }

    @Test
    fun `der Datensatz hat die gewuenschte Groesse`() {
        assertEquals(DemoData.SIZE, all.size)
        assertEquals(all, DemoData.entries(today))
    }

    @Test
    fun `jeder Monat seit Anfang 2021 hat Fluege`() {
        var month = YearMonth.of(2021, 1)
        val last = YearMonth.from(today)
        while (!month.isAfter(last)) {
            val flights = flown.count { YearMonth.from(it.date) == month }
            assertTrue("${month} ist leer", flights >= 2)
            month = month.plusMonths(1)
        }
    }

    @Test
    fun `jedes Jahr hat genug Fluege fuer die Jahresansicht`() {
        val perYear = flown.groupingBy { it.date.year }.eachCount()
        perYear.forEach { (year, count) ->
            assertTrue("$year hat nur $count Fluege", count >= 25)
        }
        // Geflogen ist nur bis heute; die geplanten Flugege duerfen im
        // kommenden Jahr liegen und gehoeren dort nicht in die Jahresansicht.
        assertEquals(2021, perYear.keys.min())
        assertEquals(today.year, perYear.keys.max())
    }

    @Test
    fun `die Auswertungen finden mehrere Airlines und Flugzeugtypen`() {
        assertTrue(all.mapNotNull { it.airline }.distinct().size >= 15)
        assertTrue(all.mapNotNull { it.aircraftType }.distinct().size >= 10)
        assertTrue(all.mapNotNull { it.registration }.distinct().size >= 25)
        assertTrue(all.map { it.fromAirport }.distinct().size >= 15)
        assertTrue(all.map { it.toAirport }.distinct().size >= 30)
    }

    @Test
    fun `jede Kennzeichen gehoert zu genau einer Airline`() {
        val byRegistration = all
            .filter { it.registration != null }
            .groupBy { it.registration!! }
        byRegistration.forEach { (registration, flights) ->
            val airlines = flights.mapNotNull { it.airline }.distinct()
            assertEquals("$registration fliegt fuer ${airlines.joinToString()}", 1, airlines.size)
        }
    }

    @Test
    fun `alle Reisearten und Klassen kommen vor`() {
        val types = all.mapNotNull { it.flightType }.toSet()
        assertTrue(types.containsAll(ChartData.DUTY_CANONICALS))
        assertTrue(types.contains(ChartData.PRIVATE_CANONICAL))

        val classes = all.mapNotNull { it.classType }.toSet()
        assertEquals(
            setOf("Economy", "Premium Eco", "Business", "First", "Jump"), classes
        )

        val functions = all.mapNotNull { it.function }.toSet()
        assertTrue("Funktionen: $functions", functions.size >= 3)
    }

    @Test
    fun `Layover kommen mit ihren Stunden und ohne Leerfall`() {
        val layovers = all.filter { it.layover }
        assertTrue("zu wenige Layover: ${layovers.size}", layovers.size >= 20)
        layovers.forEach {
            assertNotNull("${it.date} ohne layoverHours", it.layoverHours)
            assertTrue("${it.date}: ${it.layoverHours} Stunden", it.layoverHours!! > 0)
        }
        all.filter { !it.layover }.forEach {
            assertEquals(null, it.layoverHours)
        }
    }

    @Test
    fun `Reisebuddies und Kommentare kommen vor`() {
        val buddies = all.mapNotNull { it.travelBuddy }
        assertTrue("zu wenige Buddies: ${buddies.size}", buddies.size >= 30)
        // Die Kachel zeigt die meistgeflogenen Namen, das braucht einen Spitzenreiter
        assertTrue("kein Name faellt auf", buddies.groupingBy { it }.eachCount().values.max() >= 8)
        assertTrue(
            "Kommentare fehlen",
            flown.count { !it.comment.isNullOrBlank() } >= 30
        )
    }

    @Test
    fun `geplante Flugege liegen in der Zukunft und zaehlen nicht mit`() {
        val upcoming = all.filter { it.date.isAfter(today) }
        // Der letzte Einsatz kann eine Hin- und Rueckfahrt sein, dann sind es
        // ein paar Zeilen mehr als geplant.
        assertTrue(
            "geplante Flugege: ${upcoming.size}",
            upcoming.size in DemoData.UPCOMING_FLIGHTS..DemoData.UPCOMING_FLIGHTS + 2
        )
        assertTrue(UpcomingEntries.flown(all, today).none { it in upcoming })
        assertEquals(upcoming.toSet(), UpcomingEntries.upcoming(all, today).toSet())
    }

    @Test
    fun `jeder Eintrag traegt die Angaben, die eine Auswertung braucht`() {
        all.forEach {
            assertTrue("${it.date} $it", it.distanceKm!! > 0)
            assertTrue("${it.date} $it", it.flightMinutes!! > 0)
            assertEquals("${it.fromAirport}", 3, it.fromAirport.length)
            assertEquals("${it.toAirport}", 3, it.toAirport.length)
            assertTrue("${it.date} $it", !it.flightNumber.isNullOrBlank())
            // Entweder beide Flugzeugangaben oder keine: Ein halb gefuelltes
            // Flugzeug macht die Kacheln uneinheitlich
            assertEquals(
                "${it.date} $it",
                it.aircraftType != null,
                it.registration != null
            )
        }
    }

    @Test
    fun `gleiche Strecke bedeutet gleiche Entfernung`() {
        val distances = all
            .groupBy { it.fromAirport to it.toAirport }
            .mapValues { (_, flights) -> flights.map { it.distanceKm }.distinct() }
        distances.forEach { (route, values) ->
            assertEquals("$route: $values", 1, values.size)
        }
    }

    @Test
    fun `die Erzeugung ist bei gleichem Startwert gleich`() {
        val size = DemoData.SIZE - 74
        assertEquals(DemoData.generatedEntries(today, size), DemoData.generatedEntries(today, size))
    }

    @Test
    fun `die festen Fluge bleiben erhalten`() {
        // LH 400/401 mit seiner Uebernachtung am Ziel ist der eine Fall, an dem
        // sich der Weg eines Flugzeugs und die Layover-Stunden zeigen lassen
        val jfk = DemoData.entries().filter { it.fromAirport == "JFK" && it.toAirport == "FRA" }
        assertTrue(jfk.isNotEmpty())
        assertTrue(jfk.any { it.layover })
        assertTrue(jfk.any { it.aircraftType == "B747-8" })
        assertTrue(DemoData.entries().any { it.comment != null })
        assertTrue(DemoData.entries().any { it.travelBuddy != null })
    }
}