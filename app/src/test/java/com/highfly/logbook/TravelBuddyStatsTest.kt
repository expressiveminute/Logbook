package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.time.LocalDate
import java.util.Locale

/**
 * Tests fuer die Auswertung des Feldes "Reisebuddy". Das Feld ist Freitext,
 * deshalb ist die Zerlegung der Text genauso wichtig wie die Zaehlung: Ein
 * Tippfehler in der Schreibweise darf keine zweite Person in der Liste
 * erzeugen, und ein Flug mit zwei Buddies muss bei beiden zaehlen.
 */
class TravelBuddyStatsTest {

    private val collator: Collator = Collator.getInstance(Locale.GERMANY)

    private fun entry(
        date: LocalDate,
        travelBuddy: String?
    ) = LogbookEntry(
        date = date,
        flightType = "On Duty",
        fromAirport = "FRA",
        toAirport = "JFK",
        travelBuddy = travelBuddy
    )

    private fun entry(buddy: String?) = entry(LocalDate.of(2026, 5, 4), buddy)

    @Test
    fun parse_zerlegtNamenAnKomma() {
        assertEquals(listOf("Anna", "Bob"), TravelBuddyStats.parse("Anna, Bob"))
    }

    @Test
    fun parse_zerlegtAuchAnSemikolonUndZeilenumbruch() {
        assertEquals(
            listOf("Anna", "Bob", "Carla"),
            TravelBuddyStats.parse("Anna; Bob\nCarla")
        )
    }

    @Test
    fun parse_ignoriertLeerraumUndLeereStuecke() {
        assertEquals(
            listOf("Anna Beispiel", "Bob"),
            TravelBuddyStats.parse("  Anna   Beispiel ,,  , Bob  ")
        )
    }

    @Test
    fun parse_gleicherNameNichtZweimal() {
        assertEquals(listOf("Anna"), TravelBuddyStats.parse("Anna, anna, ANNA"))
    }

    @Test
    fun parse_leererTextIstKeinBuddy() {
        assertEquals(emptyList<String>(), TravelBuddyStats.parse(null))
        assertEquals(emptyList<String>(), TravelBuddyStats.parse(""))
        assertEquals(emptyList<String>(), TravelBuddyStats.parse("  ,  ; "))
    }

    @Test
    fun build_zaehltDieFluegeJeBuddy() {
        val buddies = TravelBuddyStats.build(
            listOf(
                entry("Anna"),
                entry("Anna, Bob"),
                entry("Bob")
            ),
            collator
        )
        assertEquals(listOf("Anna", "Bob"), buddies.map { it.name })
        assertEquals(listOf(2, 2), buddies.map { it.flights })
    }

    @Test
    fun build_sortiertNachZahlAbsteigend() {
        val buddies = TravelBuddyStats.build(
            listOf(
                entry("Anna"),
                entry("Bob"),
                entry("Bob"),
                entry("Bob")
            ),
            collator
        )
        assertEquals(listOf("Bob", "Anna"), buddies.map { it.name })
        assertEquals(listOf(3, 1), buddies.map { it.flights })
    }

    @Test
    fun build_zaehltDenselbenNamenAufEinemFlugEinmal() {
        val buddies = TravelBuddyStats.build(listOf(entry("Anna, anna")), collator)
        assertEquals(1, buddies.size)
        assertEquals(1, buddies.single().flights)
    }

    @Test
    fun build_vereinigtNamenUnabhaengigVonDerSchreibweise() {
        val buddies = TravelBuddyStats.build(
            listOf(
                entry(LocalDate.of(2026, 1, 5), "anna"),
                entry(LocalDate.of(2026, 7, 9), "Anna")
            ),
            collator
        )
        // Eine Zeile, und zwar mit der Schreibweise des ersten Eintrags.
        assertEquals(listOf("anna"), buddies.map { it.name })
        assertEquals(2, buddies.single().flights)
    }

    @Test
    fun build_beiGleicherZahlGewinntDerZuletztGeflogeneBuddy() {
        val buddies = TravelBuddyStats.build(
            listOf(
                entry(LocalDate.of(2026, 1, 5), "Anna"),
                entry(LocalDate.of(2026, 7, 9), "Bob")
            ),
            collator
        )
        assertEquals(listOf("Bob", "Anna"), buddies.map { it.name })
    }

    @Test
    fun build_ohneEintragKeineListe() {
        assertEquals(emptyList<TravelBuddyStats.Buddy>(), TravelBuddyStats.build(emptyList(), collator))
        assertEquals(emptyList<TravelBuddyStats.Buddy>(), TravelBuddyStats.build(listOf(entry(null)), collator))
    }

    @Test
    fun build_merktSichDasJuengsteDatum() {
        val buddies = TravelBuddyStats.build(
            listOf(
                entry(LocalDate.of(2026, 1, 5), "Anna"),
                entry(LocalDate.of(2026, 7, 9), "Anna"),
                entry(LocalDate.of(2026, 3, 3), "Anna")
            ),
            collator
        )
        assertEquals(LocalDate.of(2026, 7, 9), buddies.single().lastDate)
    }

    @Test
    fun flightsWithBuddy_zaehltNurEintraegeMitNamen() {
        val entries = listOf(
            entry("Anna"),
            entry("Anna, Bob"),
            entry(null),
            entry(""),
            entry("  ,  ")
        )
        assertEquals(2, TravelBuddyStats.flightsWithBuddy(entries))
    }

    @Test
    fun geplanteFluegeMitBuddyZaehlenErstAbIhremTag() {
        // Der Fall aus der Praxis: Zwei Flüge (LH726 am 24.10., LH727 am
        // 25.10.) mit Buddy, beide noch in der Zukunft. Die Kachel zeigt sie
        // nicht, weil geplante Fluege in keiner Auswertung mitzaehlen - das
        // ist der Schnitt aus [UpcomingEntries], nicht der Buddy.
        val heute = LocalDate.of(2026, 10, 4)
        val entries = listOf(
            entry(LocalDate.of(2026, 10, 24), "Anna"),
            entry(LocalDate.of(2026, 10, 25), "Anna")
        )

        val geflogen = UpcomingEntries.flown(entries, heute)

        assertEquals(0, TravelBuddyStats.build(geflogen).size)
        // Der Buddy selbst ist gespeichert und wird am Tag des Fluges gezaehlt.
        assertEquals(2, TravelBuddyStats.build(UpcomingEntries.flown(entries, LocalDate.of(2026, 10, 25))).first().flights)
    }

    @Test
    fun tileCount_zaehltVerschiedeneBuddiesNichtDieFluge() {
        val entries = listOf(
            entry("Anna"),
            entry("Anna"),
            entry("Anna, Bob")
        )
        // Drei Fluege, aber nur zwei Personen.
        assertEquals(2, DashboardStats.tileCount("reisebuddy", entries))
    }

    @Test
    fun csvHinUndZurueckBehaeltReisebuddy() {
        val original = entry("Anna, Bob")
        val zurueck = LogbookCsv.fromCsv(LogbookCsv.toCsv(listOf(original))).single()
        assertEquals("Anna, Bob", zurueck.travelBuddy)
    }

    @Test
    fun matches_trifftDenNamenInEinemEintrag() {
        assertTrue(TravelBuddyStats.matches("Anna, Bob", "Anna"))
        assertTrue(TravelBuddyStats.matches("Anna, Bob", "bob"))
        assertTrue(TravelBuddyStats.matches("Anna", "ann"))
        assertFalse(TravelBuddyStats.matches("Anna, Bob", "Clara"))
        assertFalse(TravelBuddyStats.matches(null, "Anna"))
    }

    @Test
    fun matches_ohneFilterTrifftJedenEintrag() {
        assertTrue(TravelBuddyStats.matches("Anna", ""))
        assertTrue(TravelBuddyStats.matches("Anna", "   "))
        assertTrue(TravelBuddyStats.matches(null, ""))
    }

    @Test
    fun matches_passtNichtUeberDenTrennerHinweg() {
        // "Bob" steht im zweiten Namen, "ann, b" waere kein Name.
        assertFalse(TravelBuddyStats.matches("Anna, Bob", "a, b"))
    }

    /** Eine Datei ohne die Spalte bleibt lesbar, das Feld kommt leer heraus. */
    @Test
    fun csvOhneSpalteLiestSichOhneBuddy() {
        val csv = "date,flightType,classType,fromAirport,toAirport,airline,flightNumber," +
            "aircraftType,registration,distanceKm,flightMinutes,layover,comment,function\n" +
            "2026-05-04,On Duty,Business,FRA,JFK,LH,401,A350,D-ABXA,6400,420,0,,\n"
        val entry = LogbookCsv.fromCsv(csv).single()
        assertEquals("FRA", entry.fromAirport)
        assertNull(entry.travelBuddy)
    }
}
