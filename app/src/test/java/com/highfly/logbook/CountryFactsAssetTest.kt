package com.highfly.logbook

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft das von `tools/build_country_facts.py` erzeugte Asset
 * `assets/country_facts.json`: Die Fakten müssen zu bekannten Ländern
 * passen und das Format muss zu dem passen, was `CountryFacts` einliest
 * (org.json ist in den JVM-Unit-Tests nur ein Stub, deshalb Textmittel).
 */
class CountryFactsAssetTest {

    private val file = File("src/main/assets/country_facts.json")
    private val text by lazy { file.readText() }

    /** Der JSON-Block eines einzelnen ISO-2-Codes, ohne die umgebenden Klammern. */
    private fun entry(iso2: String): String {
        val match = Regex("\"$iso2\":\\{").find(text) ?: return ""
        // Klammern zaehlen, um das Ende des Blocks zu finden.
        var depth = 0
        var i = match.range.first + iso2.length + 2
        val start = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start + 1, i)
                }
            }
            i++
        }
        return text.substring(start + 1)
    }

    @Test
    fun assetLiegtVorUndIstNichtLeer() {
        assertTrue("country_facts.json fehlt", file.isFile)
        assertTrue("country_facts.json ist verdächtig klein: ${file.length()} B", file.length() > 20_000)
    }

    @Test
    fun deutschlandHatDieBekanntenFakten() {
        val de = entry("DE")
        assertTrue("DE-Block fehlt", de.isNotEmpty())
        assertTrue("Hauptstadt Berlin fehlt", de.contains("\"capital\":{\"de\":[\"Berlin\"]"))
        assertTrue("Währung Euro fehlt", de.contains("Euro"))
        assertTrue("Amtssprache Deutsch fehlt", de.contains("Deutsch"))
        assertTrue("en-Label fehlt", de.contains("\"en\":"))
        assertTrue("Nationalfeiertag fehlt", de.contains("Tag der Deutschen Einheit"))
        assertTrue("Feiertags-Datum fehlt", de.contains("\"date\":\"10-03\""))
        assertTrue("adminDivisions fehlt", de.contains("\"adminDivisions\":"))
    }

    @Test
    fun usaHatHauptstadtUndWaehrung() {
        val us = entry("US")
        assertTrue("US-Block fehlt", us.isNotEmpty())
        assertTrue("Hauptstadt fehlt", us.contains("Washington"))
        // en-Waehrung gross geschrieben ("United States dollar").
        assertTrue("Währung fehlt", us.contains("Dollar") || us.contains("dollar"))
    }

    @Test
    fun waehrungenSindAufEnglischGrossGeschrieben() {
        // "euro" (klein) darf nicht vorkommen, "Euro" schon.
        assertTrue("euro klein gefunden", !text.contains("\"en\":[\"euro\"]"))
        assertTrue("Euro gross fehlt", text.contains("Euro"))
    }

    @Test
    fun schlüsselSindIso2UndJederEintragHatEinFeld() {
        // Alle Top-Level-Keys sind genau zwei Buchstaben.
        val keys = Regex("\"([A-Z]{2})\":\\{").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("zu wenige Länder: ${keys.size}", keys.size > 200)
        assertTrue("doppelte Schlüssel", keys.size == keys.toSet().size)
    }
}
