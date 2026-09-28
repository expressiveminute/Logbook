package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.Collator
import java.util.Locale

class CountryChecklistTest {

    private val collator: Collator = Collator.getInstance(Locale.GERMAN)

    private fun country(iso2: String, de: String, en: String) = CountryShapes.Country(
        iso2 = iso2,
        nameDe = de,
        nameEn = en,
        labelLat = 0.0,
        labelLon = 0.0,
        bbox = doubleArrayOf(0.0, 0.0, 1.0, 1.0),
        rings = emptyList()
    )

    @Test
    fun build_sortiertNachLandenamen() {
        val items = CountryChecklist.build(
            countries = listOf(
                country("IS", "Island", "Iceland"),
                country("AL", "Albanien", "Albania"),
                country("DE", "Deutschland", "Germany")
            ),
            flightIso2 = emptySet(),
            manualIso2 = emptySet(),
            german = true,
            collator = collator
        )
        assertEquals(listOf("Albanien", "Deutschland", "Island"), items.map { it.name })
    }

    @Test
    fun build_sortiertUmlauteWieImDeutschen() {
        // Ohne sortierte Umlaute käme "Österreich" vor "Äthiopien", mit dem
        // deutschen Collator steht es dahinter, denn "Ä" wird wie "A" gelesen.
        val items = CountryChecklist.build(
            countries = listOf(
                country("AT", "Österreich", "Austria"),
                country("ET", "Äthiopien", "Ethiopia")
            ),
            flightIso2 = emptySet(),
            manualIso2 = emptySet(),
            german = true,
            collator = collator
        )
        assertEquals(listOf("Äthiopien", "Österreich"), items.map { it.name })
    }

    @Test
    fun build_zeigtDeutscheOderEnglischeNamen() {
        val laender = listOf(country("DE", "Deutschland", "Germany"))
        val deutsch = CountryChecklist.build(
            laender, emptySet(), emptySet(), german = true, collator = collator
        )
        val englisch = CountryChecklist.build(
            laender, emptySet(), emptySet(), german = false, collator = collator
        )
        assertEquals("Deutschland", deutsch.single().name)
        assertEquals("Germany", englisch.single().name)
    }

    @Test
    fun build_haengtDieFlaggeAnDenLändercode() {
        val items = CountryChecklist.build(
            countries = listOf(country("DE", "Deutschland", "Germany")),
            flightIso2 = emptySet(),
            manualIso2 = emptySet(),
            german = true,
            collator = collator
        )
        assertEquals(AirportData.flagEmoji("DE"), items.single().flag)
    }

    @Test
    fun build_erlaubtKeinAbwaehlenDerAngeflogenenLaender() {
        val items = CountryChecklist.build(
            countries = listOf(
                country("US", "Vereinigte Staaten", "United States"),
                country("JP", "Japan", "Japan")
            ),
            flightIso2 = setOf("US"),
            manualIso2 = emptySet(),
            german = true,
            collator = collator
        )
        val usa = items.first { it.iso2 == "US" }
        val japan = items.first { it.iso2 == "JP" }
        assertTrue(usa.checked)
        assertTrue(usa.fromFlight)
        assertFalse(usa.toggleable)
        assertFalse(japan.checked)
        assertTrue(japan.toggleable)
    }

    @Test
    fun build_zaehltSelbstAbgehakteAlsAngehaekt() {
        val items = CountryChecklist.build(
            countries = listOf(country("FR", "Frankreich", "France")),
            flightIso2 = emptySet(),
            manualIso2 = setOf("FR"),
            german = true,
            collator = collator
        )
        val frankreich = items.single()
        assertTrue(frankreich.checked)
        // Selbst abgehakt bleibt abwählbar, im Gegensatz zu den angeflogenen.
        assertFalse(frankreich.fromFlight)
        assertTrue(frankreich.toggleable)
        assertEquals(1, CountryChecklist.checkedCount(items))
    }

    @Test
    fun build_ignoriertKleinbuchstabenUndUnsinn() {
        val items = CountryChecklist.build(
            countries = listOf(country("IT", "Italien", "Italy")),
            flightIso2 = setOf("it"),
            manualIso2 = setOf("Italien", "X"),
            german = true,
            collator = collator
        )
        val italien = items.single()
        assertTrue(italien.checked)
        assertTrue(italien.fromFlight)
    }

    @Test
    fun build_verwendetDenLändercodeWennDerNameFehlt() {
        val items = CountryChecklist.build(
            countries = listOf(country("ZZ", "", "")),
            flightIso2 = emptySet(),
            manualIso2 = emptySet(),
            german = true,
            collator = collator
        )
        assertEquals("ZZ", items.single().name)
    }

    @Test
    fun build_liestDasMitgelieferteAsset() {
        // Dasselbe wie in CountryShapesTest: Das echte Asset muss durch den
        // echten Aufbau laufen, sonst fehlen auf der Seite Länder, die es auf
        // der Karte gibt.
        val asset = File("src/main/assets/${CountryShapes.ASSET}")
        assertTrue("Asset fehlt: ${asset.path}", asset.exists())
        val laender = CountryShapes.parse(asset.readBytes())
        val items = CountryChecklist.build(
            countries = laender,
            flightIso2 = setOf("DE", "fr"),
            manualIso2 = setOf("IS"),
            german = true,
            collator = collator
        )
        assertEquals(laender.size, items.size)
        assertEquals(items.sortedWith(compareBy(collator) { it.name }), items)
        val deutschland = items.first { it.iso2 == "DE" }
        assertTrue(deutschland.fromFlight)
        assertTrue(deutschland.checked)
        val frankreich = items.first { it.iso2 == "FR" }
        assertTrue("Frankreich kommt aus den Einträgen", frankreich.fromFlight)
        val island = items.first { it.iso2 == "IS" }
        assertTrue(island.checked)
        assertFalse(island.fromFlight)
    }

    @Test
    fun build_zeigtNurDieLaenderDesGewaehltenKontinents() {
        // Weg von der Kontinent-Kachel zur Länderliste: Der Filter darf die
        // übrigen Kontinente nicht mitnehmen.
        val items = CountryChecklist.build(
            countries = listOf(
                country("DE", "Deutschland", "Germany"),
                country("FR", "Frankreich", "France"),
                country("US", "Vereinigte Staaten", "United States"),
                country("JP", "Japan", "Japan")
            ),
            flightIso2 = setOf("DE"),
            manualIso2 = emptySet(),
            german = true,
            collator = collator,
            continent = Continents.EUROPE
        )
        assertEquals(listOf("DE", "FR"), items.map { it.iso2 })
        // Der Zustand bleibt derselbe wie in der Liste aller Länder: Deutschland
        // ist angeflogen und deshalb abgehakt, Japan hätte es auch.
        assertTrue(items.first { it.iso2 == "DE" }.checked)
        assertFalse(items.first { it.iso2 == "FR" }.checked)
    }

    @Test
    fun build_ohneKontinentZeigtDieGanzeWelt() {
        val laender = listOf(
            country("DE", "Deutschland", "Germany"),
            country("US", "Vereinigte Staaten", "United States")
        )
        val ganzWelt = CountryChecklist.build(laender, emptySet(), emptySet(), true, collator)
        val ohneAngabe = CountryChecklist.build(
            laender, emptySet(), emptySet(), true, collator, continent = null
        )
        val leereAngabe = CountryChecklist.build(
            laender, emptySet(), emptySet(), true, collator, continent = "   "
        )
        assertEquals(listOf("DE", "US"), ganzWelt.map { it.iso2 })
        assertEquals(ganzWelt, ohneAngabe)
        assertEquals(ganzWelt, leereAngabe)
    }

    @Test
    fun build_unbekannterKontinentZeigtNichts() {
        // Lieber eine leere Seite als stillschweigend die ganze Welt - sonst
        // wüsste der Nutzer nicht, warum die Kachel nicht zu ihrer Liste führt.
        val items = CountryChecklist.build(
            countries = listOf(country("DE", "Deutschland", "Germany")),
            flightIso2 = emptySet(),
            manualIso2 = emptySet(),
            german = true,
            collator = collator,
            continent = Continents.UNKNOWN
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun build_haeltDieZaehlerDerKachelnEin() {
        // Die Kachel zeigt 3 von 54 in Europa, die Liste auf der Seite dazu
        // muss dieselben Zahlen ergeben.
        val asset = File("src/main/assets/${CountryShapes.ASSET}")
        val laender = CountryShapes.parse(asset.readBytes())
        val items = CountryChecklist.build(
            countries = laender,
            flightIso2 = setOf("DE", "FR", "IT"),
            manualIso2 = emptySet(),
            german = true,
            collator = collator,
            continent = Continents.EUROPE
        )
        val kachel = ContinentStats.build(laender, setOf("DE", "FR", "IT"))
            .first { it.continent == Continents.EUROPE }
        assertEquals(kachel.total, items.size)
        assertEquals(kachel.visited, CountryChecklist.checkedCount(items))
    }
}
