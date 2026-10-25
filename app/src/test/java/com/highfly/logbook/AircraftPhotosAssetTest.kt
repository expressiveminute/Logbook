package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die mitgelieferten Flugzeugtyp-Fotos (`assets/aircraft/photos/`):
 * Jeder Typ in `photos.json` hat ein `photos`-Array mit mindestens einem
 * Hersteller-Foto (`kind="manufacturer"`, Datei `<CODE>.webp`) und optional
 * einem Launch-Customer-Foto (`kind="launch"`, Datei `<CODE>_launch.webp`).
 * Jedes Foto zeigt auf eine vorhandene webp-Datei samt vollständiger
 * Attribution (Autor, Lizenz, Lizenz-URL, Quell-URL) unter einer erlaubten
 * Lizenz (Public Domain, CC0, CC BY, GFDL - nicht NC/ND). Außerdem darf es
 * keine verwaisten Dateien geben, und die Typen müssen genau denen aus
 * `aircraft_facts.json` entsprechen.
 *
 * Die JSON-Dateien werden bewusst mit Textmitteln gelesen: `org.json` ist in
 * den JVM-Unit-Tests nur ein Stub. `photos.json` ist kompakt (eine Zeile) und
 * verschachtelt nur eine Ebene tief (Typ -> photos-Array -> Foto-Objekt).
 */
class AircraftPhotosAssetTest {

    private val photosDir = File("src/main/assets/aircraft/photos")
    private val photosJson = File(photosDir, "photos.json")
    private val facts = File("src/main/assets/aircraft/aircraft_facts.json")

    /** Typ-Code -> Rohtext des Typ-Objekts (enthält das photos-Array). */
    private fun typeEntries(): Map<String, String> =
        Regex("\"([A-Za-z0-9]+)\":\\{\"photos\":\\[([^\\]]*)\\]\\}")
            .findAll(photosJson.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** Alle Foto-Objekte eines Typs als Liste von Rohtext. */
    private fun photoObjects(typeEntry: String): List<String> =
        Regex("\\{([^{}]*)\\}").findAll(typeEntry)
            .map { it.groupValues[1] }
            .toList()

    /** Feld eines Fotos als String (nur auf Vorhandensein/Format geprüft). */
    private fun stringField(photo: String, field: String): String? =
        Regex("\"$field\":\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(photo)?.groupValues?.get(1)

    private fun intField(photo: String, field: String): Int? =
        Regex("\"$field\":(\\d+)").find(photo)?.groupValues?.get(1)?.toIntOrNull()

    /** Erlaubte Lizenzen, siehe tools/build_aircraft_photos.py (license_allowed). */
    private fun licenseAllowed(short: String): Boolean {
        val tokens = short.uppercase()
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if ("NC" in tokens || "ND" in tokens) return false
        if ("GFDL" in tokens) return true
        if ("CC0" in tokens || "PD" in tokens) return true
        if ("PUBLIC" in tokens && "DOMAIN" in tokens) return true
        if ("NO" in tokens && "RESTRICTIONS" in tokens) return true
        return "CC" in tokens && "BY" in tokens
    }

    /** Typ-Codes aus dem types-Block von aircraft_facts.json. */
    private fun factTypes(): Set<String> {
        val typesBlock = Regex("\"types\":\\{((?:\"[A-Za-z0-9]+\":\\{[^{}]*\\},?)+)\\}")
            .find(facts.readText())?.groupValues?.get(1).orEmpty()
        return Regex("\"([A-Za-z0-9]+)\":\\{").findAll(typesBlock)
            .map { it.groupValues[1] }.toSet()
    }

    @Test
    fun jederTypHatEinHerstellerFoto() {
        val entries = typeEntries()
        assertTrue("keine Typen in photos.json", entries.size >= 50)
        for ((code, typeEntry) in entries) {
            val photos = photoObjects(typeEntry)
            assertTrue("$code: photos-Array fehlt oder leer", photos.isNotEmpty())
            val kinds = photos.mapNotNull { stringField(it, "kind") }
            assertTrue(
                "$code: kein Hersteller-Foto (kind=manufacturer)",
                "manufacturer" in kinds
            )
        }
    }

    @Test
    fun jedesFotoIstEineValideWebpDatei() {
        val entries = typeEntries()
        assertTrue("keine Fotos in photos.json", entries.size >= 50)
        for ((code, typeEntry) in entries) {
            for (photo in photoObjects(typeEntry)) {
                val kind = stringField(photo, "kind").orEmpty()
                val file = stringField(photo, "file").orEmpty()
                assertTrue("$code [$kind]: Dateiname fehlt", file.isNotEmpty())
                val expectedSuffix = if (kind == "launch") "_launch.webp" else ".webp"
                assertEquals(
                    "$code [$kind]: Datei nicht nach dem Schema benannt",
                    "$code$expectedSuffix", file
                )
                val path = File(photosDir, file)
                assertTrue("fehlende Datei: $file", path.isFile)
                val header = path.readBytes().copyOfRange(0, 12)
                assertTrue(
                    "keine webp-Datei: $file",
                    header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                        header.copyOfRange(8, 12).decodeToString() == "WEBP"
                )
                assertTrue("Datei zu klein: $file", path.length() > 5000)
            }
        }
    }

    @Test
    fun attributionIstVollstaendig() {
        for ((code, typeEntry) in typeEntries()) {
            for (photo in photoObjects(typeEntry)) {
                val kind = stringField(photo, "kind").orEmpty()
                val file = stringField(photo, "file")
                val author = stringField(photo, "author")
                val license = stringField(photo, "license")
                val licenseUrl = stringField(photo, "licenseUrl")
                val sourceUrl = stringField(photo, "sourceUrl")
                assertTrue("$code/$kind/$file: Autor fehlt", !author.isNullOrEmpty())
                assertTrue("$code/$kind/$file: Lizenz fehlt", !license.isNullOrEmpty())
                assertTrue(
                    "$code/$kind/$file: Lizenz-URL fehlt oder ist keine URL",
                    licenseUrl != null && licenseUrl.startsWith("https://")
                )
                assertTrue(
                    "$code/$kind/$file: Quell-URL ist keine Commons-Dateiseite",
                    sourceUrl != null &&
                        sourceUrl.startsWith("https://commons.wikimedia.org/wiki/File:")
                )
            }
        }
    }

    @Test
    fun lizenzIstErlaubt() {
        for ((code, typeEntry) in typeEntries()) {
            for (photo in photoObjects(typeEntry)) {
                val license = stringField(photo, "license")
                val file = stringField(photo, "file")
                assertTrue(
                    "$code/$file: nicht erlaubte Lizenz: $license",
                    license != null && licenseAllowed(license)
                )
            }
        }
    }

    @Test
    fun masseEntsprechenDenFilternDesSkripts() {
        // Die harten Filter des Skripts (mindestens 1200 x 700 px, Querformat)
        // gelten für das Original; gespeichert ist das Thumbnail (max. 1280 px
        // breit, nie hochgerechnet), daher hier die abgeleiteten Grenzen.
        for ((code, typeEntry) in typeEntries()) {
            for (photo in photoObjects(typeEntry)) {
                val file = stringField(photo, "file")
                val width = intField(photo, "width")
                val height = intField(photo, "height")
                assertTrue(
                    "$code/$file: Breite außerhalb 1200-1280",
                    width != null && width in 1200..1280
                )
                assertTrue("$code/$file: Höhe zu klein", height != null && height >= 500)
                assertTrue(
                    "$code/$file: kein Querformat oder zu panoramisch",
                    width != null && height != null && width > height && width <= height * 2.5
                )
            }
        }
    }

    @Test
    fun aufnahmedatumHatIsoFormat() {
        // Leer ist erlaubt (kein EXIF- und kein Titel-Datum); sonst ISO.
        for ((_, typeEntry) in typeEntries()) {
            for (photo in photoObjects(typeEntry)) {
                val date = stringField(photo, "date")
                if (date.isNullOrEmpty()) continue
                assertTrue(
                    "unplausibles Aufnahmedatum: $date",
                    Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)
                )
            }
        }
    }

    @Test
    fun keineVerwaisteFotoDatei() {
        val referenced = mutableSetOf<String>()
        for ((_, typeEntry) in typeEntries()) {
            for (photo in photoObjects(typeEntry)) {
                stringField(photo, "file")?.let { referenced.add(it) }
            }
        }
        val orphans = photosDir.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".webp") && it !in referenced }
        assertTrue("verwaiste Foto-Dateien: $orphans", orphans.isEmpty())
    }

    @Test
    fun eintraegeEntsprechenDenFaktenTypen() {
        val types = factTypes()
        assertTrue("keine Typen in aircraft_facts.json", types.size >= 50)
        val entries = typeEntries()
        for (code in entries.keys) {
            assertTrue("$code: nicht in aircraft_facts.json", code in types)
        }
        for (code in types) {
            assertTrue("$code: hat kein Foto", code in entries.keys)
        }
    }
}
