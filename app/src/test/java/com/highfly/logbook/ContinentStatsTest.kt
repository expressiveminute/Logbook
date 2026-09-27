package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinentStatsTest {

    private fun country(iso2: String) = CountryShapes.Country(
        iso2 = iso2,
        nameDe = iso2,
        nameEn = iso2,
        labelLat = 0.0,
        labelLon = 0.0,
        bbox = doubleArrayOf(0.0, 0.0, 1.0, 1.0),
        rings = emptyList()
    )

    private fun build(
        iso2: List<String>,
        visited: List<String>
    ) = ContinentStats.build(iso2.map { country(it) }, visited)

    @Test
    fun build_zaehltBesuchteUndGesamtJeKontinent() {
        val progress = build(
            listOf("DE", "FR", "IT", "JP", "CN", "US"),
            listOf("DE", "FR", "JP", "US")
        )
        val europa = progress.first { it.continent == Continents.EUROPE }
        assertEquals(2, europa.visited)
        assertEquals(3, europa.total)
        val asien = progress.first { it.continent == Continents.ASIA }
        assertEquals(1, asien.visited)
        assertEquals(2, asien.total)
    }

    @Test
    fun build_bleibtInDerReihenfolgeDerKontinente() {
        val progress = build(listOf("CN", "DE", "US"), emptyList())
        assertEquals(
            listOf(
                Continents.EUROPE, Continents.ASIA, Continents.NORTH_AMERICA
            ),
            progress.map { it.continent }
        )
    }

    @Test
    fun build_liefertNurKontinenteMitLaendern() {
        // Afrika fehlt in der Liste, deshalb darf es auch keine Kachel geben -
        // sonst stünde dort 0 von 0 und die Kachelzeile verschöbe sich beim
        // Scrollen.
        val progress = build(listOf("DE", "JP"), listOf("DE"))
        assertEquals(
            listOf(Continents.EUROPE, Continents.ASIA),
            progress.map { it.continent }
        )
    }

    @Test
    fun build_zaehltLaenderOhneKontinentInKeinerKachel() {
        // Pitcairn, die Ålandinseln und das britische Territorium im
        // Indischen Ozean gehören zu keinem Kontinent. Die Falklandinseln
        // dagegen zählen zu Südamerika und hätten hier eine Kachel verdient.
        val progress = build(listOf("PN", "AX", "IO", "DE"), listOf("PN", "DE"))
        assertEquals(1, progress.size)
        assertEquals(1, progress.first().visited)
    }

    @Test
    fun build_zaehltNurLaenderDieEsInDerListeGibt() {
        // Ein zurückgebliebener Code aus den Einstellungen darf keinen Zähler
        // hochtreiben, sonst stünde in der Kachel mehr als in der Summe.
        val progress = build(listOf("DE", "FR"), listOf("DE", "XX", "AQ"))
        val europa = progress.first { it.continent == Continents.EUROPE }
        assertEquals(1, europa.visited)
        assertEquals(2, europa.total)
    }

    @Test
    fun build_ohneBesuchIstAllesNull() {
        val progress = build(listOf("DE", "FR", "JP"), emptyList())
        assertTrue(progress.all { it.visited == 0 })
        assertTrue(progress.all { it.percent == 0 })
        assertTrue(progress.all { it.fraction == 0f })
    }

    @Test
    fun build_ignoriertKleingeschriebeneUndUnschaerfeCodes() {
        val progress = build(listOf("DE", "FR"), listOf(" de ", "Fr"))
        val europa = progress.first { it.continent == Continents.EUROPE }
        assertEquals(2, europa.visited)
    }

    @Test
    fun progress_percentRundet() {
        // 1 von 3 sind 33,3 Prozent, 2 von 3 sind 66,7 - gerundet 33 und 67.
        assertEquals(33, ContinentStats.Progress(Continents.EUROPE, 1, 3).percent)
        assertEquals(67, ContinentStats.Progress(Continents.EUROPE, 2, 3).percent)
        assertEquals(50, ContinentStats.Progress(Continents.EUROPE, 1, 2).percent)
        assertEquals(100, ContinentStats.Progress(Continents.EUROPE, 55, 55).percent)
    }

    @Test
    fun progress_fractionBleibtZwischenNullUndEins() {
        assertEquals(0.5f, ContinentStats.Progress(Continents.EUROPE, 1, 2).fraction, 1e-6f)
        // Ohne Länder gibt es nichts zu zeigen, und eine Division durch null
        // erst recht nicht.
        assertEquals(0f, ContinentStats.Progress(Continents.EUROPE, 0, 0).fraction, 1e-6f)
        assertEquals(1f, ContinentStats.Progress(Continents.EUROPE, 3, 2).fraction, 1e-6f)
    }
}
