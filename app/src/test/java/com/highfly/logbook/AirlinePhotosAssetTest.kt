package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die mitgelieferten Airline-Fotos (`assets/airlines/photos/`): Jeder
 * Eintrag in `photos.json` hat ein `photos`-Array, dessen jedes Foto auf eine
 * vorhandene webp-Datei zeigt (nach dem `file`-Feld benannt), samt
 * vollständiger Attribution (Autor, Lizenz, Lizenz-URL, Quell-URL) unter
 * einer erlaubten Lizenz (Public Domain, CC0, CC BY, GFDL - nicht NC/ND).
 * Außerdem darf es keine verwaisten Dateien geben, jeder Eintrag muss zu
 * einem Katalogeintrag passen (`airlines.json`), dessen ICAO-Code mit dem
 * gespeicherten übereinstimmt, die Fotos einer Galerie müssen verschieden
 * sein, und Airlines der Lufthansa Group müssen drei Fotos haben.
 *
 * Die JSON-Dateien werden bewusst mit Textmitteln gelesen: `org.json` ist in
 * den JVM-Unit-Tests nur ein Stub. `photos.json` ist kompakt (eine Zeile, keine
 * Einrückung); die Foto-Objekte im `photos`-Array enthalten keine
 * verschachtelten Objekte.
 */
class AirlinePhotosAssetTest {

    private val photosDir = File("src/main/assets/airlines/photos")
    private val photosJson = File(photosDir, "photos.json")
    private val catalog = File("src/main/assets/airlines/airlines.json")
    private val facts = File("src/main/assets/airlines/airline_facts.json")

    /** IATA-Code -> (ICAO, Rohtext des photos-Arrays). */
    private fun entries(): Map<String, Pair<String, String>> {
        val text = photosJson.readText()
        // Schlüssel sortiert: icao, dann optional "manual":true, dann photos.
        return Regex(
            "\"([A-Z0-9]{2})\":\\{\"icao\":\"([A-Z0-9]{3})\"(?:,\"manual\":true)?,\"photos\":\\[(.*?)\\]"
        )
            .findAll(text)
            .associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }
    }

    /** Foto-Objekte (Rohtext) aus dem photos-Array einer Airline. */
    private fun photosOf(entry: String): List<String> =
        Regex("\\{([^{}]*)\\}").findAll(entry).map { it.groupValues[1] }.toList()

    /** Feld eines Fotos als String (mit Escapes, nur auf Vorhandensein geprüft). */
    private fun stringField(photo: String, field: String): String? =
        Regex("\"$field\":\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(photo)
            ?.groupValues?.get(1)

    private fun intField(photo: String, field: String): Int? =
        Regex("\"$field\":(\\d+)").find(photo)?.groupValues?.get(1)?.toIntOrNull()

    /** Erlaubte Lizenzen, siehe tools/build_airline_photos.py (license_allowed). */
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

    /** IATA-Codes der Airlines mit dieser Airlinegruppe in airline_facts.json. */
    private fun groupCodes(group: String): Set<String> =
        Regex("\"([A-Z0-9]{2})\":\\{[^{}]*\"group\":\"$group\"")
            .findAll(facts.readText())
            .map { it.groupValues[1] }
            .toSet()

    /** IATA-Codes der insolventen Airlines in airline_facts.json. */
    private fun dissolvedCodes(): Set<String> =
        Regex("\"([A-Z0-9]{2})\":\\{[^{}]*\"dissolved\":\\d+")
            .findAll(facts.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun jedesFotoIstEineValideWebpDatei() {
        val entries = entries()
        assertTrue("keine Fotos in photos.json", entries.size >= 200)
        for ((iata, entry) in entries) {
            val photos = photosOf(entry.second)
            assertTrue("$iata: photos-Array leer", photos.isNotEmpty())
            for (photo in photos) {
                val file = stringField(photo, "file").orEmpty()
                assertTrue("$iata: Dateiname fehlt", file.isNotEmpty())
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
        for ((iata, entry) in entries()) {
            for (photo in photosOf(entry.second)) {
                val file = stringField(photo, "file")
                val author = stringField(photo, "author")
                val license = stringField(photo, "license")
                val licenseUrl = stringField(photo, "licenseUrl")
                val sourceUrl = stringField(photo, "sourceUrl")
                assertTrue("$iata/$file: Autor fehlt", !author.isNullOrEmpty())
                assertTrue("$iata/$file: Lizenz fehlt", !license.isNullOrEmpty())
                assertTrue(
                    "$iata/$file: Lizenz-URL fehlt oder ist keine URL",
                    licenseUrl != null && licenseUrl.startsWith("https://")
                )
                assertTrue(
                    "$iata/$file: Quell-URL ist keine Commons-Dateiseite",
                    sourceUrl != null &&
                        sourceUrl.startsWith("https://commons.wikimedia.org/wiki/File:")
                )
            }
        }
    }

    @Test
    fun lizenzIstErlaubt() {
        for ((iata, entry) in entries()) {
            for (photo in photosOf(entry.second)) {
                val license = stringField(photo, "license")
                assertTrue(
                    "$iata/${stringField(photo, "file")}: nicht erlaubte Lizenz: $license",
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
        for ((iata, entry) in entries()) {
            for (photo in photosOf(entry.second)) {
                val file = stringField(photo, "file")
                val width = intField(photo, "width")
                val height = intField(photo, "height")
                assertTrue(
                    "$iata/$file: Breite außerhalb 1200-1280",
                    width != null && width in 1200..1280
                )
                assertTrue("$iata/$file: Höhe zu klein", height != null && height >= 500)
                assertTrue(
                    "$iata/$file: kein Querformat oder zu panoramisch",
                    width != null && height != null && width > height && width <= height * 2.5
                )
            }
        }
    }

    @Test
    fun keineVerwaisteFotoDatei() {
        val referenced = entries().values
            .flatMap { photosOf(it.second) }
            .mapNotNull { stringField(it, "file") }
            .toSet()
        val orphans = photosDir.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".webp") && it !in referenced }
        assertTrue("verwaiste Foto-Dateien: $orphans", orphans.isEmpty())
    }

    @Test
    fun eintraegePassenZumKatalog() {
        // IATA-Code -> ICAO-Code aus dem Katalog (eine Zeile pro Feld).
        val icaoByIata = Regex("\"iata\": \"([A-Z0-9]{2})\",\\n\"icao\": (?:\"([^\"]*)\"|null)")
            .findAll(catalog.readText())
            .associate { it.groupValues[1] to it.groupValues[2].takeIf { s -> s.isNotEmpty() } }
        assertTrue("Katalog ohne Einträge", icaoByIata.size > 900)
        for ((iata, entry) in entries()) {
            val catalogIcao = icaoByIata[iata]
            assertTrue("$iata: nicht im Katalog", catalogIcao != null || iata in icaoByIata)
            if (catalogIcao != null) {
                assertEquals(
                    "$iata: ICAO stimmt nicht mit dem Katalog überein",
                    catalogIcao,
                    entry.first
                )
            }
        }
    }

    @Test
    fun galerieFotosSindVerschieden() {
        for ((iata, entry) in entries()) {
            val photos = photosOf(entry.second)
            if (photos.size < 2) continue
            val files = photos.mapNotNull { stringField(it, "file") }
            val sources = photos.mapNotNull { stringField(it, "sourceUrl") }
            assertEquals(
                "$iata: gleiche Datei mehrfach in der Galerie",
                files.size,
                files.toSet().size
            )
            assertEquals(
                "$iata: gleiche Quelle mehrfach in der Galerie",
                sources.size,
                sources.toSet().size
            )
        }
    }

    @Test
    fun lufthansaGruppeHatDreiFotos() {
        // Die aktiven Airlines der Gruppe (ohne Insolvenz) sind in den Top 250.
        val activeGroup = groupCodes("Lufthansa Group") - dissolvedCodes()
        assertTrue("keine aktiven Lufthansa-Group-Airlines", activeGroup.size >= 8)
        val withPhotos = activeGroup.filter { it in entries() }
        assertEquals(
            "aktive Lufthansa-Group-Airlines ohne Foto: ${activeGroup - withPhotos.toSet()}",
            activeGroup,
            withPhotos.toSet()
        )
        for (iata in withPhotos) {
            val count = photosOf(entries()[iata]!!.second).size
            assertEquals("$iata: Galerie hat nicht drei Fotos", 3, count)
        }
    }
}
