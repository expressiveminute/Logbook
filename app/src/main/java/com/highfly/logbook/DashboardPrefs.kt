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
            R.drawable.ic_aircraft,
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
            R.drawable.ic_time
        ),
        Tile(
            "routes",
            R.string.tile_routes,
            R.drawable.ic_routes,
            nameSingularRes = R.string.tile_routes_singular
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
            "discovery",
            R.string.tile_discovery,
            R.drawable.ic_star
        ),
    )

    val DEFAULT_ROWS = listOf(
        listOf("flights", "layover", "time"),
        listOf("airlines", "aircraftreg", "function"),
        listOf("countries", "routes", "discovery"),
        listOf("worldmap"),
        listOf("traveltype", "class"),
        listOf("distance"),
        listOf("co2")
    )

    private const val PREF_NAME = "dashboard_layout"
    private const val KEY_TILES = "tiles_standard_v12"
    private const val ROW_SEPARATOR = ";"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun tileById(id: String): Tile =
        CATALOG.firstOrNull { it.id == id } ?: CATALOG.first()

    private fun cleanRows(rows: List<List<String>>): List<List<String>> =
        rows.map { row -> row.filter { id -> CATALOG.any { it.id == id } } }
            .filter { it.isNotEmpty() }

    fun readRows(context: Context): List<List<String>> {
        val stored = prefs(context).getString(KEY_TILES, null)
            ?: return DEFAULT_ROWS
        val rows = stored.split(ROW_SEPARATOR)
            .map { row -> row.split(",").filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
        val cleanedRows = cleanRows(rows)
        return if (cleanedRows.isEmpty()) DEFAULT_ROWS else cleanedRows
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
