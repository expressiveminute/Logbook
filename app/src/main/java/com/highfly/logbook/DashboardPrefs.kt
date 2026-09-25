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
        val nameSingularRes: Int? = null,
        val styles: Map<String, TileStyle> = emptyMap()
    )

    private fun brownStyle(backgroundColorRes: Int) =
        TileStyle(backgroundColorRes)

    private fun purpleStyle(backgroundColorRes: Int) =
        TileStyle(backgroundColorRes)

    private fun blueStyle(backgroundColorRes: Int) =
        TileStyle(backgroundColorRes)

    val CATALOG = listOf(
        Tile(
            "flights",
            R.string.tile_flights,
            R.drawable.ic_flight,
            nameSingularRes = R.string.tile_flights_singular,
            styles = mapOf(
                Settings.ACCENT_BROWN to brownStyle(R.color.dashboard_brown_flights),
                Settings.ACCENT_PURPLE to purpleStyle(R.color.dashboard_purple_flights),
                Settings.ACCENT_BLUE to blueStyle(R.color.dashboard_blue_flights)
            )
        ),
        Tile(
            "layover",
            R.string.tile_layover,
            R.drawable.ic_umbrella,
            styles = mapOf(
                Settings.ACCENT_BROWN to brownStyle(R.color.dashboard_brown_layover),
                Settings.ACCENT_PURPLE to purpleStyle(R.color.dashboard_purple_layover),
                Settings.ACCENT_BLUE to blueStyle(R.color.dashboard_blue_layover)
            )
        ),
        Tile(
            "airlines",
            R.string.tile_airlines,
            R.drawable.ic_airlines,
            nameSingularRes = R.string.tile_airlines_singular,
            styles = mapOf(
                Settings.ACCENT_BROWN to brownStyle(R.color.dashboard_brown_airlines),
                Settings.ACCENT_PURPLE to purpleStyle(R.color.dashboard_purple_airlines),
                Settings.ACCENT_BLUE to blueStyle(R.color.dashboard_blue_airlines)
            )
        ),
        Tile(
            "aircraftreg",
            R.string.tile_aircraft_reg,
            R.drawable.ic_aircraft,
            nameSingularRes = R.string.tile_aircraft_reg_singular,
            styles = mapOf(
                Settings.ACCENT_BROWN to brownStyle(R.color.dashboard_brown_aircraft),
                Settings.ACCENT_PURPLE to purpleStyle(R.color.dashboard_purple_aircraft),
                Settings.ACCENT_BLUE to blueStyle(R.color.dashboard_blue_aircraft)
            )
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
            styles = mapOf(
                Settings.ACCENT_BROWN to brownStyle(R.color.dashboard_brown_time),
                Settings.ACCENT_PURPLE to purpleStyle(R.color.dashboard_purple_time),
                Settings.ACCENT_BLUE to blueStyle(R.color.dashboard_blue_time)
            )
        ),
        Tile(
            "routes",
            R.string.tile_routes,
            R.drawable.ic_routes,
            nameSingularRes = R.string.tile_routes_singular,
            styles = mapOf(
                Settings.ACCENT_BROWN to brownStyle(R.color.dashboard_brown_routes),
                Settings.ACCENT_PURPLE to purpleStyle(R.color.dashboard_purple_routes),
                Settings.ACCENT_BLUE to blueStyle(R.color.dashboard_blue_routes)
            )
        ),
        Tile(
            "countries",
            R.string.tile_countries,
            R.drawable.ic_countries,
            nameSingularRes = R.string.tile_countries_singular,
            styles = mapOf(
                Settings.ACCENT_BROWN to brownStyle(R.color.dashboard_brown_countries),
                Settings.ACCENT_PURPLE to purpleStyle(R.color.dashboard_purple_countries),
                Settings.ACCENT_BLUE to blueStyle(R.color.dashboard_blue_countries)
            )
        ),
        Tile(
            "function",
            R.string.tile_function,
            R.drawable.ic_function,
            styles = mapOf(
                Settings.ACCENT_BROWN to brownStyle(R.color.dashboard_brown_function),
                Settings.ACCENT_PURPLE to purpleStyle(R.color.dashboard_purple_function),
                Settings.ACCENT_BLUE to blueStyle(R.color.dashboard_blue_function)
            )
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
            R.drawable.ic_search
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
    private const val KEY_TILES = "tiles_standard_v11"
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

    fun apply(card: MaterialCardView, tileId: String) {
        val accent = Settings.getAccentColor(card.context)
        val style = DashboardPrefs.tileById(tileId).styles[accent]
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
