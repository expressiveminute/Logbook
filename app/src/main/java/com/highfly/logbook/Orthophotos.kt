package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject

/**
 * Ein mitgelieferter Luftbild-Ausschnitt: das Bild samt seiner Randkoordinaten
 * (WGS84). Der [code] ist der IATA-Code des Flughafens, um den der Ausschnitt
 * liegt – er dient zugleich als Dateiname unter `assets/orthophotos/`.
 */
class Orthophoto(
    val code: String,
    val bitmap: Bitmap,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
)

/**
 * Lädt die amtlichen Luftbilder (DOP20) für die Seite "CO₂-Ausgleich".
 *
 * Sie liegen fertig als `assets/orthophotos/<CODE>.webp` samt Randkoordinaten
 * in `<CODE>.json`; es wird nie etwas nachgeladen. Erzeugt werden sie von
 * `tools/build_orthophotos.py`. Bewusst auf die beiden Flughäfen FRA (Hessen)
 * und MUC (Bayern) beschränkt – nur diese eine Karte nutzt sie, und nur diese
 * beiden Bundesländer liefern passende offene Daten.
 *
 * Der Baustein ist in sich geschlossen und lässt sich mit [ENABLED] in einem
 * Schritt abschalten; siehe [ENABLED].
 */
object Orthophotos {

    /**
     * Schaltet die Luftbilder der CO₂-Karte an oder aus. Steht das auf `false`,
     * verhält sich die Karte genau wie ohne dieses Merkmal: kein Bild, kein
     * Quellennachweis, keine Ladezeit. Zum vollständigen Rückbau siehe den
     * Abschnitt "Rückbau" in AGENTS.md.
     */
    const val ENABLED = true

    private const val TAG = "Orthophotos"
    private const val DIR = "orthophotos"

    /** Flughäfen, für die ein Ausschnitt mitgeliefert wird. */
    private val CODES = setOf("FRA", "MUC")

    /** Zuletzt geladenes Bild; es wird immer nur eines gebraucht. */
    @Volatile
    private var cached: Orthophoto? = null

    /** Wird für diesen Flughafen ein Luftbild mitgeliefert? */
    fun isSupported(code: String): Boolean = ENABLED && code.uppercase() in CODES

    /**
     * Lädt das Luftbild zu [code] (nur vom Hintergrundthread aufrufen). Gibt
     * `null` zurück, wenn das Merkmal aus ist, der Flughafen nicht unterstützt
     * wird oder das Asset fehlt – die Karte bleibt dann unverändert.
     */
    fun load(context: Context, code: String): Orthophoto? {
        if (!ENABLED) return null
        val key = code.uppercase()
        if (key !in CODES) return null
        cached?.let { if (it.code == key) return it }
        synchronized(this) {
            cached?.let { if (it.code == key) return it }
            val loaded = loadUncached(context, key) ?: return null
            cached = loaded
            return loaded
        }
    }

    private fun loadUncached(context: Context, code: String): Orthophoto? = try {
        val json = context.assets.open("$DIR/$code.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        // RGB_565 statt ARGB_8888: halbiert den Speicher, Alpha braucht ein
        // Luftbild nicht.
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = context.assets.open("$DIR/$code.webp").use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Bild konnte nicht dekodiert werden")
        Orthophoto(
            code = code,
            bitmap = bitmap,
            minLat = root.getDouble("min_lat"),
            minLon = root.getDouble("min_lon"),
            maxLat = root.getDouble("max_lat"),
            maxLon = root.getDouble("max_lon")
        )
    } catch (e: Exception) {
        Log.w(TAG, "Kein Luftbild für $code vorhanden", e)
        null
    }

    /**
     * Quellennachweis des Flughafens. Bayern verlangt als CC-BY-4.0-Daten eine
     * Namensnennung, Hessen (dl-de/zero-2.0) nicht; der Nachweis steht daher
     * für beide. `null`, wenn das Merkmal aus ist oder es keinen gibt.
     */
    fun creditRes(code: String): Int? {
        if (!ENABLED) return null
        return when (code.uppercase()) {
            "FRA" -> R.string.co2_offset_credit_hessen
            "MUC" -> R.string.co2_offset_credit_bayern
            else -> null
        }
    }
}
