package com.highfly.logbook

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prüft die Flaggen-Assets unter `assets/state_flags/`: Zu jedem Staat in
 * `state_shapes.bin` muss es eine PNG geben, und umgekehrt - die Liste in der
 * Rubbelkarte zeigt sonst Lücken oder die Dateien liegen verwaist herum.
 * Erzeugt werden die Dateien von `tools/build_state_flags.py` (Wikimedia
 * Commons, gemeinfrei).
 */
class StateFlagsAssetTest {

    @Test
    fun flaggen_passenZuDenStaatenImAsset() {
        val asset = File("src/main/assets/${StateShapes.ASSET}")
        assertTrue("Asset nicht gefunden: ${asset.absolutePath}", asset.exists())
        val states = StateShapes.parse(asset.readBytes())
        assertTrue("zu wenige Staaten", states.size >= 60)

        val dir = File("src/main/assets/state_flags")
        assertTrue("Flaggen-Ordner fehlt: ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.extension == "png" }
            ?.map { it.nameWithoutExtension.uppercase() }
            ?.toSet()
            ?: emptySet()

        for (state in states) {
            val code = state.code.uppercase()
            assertTrue(
                "keine Flagge für $code (${state.nameDe})",
                files.contains(code)
            )
            val png = File(dir, "$code.png").readBytes()
            // PNG-Magic: der Loader dekodiert später genau diese Datei.
            assertTrue(
                "Flagge $code ist keine PNG",
                png.size > 8 && png[0] == 0x89.toByte() && png[1] == 'P'.code.toByte() &&
                    png[2] == 'N'.code.toByte() && png[3] == 'G'.code.toByte()
            )
        }
        for (file in files) {
            assertTrue(
                "verwaiste Flagge $file ohne Staat im Asset",
                states.any { it.code.uppercase() == file }
            )
        }
    }
}
