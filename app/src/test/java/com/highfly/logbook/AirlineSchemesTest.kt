package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die Airline-Farbschemas: Die Schlüsselliste muss zu den klassischen
 * Akzentfarben passen, und die von `tools/build_airline_schemes.py` erzeugten
 * Ressourcen (`res/values/airline_schemes.xml` bzw. `values-night/…`) müssen
 * zu jedem Schema in [AirlineSchemes] die Markenfarben (so wie vom Nutzer
 * vorgegeben), die Kachelfarben und alle 12 Material-3-Rollen samt
 * Theme-Overlay enthalten.
 */
class AirlineSchemesTest {

    /** key -> (Primär, Sekundär, Akzent) wie vom Nutzer vorgegeben. */
    private val brandHex = mapOf(
        "airline_lh" to Triple("#05164D", "#FFFFFF", "#FFAD00"),
        "airline_lx" to Triple("#CC0000", "#FFFFFF", "#B9969D"),
        "airline_wk" to Triple("#001973", "#D70032", "#FFC82D"),
        "airline_os" to Triple("#D81E05", "#FFFFFF", "#000000"),
        "airline_ew" to Triple("#871C54", "#FFFFFF", "#00A6CF"),
        "airline_vl" to Triple("#05164D", "#FFFFFF", "#6E8BB7"),
        "airline_4y" to Triple("#1E73AF", "#FFFFFF", "#FFCD00"),
        "airline_en" to Triple("#0093A8", "#FFFFFF", "#002E54"),
        "airline_az" to Triple("#01683C", "#FFFFFF", "#BF1D0C"),
        "airline_sn" to Triple("#051446", "#D6E2F5", "#E5002B"),
        "airline_tp" to Triple("#BFD730", "#FFFFFF", "#EE1C24")
    )

    private val m3Roles = listOf(
        "primary", "on_primary", "primary_container", "on_primary_container",
        "secondary", "on_secondary", "secondary_container", "on_secondary_container",
        "tertiary", "on_tertiary", "tertiary_container", "on_tertiary_container"
    )

    private val valuesText by lazy {
        File("src/main/res/values/airline_schemes.xml").readText()
    }
    private val nightText by lazy {
        File("src/main/res/values-night/airline_schemes.xml").readText()
    }

    private fun colorLine(text: String, name: String): String? =
        Regex("<color name=\"$name\">(#[0-9A-Fa-f]{8})</color>").find(text)?.groupValues?.get(1)

    @Test
    fun schemes_habenEindeutigeKeysUndKeineKollisionMitAkzenten() {
        assertEquals(brandHex.size, AirlineSchemes.ALL.size)
        val keys = AirlineSchemes.KEYS
        assertEquals(keys.size, keys.toSet().size)
        AirlineSchemes.KEYS.forEach { key ->
            assertTrue("Airline-Key kollidiert mit Akzent: $key", key !in Settings.ACCENT_OPTIONS)
        }
        AirlineSchemes.ALL.forEach { scheme ->
            assertEquals(scheme, AirlineSchemes.byKey(scheme.key))
        }
        assertNull(AirlineSchemes.byKey("airline_xx"))
        assertNull(AirlineSchemes.byKey(Settings.ACCENT_BROWN))
    }

    @Test
    fun schemes_habenEindeutigeIataCodes() {
        val codes = AirlineSchemes.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        codes.forEach { code ->
            assertTrue("kein IATA-Code: $code", Regex("^[A-Z0-9]{2}$").matches(code))
        }
    }

    @Test
    fun alleSchemasSindGueltigeSchemeOptions() {
        AirlineSchemes.KEYS.forEach { key ->
            assertTrue("$key fehlt in SCHEME_OPTIONS", key in Settings.SCHEME_OPTIONS)
        }
        Settings.ACCENT_OPTIONS.forEach { key ->
            assertTrue("$key fehlt in SCHEME_OPTIONS", key in Settings.SCHEME_OPTIONS)
        }
    }

    @Test
    fun ressourcen_enthaltenMarkenfarbenWieVorgegeben() {
        for ((key, brand) in brandHex) {
            val (primary, secondary, accent) = brand
            assertEquals(
                "Primär $key",
                "#FF${primary.removePrefix("#")}",
                colorLine(valuesText, "${key}_brand_primary")
            )
            assertEquals(
                "Sekundär $key",
                "#FF${secondary.removePrefix("#")}",
                colorLine(valuesText, "${key}_brand_secondary")
            )
            assertEquals(
                "Akzent $key",
                "#FF${accent.removePrefix("#")}",
                colorLine(valuesText, "${key}_brand_accent")
            )
        }
    }

    @Test
    fun ressourcen_enthaltenKachelfarbenUndThemeRollen() {
        for (scheme in AirlineSchemes.ALL) {
            val key = scheme.key
            // Kachel: Primär als Grundfarbe, Sekundär als Schrift.
            val tileBg = colorLine(valuesText, "${key}_tile_bg")
            val tileText = colorLine(valuesText, "${key}_tile_text")
            assertNotNull("tile_bg fehlt: $key", tileBg)
            assertNotNull("tile_text fehlt: $key", tileText)
            assertNotNull("tile_chart (Tag) fehlt: $key", colorLine(valuesText, "${key}_tile_chart"))
            assertNotNull("tile_chart (Nacht) fehlt: $key", colorLine(nightText, "${key}_tile_chart"))
            assertEquals(
                "Kachelgrund muss der Primärfarbe entsprechen: $key",
                colorLine(valuesText, "${key}_brand_primary"), tileBg
            )
            assertTrue(
                "Kachelschrift zu blass auf Kachelgrund: $key",
                contrast(tileText!!, tileBg!!) >= 4.5
            )
            // Alle 12 Material-3-Rollen in beiden Modi.
            for (role in m3Roles) {
                assertNotNull("Tag-Rolle fehlt: $key/$role", colorLine(valuesText, "${key}_$role"))
                assertNotNull("Nacht-Rolle fehlt: $key/$role", colorLine(nightText, "${key}_$role"))
            }
            // Theme-Overlay mit genau den 12 Rollen.
            val styleName = "Theme.Logbook.Airline${key.removePrefix("airline_").uppercase()}"
            assertTrue("Theme-Overlay fehlt: $styleName", valuesText.contains("<style name=\"$styleName\""))
            for (role in m3Roles) {
                assertTrue(
                    "Theme $styleName ohne $role",
                    valuesText.contains("@color/${key}_$role")
                )
            }
            // Kachel-interne Zeichnungen (Balken, Kompass, Globuslinien) folgen
            // bei Airline-Schemas nicht dem Akzent, sondern der Sekundärfarbe.
            assertTrue(
                "Theme $styleName ohne tileChartColor",
                valuesText.contains("<item name=\"tileChartColor\">@color/${key}_tile_chart</item>")
            )
            // Kachelschrift (Werte, Überschriften, Trennlinien) = Sekundärfarbe.
            assertNotNull("tile_text_dim fehlt: $key", colorLine(valuesText, "${key}_tile_text_dim"))
            assertTrue(
                "Theme $styleName ohne tileContentColor",
                valuesText.contains("<item name=\"tileContentColor\">@color/${key}_tile_text</item>")
            )
            assertTrue(
                "Theme $styleName ohne tileContentSecondaryColor",
                valuesText.contains("<item name=\"tileContentSecondaryColor\">@color/${key}_tile_text_dim</item>")
            )
            // tileChartColor muss gegen die jeweilige Oberfläche lesbar sein.
            assertTrue(
                "tileChartColor (Tag) zu blass auf Oberfläche: $key",
                contrast(colorLine(valuesText, "${key}_tile_chart")!!, "#FFFAFAFA") >= 4.5
            )
            assertTrue(
                "tileChartColor (Nacht) zu blass auf Oberfläche: $key",
                contrast(colorLine(nightText, "${key}_tile_chart")!!, "#FF1C1B1F") >= 4.5
            )
        }
    }

    @Test
    fun balkenUndGrafiken_nurBeiLhDieVlAkzentfarbe() {
        // Nur beim aktiven LH-Schema wird für Balken/Grafiken die Akzentfarbe
        // von VL vorgeschaltet.
        assertEquals(AirlineSchemes.VL_KEY, AirlineSchemes.chartColorOverrideKey(AirlineSchemes.LH_KEY))
        // Alle anderen Airline-Schemas (auch VL selbst) bleiben bei null.
        AirlineSchemes.ALL.map { it.key }
            .filter { it != AirlineSchemes.LH_KEY }
            .forEach { assertNull("Override bei $it", AirlineSchemes.chartColorOverrideKey(it)) }
        // Klassische Akzentfarben und null ebenfalls ohne Override.
        Settings.ACCENT_OPTIONS.forEach {
            assertNull("Override bei $it", AirlineSchemes.chartColorOverrideKey(it))
        }
        assertNull(AirlineSchemes.chartColorOverrideKey(null))
        // Der Override-Schlüssel muss ein bekanntes Schema sein.
        assertNotNull(AirlineSchemes.byKey(AirlineSchemes.chartColorOverrideKey(AirlineSchemes.LH_KEY)!!))
    }

    @Test
    fun effektiveAkzentfarbe_nurBeiLhDieVlAkzentfarbe() {
        // Nur beim aktiven LH-Schema schaltet die effektive Akzentfarbe auf VL um.
        assertEquals(AirlineSchemes.VL_KEY, AirlineSchemes.effectiveAccentKey(AirlineSchemes.LH_KEY))
        // Alle anderen Airline-Schemas (auch VL selbst) bleiben bei sich.
        AirlineSchemes.ALL.map { it.key }
            .filter { it != AirlineSchemes.LH_KEY }
            .forEach { assertEquals(it, AirlineSchemes.effectiveAccentKey(it)) }
        // Klassische Akzentfarben und null bleiben unverändert.
        Settings.ACCENT_OPTIONS.forEach {
            assertEquals(it, AirlineSchemes.effectiveAccentKey(it))
        }
        assertNull(AirlineSchemes.effectiveAccentKey(null))
        // Der effektive Schlüssel muss ein bekanntes Schema sein.
        assertNotNull(AirlineSchemes.byKey(AirlineSchemes.effectiveAccentKey(AirlineSchemes.LH_KEY)!!))
    }

    @Test
    fun themeResNamenPassenZuDenGeneriertenStyles() {
        for (scheme in AirlineSchemes.ALL) {
            val expected = "Theme.Logbook.Airline${scheme.key.removePrefix("airline_").uppercase()}"
            assertTrue(valuesText.contains("<style name=\"$expected\""))
            // R.style-Name des Overlays (Punkte werden zu Unterstrichen).
            assertNotEquals(0, scheme.themeRes)
        }
    }

    /** Relativer Kontrast nach WCAG, aus einer #AARRGGBB-Farbe. */
    private fun contrast(c1: String, c2: String): Double {
        fun lum(hex: String): Double {
            fun f(v: Int): Double {
                val c = v / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            val r = f(hex.substring(3, 5).toInt(16))
            val g = f(hex.substring(5, 7).toInt(16))
            val b = f(hex.substring(7, 9).toInt(16))
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        val l1 = lum(c1)
        val l2 = lum(c2)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }
}
