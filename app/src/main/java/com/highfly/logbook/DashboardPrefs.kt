package com.highfly.logbook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

object DashboardPrefs {

    const val SPAN_FULL = 3

    data class TileStyle(
        val backgroundColorRes: Int,
        val contentColorRes: Int = R.color.white,
        val strokeColorRes: Int = R.color.white,
        val strokeWidthDp: Int = 0
    )

    data class Tile(
        val id: String,
        val nameRes: Int,
        val iconRes: Int,
        val span: Int = 1,
        val heightDp: Int = 72,
        val nameSingularRes: Int? = null
    )

    /**
     * Platz einer Kachel im Raster, 0-basiert. Die Farbe hängt an der
     * Position, nicht an der Kachel: wird eine Kachel verschoben, nimmt sie
     * die Farbe ihres neuen Platzes an.
     */
    data class TilePosition(val row: Int, val column: Int)

    private fun brownStyle(backgroundColorRes: Int) =
        TileStyle(backgroundColorRes)

    private fun magentaStyle(backgroundColorRes: Int) =
        TileStyle(backgroundColorRes)

    private fun turquoiseStyle(backgroundColorRes: Int) =
        TileStyle(backgroundColorRes)

    /** Anzahl der Reihen, die eine eigene Kachelfarbe haben. */
    const val COLORED_ROWS = 3

    /** Spalten je Reihe, die eine eigene Kachelfarbe haben. */
    const val COLORED_COLUMNS = 3

    private fun accentStyles(
        brown: Int,
        magenta: Int,
        turquoise: Int
    ): Map<String, TileStyle> = mapOf(
        Settings.ACCENT_BROWN to brownStyle(brown),
        Settings.ACCENT_MAGENTA to magentaStyle(magenta),
        Settings.ACCENT_TURQUOISE to turquoiseStyle(turquoise)
    )

    /**
     * Farbcodes der Rasterpositionen, nicht der Kacheln. Position 1 ist links
     * oben, danach zeilenweise nach rechts. Positionen ohne Eintrag werden in
     * der Standarddarstellung gezeichnet.
     */
    private val POSITION_STYLES: Map<Int, Map<String, TileStyle>> = mapOf(
        0 to accentStyles(
            R.color.dashboard_brown_pos1,
            R.color.dashboard_magenta_pos1,
            R.color.dashboard_turquoise_pos1
        ),
        1 to accentStyles(
            R.color.dashboard_brown_pos2,
            R.color.dashboard_magenta_pos2,
            R.color.dashboard_turquoise_pos2
        ),
        2 to accentStyles(
            R.color.dashboard_brown_pos3,
            R.color.dashboard_magenta_pos3,
            R.color.dashboard_turquoise_pos3
        ),
        3 to accentStyles(
            R.color.dashboard_brown_pos4,
            R.color.dashboard_magenta_pos4,
            R.color.dashboard_turquoise_pos4
        ),
        4 to accentStyles(
            R.color.dashboard_brown_pos5,
            R.color.dashboard_magenta_pos5,
            R.color.dashboard_turquoise_pos5
        ),
        5 to accentStyles(
            R.color.dashboard_brown_pos6,
            R.color.dashboard_magenta_pos6,
            R.color.dashboard_turquoise_pos6
        ),
        6 to accentStyles(
            R.color.dashboard_brown_pos7,
            R.color.dashboard_magenta_pos7,
            R.color.dashboard_turquoise_pos7
        ),
        7 to accentStyles(
            R.color.dashboard_brown_pos8,
            R.color.dashboard_magenta_pos8,
            R.color.dashboard_turquoise_pos8
        ),
        8 to accentStyles(
            R.color.dashboard_brown_pos9,
            R.color.dashboard_magenta_pos9,
            R.color.dashboard_turquoise_pos9
        )
    )

    /**
     * Farbcode der Rasterposition für den gewählten Akzent, oder null, wenn die
     * Position keine eigene Farbe hat und die Standarddarstellung gilt.
     */
    fun positionStyle(position: TilePosition, accent: String): TileStyle? {
        if (position.row !in 0 until COLORED_ROWS) return null
        if (position.column !in 0 until COLORED_COLUMNS) return null
        val key = position.row * COLORED_COLUMNS + position.column
        return POSITION_STYLES[key]?.get(accent)
    }

    val CATALOG = listOf(
        Tile(
            "flights",
            R.string.tile_flights,
            R.drawable.ic_flight,
            nameSingularRes = R.string.tile_flights_singular
        ),
        Tile(
            "layover",
            R.string.tile_layover,
            R.drawable.ic_umbrella
        ),
        Tile(
            "airlines",
            R.string.tile_airlines,
            R.drawable.ic_airlines,
            nameSingularRes = R.string.tile_airlines_singular
        ),
        Tile(
            "aircraftreg",
            R.string.tile_aircraft_reg,
            R.drawable.ic_hangar,
            nameSingularRes = R.string.tile_aircraft_reg_singular
        ),
        Tile(
            "class",
            R.string.tile_class,
            R.drawable.ic_class
        ),
        Tile(
            "traveltype",
            R.string.tile_travel_type,
            R.drawable.ic_work
        ),
        Tile(
            "worldmap",
            R.string.tile_world_map,
            R.drawable.ic_world_map,
            span = SPAN_FULL,
            heightDp = 184
        ),
        Tile(
            "distance",
            R.string.tile_distance,
            R.drawable.ic_distance,
            span = SPAN_FULL,
            heightDp = 200
        ),
        Tile(
            "co2",
            R.string.tile_co2,
            R.drawable.ic_co2,
            span = SPAN_FULL,
            heightDp = 104
        ),
        Tile(
            "time",
            R.string.tile_time,
            R.drawable.ic_time,
            span = SPAN_FULL,
            heightDp = 200
        ),
        Tile(
            "routes",
            R.string.tile_routes,
            R.drawable.ic_control_tower,
            nameSingularRes = R.string.tile_routes_singular
        ),
        Tile(
            "flightroutes",
            R.string.tile_flight_routes,
            R.drawable.ic_routes,
            nameSingularRes = R.string.tile_flight_routes_singular
        ),
        Tile(
            "countries",
            R.string.tile_countries,
            R.drawable.ic_countries,
            nameSingularRes = R.string.tile_countries_singular
        ),
        Tile(
            "function",
            R.string.tile_function,
            R.drawable.ic_function
        ),
        Tile(
            "earthorbits",
            R.string.tile_earth_orbits,
            R.drawable.ic_earth
        ),
        Tile(
            "moon",
            R.string.tile_moon,
            R.drawable.ic_moon
        ),
        Tile(
            "reisebuddy",
            R.string.tile_reisebuddy,
            R.drawable.ic_reisebuddy
        ),
    )

    val DEFAULT_ROWS = listOf(
        listOf("flights", "layover", "aircraftreg"),
        listOf("airlines", "routes", "flightroutes"),
        listOf("countries", "function", "reisebuddy"),
        listOf("worldmap"),
        listOf("traveltype", "class"),
        listOf("distance"),
        listOf("time"),
        listOf("co2")
    )

    /**
     * Grenzen und Standard der globalen Kachel-Grösse, siehe [readScale].
     *
     * Die Grösse ist der Anteil der Rasterzelle, den die Kachel füllt: 100 %
     * heisst, dass die Kacheln ohne Zwischenraum aneinanderstossen, darunter
     * schrumpfen sie gleichmässig in ihrer Zelle. Standard ist 95 %.
     */
    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.00f
    const val DEFAULT_SCALE = 0.95f

    /**
     * Der Schieber rechnet in ganzen Prozent (85..100, Schritt 1). Eine krumme
     * Schrittweite wie 0,01 lässt Material den letzten Rastpunkt verlieren:
     * `1,0f - 0,85f` ergibt als Fliesskommazahl 0,14999998, geteilt durch 0,01
     * also 14,999998 – Material zeichnet dann 15 statt 16 Punkte und verteilt
     * sie über die ganze Breite. Der Regler läge zwischen den Punkten. Ganze
     * Zahlen treffen das Raster exakt.
     */
    const val MIN_PERCENT = 85
    const val MAX_PERCENT = 100
    const val DEFAULT_PERCENT = 95

    private const val PREF_NAME = "dashboard_layout"
    private const val KEY_TILES = "tiles_standard_v14"
    private const val KEY_SPANS = "tile_spans_v1"
    private const val KEY_SCALE = "tile_scale_v1"
    private const val ROW_SEPARATOR = ";"
    private const val SPAN_SEPARATOR = "="

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun tileById(id: String): Tile =
        CATALOG.firstOrNull { it.id == id } ?: CATALOG.first()

    /** Standardbreite einer Kachel in Spalten (1..[SPAN_FULL]). */
    fun defaultSpan(tileId: String): Int = tileById(tileId).span.coerceIn(1, SPAN_FULL)

    /**
     * Die vom Nutzer gewählten Kachelbreiten (Spalten 1..[SPAN_FULL]). Nur
     * Kacheln mit einer eigenen Breite stehen hier; alle anderen nutzen ihren
     * Standard aus dem [CATALOG].
     */
    fun readSpans(context: Context): Map<String, Int> {
        val stored = prefs(context).getString(KEY_SPANS, null) ?: return emptyMap()
        val result = mutableMapOf<String, Int>()
        stored.split(ROW_SEPARATOR).forEach { part ->
            val pair = part.split(SPAN_SEPARATOR)
            if (pair.size != 2) return@forEach
            val id = pair[0]
            if (CATALOG.none { it.id == id }) return@forEach
            val span = pair[1].toIntOrNull() ?: return@forEach
            result[id] = span.coerceIn(1, SPAN_FULL)
        }
        return result
    }

    fun writeSpans(context: Context, spans: Map<String, Int>) {
        // Nur abweichende Breiten speichern; unveränderte Kacheln bleiben beim
        // Standard aus dem CATALOG und übernehmen spätere Änderungen daran.
        val clean = spans
            .filterKeys { id -> CATALOG.any { it.id == id } }
            .mapValues { (_, value) -> value.coerceIn(1, SPAN_FULL) }
            .filter { (id, value) -> value != defaultSpan(id) }
        prefs(context).edit().putString(
            KEY_SPANS,
            clean.entries.joinToString(ROW_SEPARATOR) {
                "${it.key}$SPAN_SEPARATOR${it.value}"
            }
        ).apply()
    }

    /** Breite aller Kacheln inklusive Standard, fertig zum Zeichnen. */
    fun spansWithDefaults(context: Context): Map<String, Int> {
        val stored = readSpans(context)
        return CATALOG.associate { it.id to (stored[it.id] ?: defaultSpan(it.id)) }
    }

    fun spanOf(spans: Map<String, Int>, tileId: String): Int =
        (spans[tileId] ?: defaultSpan(tileId)).coerceIn(1, SPAN_FULL)

    /**
     * Globale Kachel-Grösse: der Anteil der Rasterzelle, den eine Kachel füllt.
     * Bei 1.0 stossen die Kacheln ohne Zwischenraum aneinander, darunter
     * schrumpfen sie gleichmässig in ihrer Zelle, während das Raster selbst
     * unverändert bleibt.
     *
     * Der Wert wird auf das Raster des Schiebers gerundet: Ein früher einmal
     * frei gezogener Wert (etwa 1.0214286) liegt sonst nicht auf einem
     * Vielfachen der Schrittweite und der Schieber stürzt beim Aufbau ab.
     */
    fun readScale(context: Context): Float =
        snapScale(prefs(context).getFloat(KEY_SCALE, DEFAULT_SCALE))

    fun writeScale(context: Context, value: Float) {
        prefs(context).edit()
            .putFloat(KEY_SCALE, snapScale(value))
            .apply()
    }

    /** Prozentwert des Schiebers (85..100) zu einer Kachel-Grösse. */
    fun scaleToPercent(scale: Float): Int =
        (scale * 100f).roundToInt().coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Kachel-Grösse zu einem Prozentwert des Schiebers. */
    fun percentToScale(percent: Int): Float =
        percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f

    /** Rundet eine Grösse auf ganze Prozent und begrenzt sie. */
    fun snapScale(value: Float): Float = percentToScale(scaleToPercent(value))

    /** Reihenfolge der sichtbaren Kacheln, zeilenweise gelesen. */
    fun readFlat(context: Context): List<String> = readRows(context).flatten()

    /**
     * Schreibt die flache Reihenfolge als Zeilen. Fortlaufend werden bis zu
     * [SPAN_FULL] Spalten gefüllt; passt eine Kachel nicht mehr hinein, beginnt
     * eine neue Zeile. Eine Zeile, die nicht ganz aufgeht, lässt die restlichen
     * Spalten leer; die Kacheln behalten ihre Breite.
     */
    fun writeFlat(context: Context, flat: List<String>, spans: Map<String, Int>) {
        writeRows(context, packRows(flat, spans))
    }

    /** Gruppiert die flache Reihenfolge zu Zeilen von höchstens [SPAN_FULL] Spalten. */
    fun packRows(flat: List<String>, spans: Map<String, Int>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var columns = 0
        for (id in flat) {
            if (CATALOG.none { it.id == id }) continue
            val span = spanOf(spans, id)
            if (columns + span > SPAN_FULL) {
                result.add(current)
                current = mutableListOf()
                columns = 0
            }
            current.add(id)
            columns += span
            if (columns == SPAN_FULL) {
                result.add(current)
                current = mutableListOf()
                columns = 0
            }
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    /** Setzt Layout und Grösse auf den Standard zurück. */
    fun resetToDefault(context: Context) {
        writeRows(context, DEFAULT_ROWS)
        prefs(context).edit()
            .remove(KEY_SPANS)
            .remove(KEY_SCALE)
            .apply()
    }

    /**
     * Ob eine Kachel zur Rolle passt. Layover und Funktion sind reine
     * Besatzungs-Kacheln und fehlen dem Fluggast ueberall: im Dashboard, in der
     * Auswahl des Bearbeitens und damit auch in der Detailseite. Die Zuordnung
     * haengt an [Role.showsLayover] und [Role.showsFunction], damit Kachel und
     * Definition nicht auseinanderlaufen koennen.
     */
    fun isTileAvailable(id: String, role: Role): Boolean = when (id) {
        "layover" -> role.showsLayover
        "function" -> role.showsFunction
        else -> true
    }

    private fun cleanRows(rows: List<List<String>>): List<List<String>> =
        rows.map { row -> row.filter { id -> CATALOG.any { it.id == id } } }
            .filter { it.isNotEmpty() }

    fun readRows(context: Context): List<List<String>> {
        val stored = prefs(context).getString(KEY_TILES, null)
            ?: return DEFAULT_ROWS
        val rows = stored.split(ROW_SEPARATOR)
            .map { row -> row.split(",").filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
        // Fehlt der Schlüssel, gilt der Standard; ist er vorhanden, aber leer,
        // hat der Nutzer alle Kacheln ausgeblendet - dann bleibt das Raster leer.
        return cleanRows(rows)
    }

    fun writeRows(context: Context, rows: List<List<String>>) {
        val cleanedRows = cleanRows(rows)
        prefs(context).edit()
            .putString(
                KEY_TILES,
                cleanedRows.joinToString(ROW_SEPARATOR) { it.joinToString(",") }
            )
            .apply()
    }
}

object DashboardTileColors {

    private val textViewIds = intArrayOf(
        R.id.tv_tile_name,
        R.id.tv_tile_value,
        R.id.tv_tile_unit
    )

    fun apply(card: MaterialCardView, position: DashboardPrefs.TilePosition) {
        val accent = Settings.getAccentColor(card.context)
        val style = DashboardPrefs.positionStyle(position, accent)
        if (style == null) {
            card.setCardBackgroundColor(
                MaterialColors.getColor(
                    card,
                    com.google.android.material.R.attr.colorSecondaryContainer
                )
            )
            card.strokeColor = MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorOutlineVariant
            )
            card.strokeWidth = dp(card, 1)
            return
        }

        val backgroundColor = ContextCompat.getColor(card.context, style.backgroundColorRes)
        val contentColor = ContextCompat.getColor(card.context, style.contentColorRes)
        val strokeColor = ContextCompat.getColor(card.context, style.strokeColorRes)

        card.setCardBackgroundColor(backgroundColor)
        card.strokeColor = strokeColor
        card.strokeWidth = dp(card, style.strokeWidthDp)

        textViewIds.forEach { viewId ->
            setTextColor(card.findViewById(viewId), contentColor)
        }
        card.findViewById<ImageView>(R.id.iv_tile_icon)?.imageTintList =
            ColorStateList.valueOf(contentColor)
    }

    private fun setTextColor(view: TextView?, color: Int) {
        view ?: return
        view.setTextColor(color)
        val text = view.text as? Spannable ?: return
        text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            .forEach { text.removeSpan(it) }
    }

    private fun dp(card: MaterialCardView, value: Int): Int =
        (value * card.resources.displayMetrics.density).roundToInt()
}
