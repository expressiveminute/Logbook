package com.highfly.logbook

import android.content.Context
import android.content.SharedPreferences

object DashboardPrefs {

    data class Tile(
        val id: String,
        val nameRes: Int,
        val iconRes: Int
    )

    const val ABOVE = "above"
    const val BELOW = "below"

    val CATALOG = listOf(
        Tile("flights", R.string.tile_flights, R.drawable.ic_flight),
        Tile("distance", R.string.tile_distance, R.drawable.ic_distance),
        Tile("time", R.string.tile_time, R.drawable.ic_time),
        Tile("routes", R.string.tile_routes, R.drawable.ic_world_map),
        Tile("airports", R.string.tile_airports, R.drawable.ic_airports),
        Tile("airlines", R.string.tile_airlines, R.drawable.ic_airlines),
        Tile("layover", R.string.tile_layover, R.drawable.ic_flight_takeoff),
        Tile("class", R.string.tile_class, R.drawable.ic_class),
        Tile("traveltype", R.string.tile_travel_type, R.drawable.ic_work),
        Tile("aircraft", R.string.tile_aircraft_type, R.drawable.ic_flight),
        Tile("registration", R.string.tile_registration, R.drawable.ic_tag),
    )

    private const val PREF_NAME = "dashboard_layout"
    private const val KEY_COLS_ABOVE = "cols_above"
    private const val KEY_COLS_BELOW = "cols_below"
    private const val KEY_TILES_ABOVE = "tiles_above"
    private const val KEY_TILES_BELOW = "tiles_below"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun tileById(id: String): Tile =
        CATALOG.firstOrNull { it.id == id } ?: CATALOG.first()

    fun cols(context: Context, section: String): Int =
        prefs(context).getInt(
            if (section == ABOVE) KEY_COLS_ABOVE else KEY_COLS_BELOW,
            2
        )

    fun setCols(context: Context, section: String, cols: Int) {
        prefs(context).edit()
            .putInt(if (section == ABOVE) KEY_COLS_ABOVE else KEY_COLS_BELOW, cols)
            .apply()
    }

    fun tiles(context: Context, section: String): List<String> {
        val key = if (section == ABOVE) KEY_TILES_ABOVE else KEY_TILES_BELOW
        val stored = prefs(context).getString(key, null) ?: return defaultTiles(section)
        return stored.split(",").filter { it.isNotBlank() }
    }

    fun setTiles(context: Context, section: String, ids: List<String>) {
        prefs(context).edit()
            .putString(
                if (section == ABOVE) KEY_TILES_ABOVE else KEY_TILES_BELOW,
                ids.joinToString(",")
            )
            .apply()
    }

    private fun defaultTiles(section: String): List<String> =
        if (section == ABOVE) listOf("flights", "layover", "routes", "airlines")
        else emptyList()
}