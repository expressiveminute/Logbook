package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der Balken über der Rubbelkarte: besuchte Länder als Anteil aller Länder der
 * Welt. Der Nenner muss derselbe sein wie über der Länderliste neben dem Stift,
 * sonst stünden zwei verschiedene Zahlen auf zwei Seiten nebeneinander.
 */
class WorldProgressTest {

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
    ) = WorldProgress.build(iso2.map { country(it) }, visited)

    @Test
    fun build_zaehltBesuchteUndGesamt() {
        val progress = build(listOf("DE", "FR", "IT", "JP"), listOf("DE", "JP"))
        assertEquals(2, progress.visited)
        assertEquals(4, progress.total)
    }

    @Test
    fun build_zaehltNurLaenderDieEsInDerListeGibt() {
        // Ein zurückgebliebener Code aus den Einstellungen darf den Balken nicht
        // voller machen, als die Länderliste es hergibt.
        val progress = build(listOf("DE", "FR"), listOf("DE", "XX", "AQ"))
        assertEquals(1, progress.visited)
        assertEquals(2, progress.total)
    }

    @Test
    fun build_ignoriertKleingeschriebeneUndUnschaerfeCodes() {
        val progress = build(listOf("DE", "FR"), listOf(" de ", "Fr"))
        assertEquals(2, progress.visited)
    }

    @Test
    fun build_ohneBesuchIstAllesNull() {
        val progress = build(listOf("DE", "FR", "JP"), emptyList())
        assertEquals(0, progress.visited)
        assertEquals(0f, progress.fraction, 1e-6f)
        assertEquals(0, progress.percent)
    }

    @Test
    fun build_zaehltLaenderOhneKontinentMit() {
        // Anders als in den Kacheln: Der Balken zeigt die ganze Welt, und ein
        // Land ohne Kontinent ist ein Land der Welt. Sonst wäre der Nenner
        // kleiner als die Zahl über der Länderliste.
        val progress = build(listOf("PN", "IO", "DE"), listOf("PN", "DE"))
        assertEquals(2, progress.visited)
        assertEquals(3, progress.total)
    }

    @Test
    fun progress_percentRundet() {
        // 1 von 3 sind 33,3 Prozent, 2 von 3 sind 66,7 - gerundet 33 und 67.
        assertEquals(33, WorldProgress.Progress(1, 3).percent)
        assertEquals(67, WorldProgress.Progress(2, 3).percent)
        assertEquals(50, WorldProgress.Progress(1, 2).percent)
        assertEquals(100, WorldProgress.Progress(240, 240).percent)
    }

    @Test
    fun progress_fractionBleibtZwischenNullUndEins() {
        assertEquals(0.5f, WorldProgress.Progress(1, 2).fraction, 1e-6f)
        // Ohne Länder gibt es nichts zu zeigen, und eine Division durch null
        // erst recht nicht.
        assertEquals(0f, WorldProgress.Progress(0, 0).fraction, 1e-6f)
        // Mehr als alle, etwa weil zwei Einträge dasselbe Land nennen: Der Balken
        // darf nicht über seinen Rand hinauslaufen.
        assertEquals(1f, WorldProgress.Progress(3, 2).fraction, 1e-6f)
    }

    @Test
    fun build_stimmtMitDerLaenderlisteUeberein() {
        // Der Nenner des Balkens ist die Länderliste, der Nenner über der Liste
        // die Gesamtzahl ihrer Zeilen. Beide müssen übereinstimmen.
        val asset = File("src/main/assets/${CountryShapes.ASSET}")
        assertTrue("Asset fehlt: ${asset.path}", asset.exists())
        val laender = CountryShapes.parse(asset.readBytes())
        val besucht = setOf("DE", "FR", "IT", "US", "JP")
        val balken = WorldProgress.build(laender, besucht)
        val liste = CountryChecklist.build(
            countries = laender,
            flightIso2 = besucht,
            manualIso2 = emptySet(),
            german = true,
            collator = java.text.Collator.getInstance(java.util.Locale.GERMAN)
        )
        assertEquals(liste.size, balken.total)
        assertEquals(CountryChecklist.checkedCount(liste), balken.visited)
    }

    @Test
    fun build_summiertSichUeberDieKacheln() {
        // Ein Kontinent ohne Länder in der Liste hat keine Kachel und fehlt
        // damit auch in der Summe - die Kacheln und der Balken dürfen sich nicht
        // widersprechen, wenn es solche Länder gibt.
        val asset = File("src/main/assets/${CountryShapes.ASSET}")
        val laender = CountryShapes.parse(asset.readBytes())
        val besucht = setOf("DE", "FR", "US", "JP", "PN", "IO")
        val balken = WorldProgress.build(laender, besucht)
        val kacheln = ContinentStats.build(laender, besucht)
        assertEquals(kacheln.sumOf { it.total }, balken.total - laender.count { country ->
            Continents.continentOf(country.iso2) == null
        })
        assertEquals(balken.visited, besucht.count { code ->
            laender.any { it.iso2 == code }
        })
    }
}
