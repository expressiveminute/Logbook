package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die Typ-Auflösung von [AircraftFacts.Catalog] an einem handgebauten
 * Datensatz: Familien, Varianten, Herstellerwörter und Alias-Codes müssen
 * auf den kanonischen Typ führen - auch bei gemischter Schreibung im Asset
 * ("Saab340") - und unbekannte Typen liefern null. Außerdem wird das von
 * `tools/build_aircraft_facts.py` erzeugte Asset
 * `assets/aircraft/aircraft_facts.json` geprüft (Textmittel statt
 * `org.json`, das in den JVM-Unit-Tests nur ein Stub ist).
 */
class AircraftFactsTest {

    private fun facts(name: String) = AircraftFacts.Facts(
        name = name,
        manufacturer = null,
        productionStart = null,
        firstDelivery = null,
        launchCustomer = null,
        wiki = null
    )

    private val catalog = AircraftFacts.Catalog(
        types = listOf(
            "A320" to "Airbus A320",
            "A320neo" to "Airbus A320neo",
            "A321" to "Airbus A321",
            "A321neo" to "Airbus A321neo",
            "A330" to "Airbus A330",
            "A330neo" to "Airbus A330neo",
            "A350" to "Airbus A350",
            "B707" to "Boeing 707",
            "B737" to "Boeing 737",
            "B737MAX" to "Boeing 737 MAX",
            "CRJ" to "Bombardier CRJ",
            "DH8D" to "De Havilland Canada Dash 8",
            "MD80" to "McDonnell Douglas MD-80",
            "E190" to "Embraer E-190",
            "E190E2" to "Embraer E190-E2",
            "E195" to "Embraer E-195",
            "E195E2" to "Embraer E195-E2",
            "ATR72" to "ATR 72",
            "Concorde" to "Concorde",
            "Comet" to "de Havilland Comet",
            "F70" to "Fokker 70",
            "Saab340" to "Saab 340",
            "BAe146" to "British Aerospace 146",
            "L1011" to "Lockheed L-1011 TriStar",
            "BAC111" to "BAC One-Eleven",
            "Tu154" to "Tupolev Tu-154",
            "Il86" to "Ilyushin Il-86",
            "SSJ100" to "Sukhoi Superjet 100"
        ).associate { (code, name) -> code to facts(name) },
        aliases = mapOf(
            "320" to "A320",
            "320NEO" to "A320neo",
            "A320NEO" to "A320neo",
            "20N" to "A320neo",
            "A319NEO" to "A320neo",
            "19N" to "A320neo",
            "321" to "A321",
            "321NEO" to "A321neo",
            "A321NEO" to "A321neo",
            "21N" to "A321neo",
            "330" to "A330",
            "33X" to "A330neo",
            "A330NEO" to "A330neo",
            "A330800" to "A330neo",
            "A330900" to "A330neo",
            "338" to "A330neo",
            "339" to "A330neo",
            "350" to "A350",
            "707" to "B707",
            "737" to "B737",
            "738" to "B737",
            "B737MAX" to "B737MAX",
            "737MAX" to "B737MAX",
            "7MAX" to "B737MAX",
            "7M7" to "B737MAX",
            "7M8" to "B737MAX",
            "7M9" to "B737MAX",
            "37M" to "B737MAX",
            "38M" to "B737MAX",
            "39M" to "B737MAX",
            "MD82" to "MD80",
            "ERJ190" to "E190",
            "E190E2" to "E190E2",
            "E195E2" to "E195E2",
            "AT72" to "ATR72",
            "ATR" to "ATR72",
            "COMET" to "Comet",
            "DH106" to "Comet",
            "FOKKER70" to "F70",
            "FOKKERF70" to "F70",
            "SAAB340" to "Saab340",
            "SF34" to "Saab340",
            "SF340" to "Saab340",
            "SAABSF340" to "Saab340",
            "BAE146" to "BAe146",
            "146" to "BAe146",
            "AVRORJ" to "BAe146",
            "TRISTAR" to "L1011",
            "ONEELEVEN" to "BAC111",
            "BACONEELEVEN" to "BAC111",
            "TU154" to "Tu154",
            "IL86" to "Il86",
            "SUPERJET" to "SSJ100",
            "DASH8" to "DH8D",
            "DHC8" to "DH8D",
            "DH8" to "DH8D",
            "Q400" to "DH8D"
        )
    )

    @Test
    fun resolve_erkenntFamilienUndVarianten() {
        assertEquals("A320", catalog.resolve("A320"))
        assertEquals("A320", catalog.resolve("A320-200"))
        assertEquals("A350", catalog.resolve("A350-900"))
        assertEquals("B737", catalog.resolve("B737-800"))
        assertEquals("CRJ", catalog.resolve("CRJ900"))
        assertEquals("ATR72", catalog.resolve("ATR72-600"))
        assertEquals("Concorde", catalog.resolve("Concorde"))
        assertEquals("F70", catalog.resolve("F70"))
        assertEquals("Saab340", catalog.resolve("Saab340"))
    }

    @Test
    fun resolve_unterscheidetCeoUndNeo() {
        // A320neo ist ein eigener Typ, nicht A320.
        assertEquals("A320neo", catalog.resolve("A320neo"))
        assertEquals("A320neo", catalog.resolve("A320 NEO"))
        assertEquals("A320neo", catalog.resolve("320neo"))
        assertEquals("A320neo", catalog.resolve("A319neo"))
        // A320 ceo bleibt A320.
        assertEquals("A320", catalog.resolve("A320"))
        assertEquals("A320", catalog.resolve("A320-200"))
        // A321neo ist ein eigener Typ, nicht A321.
        assertEquals("A321neo", catalog.resolve("A321neo"))
        assertEquals("A321neo", catalog.resolve("A321 NEO"))
        assertEquals("A321neo", catalog.resolve("321neo"))
        // A321LR ist eine ceo-Variante, kein neo.
        assertEquals("A321", catalog.resolve("A321LR"))
        // A321 ceo bleibt A321.
        assertEquals("A321", catalog.resolve("A321"))
        assertEquals("A321", catalog.resolve("A321-200"))
        // A330neo ist ein eigener Typ.
        assertEquals("A330neo", catalog.resolve("A330neo"))
        assertEquals("A330neo", catalog.resolve("A330-900"))
        assertEquals("A330neo", catalog.resolve("A330-800"))
        assertEquals("A330", catalog.resolve("A330-300"))
        // 737 MAX ist ein eigener Typ, nicht B737 NG.
        assertEquals("B737MAX", catalog.resolve("737 MAX"))
        assertEquals("B737MAX", catalog.resolve("737 MAX 8"))
        // Die Boeing-Kurzschreibweise "737-8" (MAX 8) hat bewusst keinen
        // Alias: normalisiert "7378" ist ein Präfix von "737800" (737-800
        // NG) und würde die NG-Varianten falsch als MAX erkennen.
        assertEquals("B737", catalog.resolve("737-800"))
        assertEquals("B737", catalog.resolve("737 NG"))
        // Embraer E2 ist eigene Typen.
        assertEquals("E190E2", catalog.resolve("E190-E2"))
        assertEquals("E190E2", catalog.resolve("E190 E2"))
        assertEquals("E195E2", catalog.resolve("E195-E2"))
        assertEquals("E195E2", catalog.resolve("E195 E2"))
        assertEquals("E190", catalog.resolve("E190"))
        assertEquals("E195", catalog.resolve("E195"))
    }

    @Test
    fun resolve_erkenntNeoAliasCodes() {
        // ICAO-Codes der neo-Varianten.
        assertEquals("A320neo", catalog.resolve("20N"))
        assertEquals("A320neo", catalog.resolve("19N"))
        assertEquals("A321neo", catalog.resolve("21N"))
        assertEquals("B737MAX", catalog.resolve("7M8"))
        assertEquals("B737MAX", catalog.resolve("7M9"))
        assertEquals("B737MAX", catalog.resolve("37M"))
        // ICAO-Code mit Herstellerbuchstabe: Ziffernteil "38M" -> B737MAX.
        assertEquals("B737MAX", catalog.resolve("B38M"))
        assertEquals("B737MAX", catalog.resolve("B39M"))
        assertEquals("A330neo", catalog.resolve("338"))
        assertEquals("A330neo", catalog.resolve("339"))
        assertEquals("A330neo", catalog.resolve("33X"))
        // Hersteller davor: "Airbus A320neo" -> A320neo.
        assertEquals("A320neo", catalog.resolve("Airbus A320neo"))
        assertEquals("B737MAX", catalog.resolve("Boeing 737 MAX 8"))
    }

    @Test
    fun resolve_erkenntHerstellerWoerter() {
        assertEquals("A320", catalog.resolve("Airbus A320"))
        assertEquals("A350", catalog.resolve("Airbus A350-900"))
        assertEquals("B737", catalog.resolve("Boeing 737-800"))
        assertEquals("B707", catalog.resolve("Boeing 707"))
        assertEquals("CRJ", catalog.resolve("Bombardier CRJ900"))
        assertEquals("E190", catalog.resolve("Embraer E190"))
        assertEquals("MD80", catalog.resolve("McDonnell Douglas MD-82"))
        assertEquals("L1011", catalog.resolve("Lockheed L-1011"))
        assertEquals("L1011", catalog.resolve("Lockheed TriStar"))
        assertEquals("Tu154", catalog.resolve("Tupolev Tu-154"))
        assertEquals("Il86", catalog.resolve("Ilyushin Il-86"))
        assertEquals("SSJ100", catalog.resolve("Sukhoi Superjet 100"))
        assertEquals("BAe146", catalog.resolve("British Aerospace 146"))
        assertEquals("BAe146", catalog.resolve("British Aerospace BAe 146"))
        assertEquals("DH8D", catalog.resolve("de Havilland Canada DHC-8-400"))
        assertEquals("DH8D", catalog.resolve("de Havilland Canada Dash 8"))
        assertEquals("Comet", catalog.resolve("de Havilland Comet"))
        assertEquals("Comet", catalog.resolve("DH.106 Comet"))
        assertEquals("BAC111", catalog.resolve("BAC One-Eleven"))
        assertEquals("BAC111", catalog.resolve("BAC 1-11"))
    }

    @Test
    fun resolve_erkenntAliase() {
        assertEquals("A320", catalog.resolve("320"))
        assertEquals("B737", catalog.resolve("737"))
        assertEquals("B737", catalog.resolve("738"))
        assertEquals("B707", catalog.resolve("707"))
        assertEquals("DH8D", catalog.resolve("Q400"))
        assertEquals("DH8D", catalog.resolve("DH8"))
        assertEquals("DH8D", catalog.resolve("Dash 8 Q400"))
        // "DH8C" beginnt mit dem Alias "DH8".
        assertEquals("DH8D", catalog.resolve("DH8C"))
        assertEquals("MD80", catalog.resolve("MD-82"))
        assertEquals("E190", catalog.resolve("ERJ-190"))
        assertEquals("ATR72", catalog.resolve("ATR 72-500"))
        assertEquals("F70", catalog.resolve("Fokker 70"))
        assertEquals("F70", catalog.resolve("Fokker F70"))
        assertEquals("Saab340", catalog.resolve("Saab 340"))
        assertEquals("Saab340", catalog.resolve("Saab SF 340"))
        assertEquals("BAe146", catalog.resolve("BAe 146"))
        assertEquals("BAe146", catalog.resolve("Avro RJ100"))
        assertEquals("SSJ100", catalog.resolve("Superjet 100"))
        assertEquals("Comet", catalog.resolve("DH.106"))
        assertEquals("BAC111", catalog.resolve("One-Eleven"))
    }

    @Test
    fun resolve_findetAliasAlsPraefixUndAufDemZiffernteil() {
        // "737 MAX" beginnt mit dem Alias "737MAX" -> B737MAX.
        assertEquals("B737MAX", catalog.resolve("737 MAX"))
        // "B738" trägt den Alias erst hinter dem Herstellerbuchstaben.
        assertEquals("B737", catalog.resolve("B738"))
        // "Tu-154M" beginnt mit dem Code "Tu154".
        assertEquals("Tu154", catalog.resolve("Tu-154M"))
    }

    @Test
    fun resolve_gibtDieOriginalschreibweiseZurueck() {
        // "Saab340" ist im Asset gemischt geschrieben; Code und Alias müssen
        // dieselbe Schreibweise liefern wie der Schlüssel im Asset.
        assertEquals("Saab340", catalog.resolve("Saab 340"))
        assertEquals("Saab340", catalog.resolve("SF34"))
    }

    @Test
    fun resolve_liefertNullBeiUnbekanntemTyp() {
        assertNull(catalog.resolve(null))
        assertNull(catalog.resolve(""))
        assertNull(catalog.resolve("   "))
        assertNull(catalog.resolve("Zeppelin"))
        assertNull(catalog.resolve("A999"))
        assertNull(catalog.resolve("AIRBUS"))
    }

    @Test
    fun factsFor_liefertStammdatenUndNullBeiUnbekanntemTyp() {
        assertEquals("Airbus A320", catalog.factsFor("320")?.name)
        assertEquals("Boeing 737", catalog.factsFor("Boeing 737-800")?.name)
        assertNull(catalog.factsFor("Zeppelin"))
        assertNull(catalog.factsFor(null))
    }

    // --- Asset-Prüfung ---

    private val asset = File("src/main/assets/aircraft/aircraft_facts.json")

    /** Der JSON-Block eines einzelnen Typ-Codes, ohne die umgebenden Klammern. */
    private fun entry(code: String): String {
        val match = Regex("\"$code\":\\{([^{}]*)\\}").find(asset.readText())
        return match?.groupValues?.get(1).orEmpty()
    }

    /** Alle Typ-Codes im types-Block (die Blöcke enthalten keine Nested-Objekte). */
    private fun typeCodes(): Set<String> {
        val typesBlock = Regex("\"types\":\\{((?:\"[A-Za-z0-9]+\":\\{[^{}]*\\},?)+)\\}")
            .find(asset.readText())?.groupValues?.get(1).orEmpty()
        return Regex("\"([A-Za-z0-9]+)\":\\{").findAll(typesBlock)
            .map { it.groupValues[1] }.toSet()
    }

    @Test
    fun assetLiegtVorUndHatGenugTypen() {
        assertTrue("aircraft_facts.json fehlt", asset.isFile)
        assertTrue(
            "aircraft_facts.json ist verdächtig klein: ${asset.length()} B",
            asset.length() > 10_000
        )
        assertTrue("zu wenige Typen: ${typeCodes().size}", typeCodes().size >= 50)
    }

    @Test
    fun assetHatNeoUndMaxVariantenAlsEigeneTypen() {
        val codes = typeCodes()
        assertTrue("A320neo fehlt als eigener Typ", "A320neo" in codes)
        assertTrue("A321neo fehlt als eigener Typ", "A321neo" in codes)
        assertTrue("A330neo fehlt als eigener Typ", "A330neo" in codes)
        assertTrue("B737MAX fehlt als eigener Typ", "B737MAX" in codes)
        assertTrue("E190E2 fehlt als eigener Typ", "E190E2" in codes)
        assertTrue("E195E2 fehlt als eigener Typ", "E195E2" in codes)
    }

    @Test
    fun a320neoHatLufthansaAlsErstkunden() {
        val a320neo = entry("A320neo")
        assertTrue("A320neo-Block fehlt", a320neo.isNotEmpty())
        assertTrue("Anzeigename fehlt: $a320neo", a320neo.contains("\"name\":\"Airbus A320neo\""))
        assertTrue("Erstkunde fehlt: $a320neo", a320neo.contains("\"launchCustomer\":\"Lufthansa\""))
    }

    @Test
    fun b737maxHatMalindoAirAlsErstkunden() {
        val b737max = entry("B737MAX")
        assertTrue("B737MAX-Block fehlt", b737max.isNotEmpty())
        assertTrue("Anzeigename fehlt: $b737max", b737max.contains("\"name\":\"Boeing 737 MAX\""))
        assertTrue("Erstkunde fehlt: $b737max", b737max.contains("\"launchCustomer\":\"Malindo Air\""))
    }

    @Test
    fun a320HatDieBekanntenStammdaten() {
        val a320 = entry("A320")
        assertTrue("A320-Block fehlt", a320.isNotEmpty())
        assertTrue("Anzeigename fehlt: $a320", a320.contains("\"name\":\"Airbus A320\""))
        assertTrue("Hersteller fehlt: $a320", a320.contains("\"manufacturer\":\"Airbus\""))
        assertTrue("Baubeginn fehlt: $a320", a320.contains("\"productionStart\":1986"))
        assertTrue(
            "Erstauslieferung fehlt: $a320",
            a320.contains("\"firstDelivery\":\"1988-04-18\"")
        )
        assertTrue("Erstkunde fehlt: $a320", a320.contains("\"launchCustomer\":\"Air France\""))
        assertTrue(
            "Wiki-Link fehlt: $a320",
            a320.contains("\"wiki\":\"https://en.wikipedia.org/wiki/Airbus_A320_family\"")
        )
    }

    @Test
    fun b737HatLufthansaAlsErstkunden() {
        val b737 = entry("B737")
        assertTrue("B737-Block fehlt", b737.isNotEmpty())
        assertTrue("Hersteller fehlt: $b737", b737.contains("\"manufacturer\":\"Boeing\""))
        assertTrue("Baubeginn fehlt: $b737", b737.contains("\"productionStart\":1966"))
        assertTrue(
            "Erstauslieferung fehlt: $b737",
            b737.contains("\"firstDelivery\":\"1968-02-10\"")
        )
        assertTrue("Erstkunde fehlt: $b737", b737.contains("\"launchCustomer\":\"Lufthansa\""))
    }

    @Test
    fun a380HatSingaporeAirlinesAlsErstkunden() {
        val a380 = entry("A380")
        assertTrue("A380-Block fehlt", a380.isNotEmpty())
        assertTrue("Baubeginn fehlt: $a380", a380.contains("\"productionStart\":2003"))
        assertTrue(
            "Erstauslieferung fehlt: $a380",
            a380.contains("\"firstDelivery\":\"2007-10-25\"")
        )
        assertTrue(
            "Erstkunde fehlt: $a380",
            a380.contains("\"launchCustomer\":\"Singapore Airlines\"")
        )
    }

    @Test
    fun concordeHatKeinenErstkunden() {
        val concorde = entry("Concorde")
        assertTrue("Concorde-Block fehlt", concorde.isNotEmpty())
        assertTrue(
            "Hersteller fehlt: $concorde",
            concorde.contains("\"manufacturer\":\"British Aircraft Corporation / Sud Aviation\"")
        )
        assertTrue(
            "Erstauslieferung fehlt: $concorde",
            concorde.contains("\"firstDelivery\":\"1976-01-21\"")
        )
        assertTrue(
            "Concorde darf keinen Erstkunden haben: $concorde",
            !concorde.contains("launchCustomer")
        )
    }

    @Test
    fun tu154HatHerstellerOverride() {
        // Die Infobox nennt die Fabrik Aviakor; das Skript überschreibt den
        // Hersteller auf das Konstruktionsbüro Tupolev.
        val tu154 = entry("Tu154")
        assertTrue("Tu154-Block fehlt", tu154.isNotEmpty())
        assertTrue("Hersteller-Override fehlt: $tu154", tu154.contains("\"manufacturer\":\"Tupolev\""))
        assertTrue("Erstkunde fehlt: $tu154", tu154.contains("\"launchCustomer\":\"Aeroflot\""))
    }

    @Test
    fun erstauslieferungenHabenDasErlaubteFormat() {
        // "YYYY-MM-DD", "YYYY-MM" (Monat ohne Tag) oder "YYYY".
        val dates = Regex("\"firstDelivery\":\"([^\"]+)\"")
            .findAll(asset.readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("keine Erstauslieferungen im Asset", dates.isNotEmpty())
        val pattern = Regex("(?:19|20)\\d{2}(?:-(?:0[1-9]|1[0-2])(?:-(?:0[1-9]|[12]\\d|3[01]))?)?")
        for (date in dates) {
            assertTrue("unplausibles Datum: $date", pattern.matches(date))
        }
    }

    @Test
    fun wikiLinksZeigenAufDieEnglischeWikipedia() {
        val links = Regex("\"wiki\":\"([^\"]+)\"")
            .findAll(asset.readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("keine Wiki-Links im Asset", links.isNotEmpty())
        for (link in links) {
            assertTrue(
                "kein englischer Wikipedia-Link: $link",
                link.startsWith("https://en.wikipedia.org/wiki/")
            )
        }
    }

    @Test
    fun aliaseZeigenAufVorhandeneTypen() {
        val aliasesBlock = asset.readText().substringAfter("\"aliases\":{", "")
        assertTrue("aliases-Block fehlt", aliasesBlock.isNotEmpty())
        val aliases = Regex("\"([A-Z0-9]+)\":\"([A-Za-z0-9]+)\"")
            .findAll(aliasesBlock)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("zu wenige Aliase: ${aliases.size}", aliases.size >= 150)
        val types = typeCodes()
        for ((alias, target) in aliases) {
            assertTrue("Alias $alias zeigt auf unbekannten Typ $target", target in types)
        }
        // Varianten-Aliase zeigen auf die Varianten-Typen, nicht auf die Basis.
        assertEquals("A320neo", aliases["20N"])
        assertEquals("A320neo", aliases["19N"])
        assertEquals("A321neo", aliases["21N"])
        assertEquals("B737MAX", aliases["7MAX"])
        assertEquals("B737MAX", aliases["7M8"])
        assertEquals("B737MAX", aliases["7M9"])
        assertEquals("A330neo", aliases["338"])
        assertEquals("A330neo", aliases["339"])
        assertEquals("E190E2", aliases["E190E2"])
        assertEquals("E195E2", aliases["E195E2"])
    }

    @Test
    fun aliaseSindNormalisiert() {
        // Schlüssel: Grossbuchstaben, nur Buchstaben und Ziffern - so legt
        // es AircraftFacts.resolve nach der Normalisierung nach.
        val aliasesBlock = asset.readText().substringAfter("\"aliases\":{", "")
        val keys = Regex("\"([A-Z0-9]+)\":\"")
            .findAll(aliasesBlock)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("keine Aliase", keys.isNotEmpty())
        for (key in keys) {
            assertTrue("Alias nicht normalisiert: $key", Regex("[A-Z0-9]+").matches(key))
        }
    }
}
