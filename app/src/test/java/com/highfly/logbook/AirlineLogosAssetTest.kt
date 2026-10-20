package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die Logo-Zuordnung des Airline-Katalogs: Jede `file`-Angabe in
 * `assets/airlines/airlines.json` muss auf eine vorhandene webp-Datei zeigen,
 * die nach dem ICAO-Code des Eintrags benannt ist (die Dateien liegen unter
 * `assets/airlines/liveries/<ICAO>.webp`). Außerdem werden die Einträge
 * geschützt, die einmal falsch belegt waren: SN darf nicht mehr den
 * Vorgänger-Code DAT tragen, VL und 4Y müssen die aktuellen Airlines sein.
 *
 * Der Katalog wird bewusst mit Textmitteln gelesen: `org.json` ist in den
 * JVM-Unit-Tests nur ein Stub.
 */
class AirlineLogosAssetTest {

    private val airlinesDir = File("src/main/assets/airlines")
    private val catalog = File(airlinesDir, "airlines.json")
    private val liveriesDir = File(airlinesDir, "liveries")

    /** Ein Katalogeintrag als Map, über Textmitteln geparst. */
    private fun entry(code: String): Map<String, String?> {
        val pattern = Regex(
            "\\{\\n\"iata\": \"$code\",\\n" +
                "\"icao\": (?:\"([^\"]*)\"|null),\\n" +
                "\"name\": \"((?:[^\"\\\\]|\\\\.)*)\",\\n" +
                "\"file\": (?:\"([^\"]*)\"|null)\\n\\}"
        )
        val match = pattern.find(catalog.readText())
            ?: return emptyMap()
        return mapOf(
            "iata" to code,
            "icao" to match.groupValues[1].takeIf { it.isNotEmpty() },
            "name" to match.groupValues[2],
            "file" to match.groupValues[3].takeIf { it.isNotEmpty() }
        )
    }

    @Test
    fun jedeDateiangabeZeigtAufVorhandeneWebpDatei() {
        val text = catalog.readText()
        val files = Regex("\"file\": \"([^\"]+)\"").findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("keine file-Angaben im Katalog", files.size > 300)
        for (file in files) {
            val path = File(airlinesDir, file)
            assertTrue("fehlende Datei: $file", path.isFile)
            val header = path.readBytes().copyOfRange(0, 12)
            assertTrue(
                "keine webp-Datei: $file",
                header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                    header.copyOfRange(8, 12).decodeToString() == "WEBP"
            )
            assertTrue("Datei zu klein: $file", path.length() > 500)
        }
    }

    @Test
    fun dateienSindNachDemIcaoCodeBenannt() {
        val text = catalog.readText()
        val pattern = Regex(
            "\"icao\": \"([A-Z0-9]+)\",\\n" +
                "\"name\": \"((?:[^\"\\\\]|\\\\.)*)\",\\n" +
                "\"file\": \"liveries/([A-Z0-9]+)\\.webp\""
        )
        val mismatches = pattern.findAll(text)
            .filter { it.groupValues[1] != it.groupValues[3] }
            .map { "${it.groupValues[2]}: ${it.groupValues[1]} vs ${it.groupValues[3]}" }
            .toList()
        assertTrue("Datei nicht nach ICAO benannt: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun keineVerwaisteLogoDatei() {
        val referenced = Regex("\"file\": \"(liveries/[^\"]+)\"")
            .findAll(catalog.readText())
            .map { it.groupValues[1] }
            .toSet()
        val orphans = liveriesDir.listFiles().orEmpty()
            .map { "liveries/" + it.name }
            .filter { it !in referenced }
        assertTrue("verwaiste Logo-Dateien: $orphans", orphans.isEmpty())
    }

    @Test
    fun brusselsAirlinesHatDenAktuellenIcaoCode() {
        val sn = entry("SN")
        assertEquals("Brussels Airlines", sn["name"])
        assertEquals("SN darf nicht den Vorgänger-ICAO DAT tragen", "BEL", sn["icao"])
        assertEquals("liveries/BEL.webp", sn["file"])
        assertTrue(File(airlinesDir, "liveries/BEL.webp").isFile)
    }

    @Test
    fun lufthansaCityUndDiscoverHabenAktuelleEintraege() {
        val vl = entry("VL")
        assertEquals("Lufthansa City Airlines", vl["name"])
        assertEquals("LHX", vl["icao"])
        assertEquals("liveries/LHX.webp", vl["file"])

        val discover = entry("4Y")
        assertEquals("Discover Airlines", discover["name"])
        assertEquals("OCN", discover["icao"])
        assertEquals("liveries/OCN.webp", discover["file"])
    }

    @Test
    fun airBerlinBehaltSeinenIcaoCode() {
        val ab = entry("AB")
        assertEquals("Air Berlin", ab["name"])
        assertEquals("BER", ab["icao"])
        assertEquals("liveries/BER.webp", ab["file"])
        assertTrue(File(airlinesDir, "liveries/BER.webp").isFile)
    }
}
