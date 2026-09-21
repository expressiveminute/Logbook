package com.highfly.logbook

import android.content.Context
import android.content.SharedPreferences

object DashboardPrefs {

    const val SPAN_FULL = 3

    data class Tile(
        val id: String,
        val nameRes: Int,
        val iconRes: Int,
        val span: Int = 1,
        val heightDp: Int = 72
    )

    val CATALOG = listOf(
        Tile("flights", R.string.tile_flights, R.drawable.ic_flight),
        Tile("layover", R.string.tile_layover, R.drawable.ic_umbrella),
        Tile("airlines", R.string.tile_airlines, R.drawable.ic_airlines),
        Tile("aircraft", R.string.tile_aircraft_type, R.drawable.ic_aircraft),
        Tile("class", R.string.tile_class, R.drawable.ic_class),
        Tile("traveltype", R.string.tile_travel_type, R.drawable.ic_work),
        Tile("worldmap", R.string.tile_world_map, R.drawable.ic_world_map, span = SPAN_FULL, heightDp = 184),
        Tile("distance", R.string.tile_distance, R.drawable.ic_distance, span = SPAN_FULL, heightDp = 200),
        Tile("co2", R.string.tile_co2, R.drawable.ic_co2, span = SPAN_FULL, heightDp = 104),
        Tile("time", R.string.tile_time, R.drawable.ic_time),
        Tile("routes", R.string.tile_routes, R.drawable.ic_routes),
        Tile("airports", R.string.tile_airports, R.drawable.ic_airports),
        Tile("registration", R.string.tile_registration, R.drawable.ic_tag),
        Tile("countries", R.string.tile_countries, R.drawable.ic_countries),
        Tile("function", R.string.tile_function, R.drawable.ic_function),
        Tile("earthorbits", R.string.tile_earth_orbits, R.drawable.ic_earth),
        Tile("moon", R.string.tile_moon, R.drawable.ic_moon),
    )

    val DEFAULT_ROWS = listOf(
        listOf("flights", "layover", "time"),
        listOf("airlines", "aircraft", "routes"),
        listOf("countries", "registration", "function"),
        listOf("worldmap"),
        listOf("traveltype", "class"),
        listOf("distance"),
        listOf("co2")
    )

    private const val PREF_NAME = "dashboard_layout"
    private const val KEY_TILES = "tiles_standard_v7"
    private const val ROW_SEPARATOR = ";"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun tileById(id: String): Tile =
        CATALOG.firstOrNull { it.id == id } ?: CATALOG.first()

    fun readRows(context: Context): List<List<String>> {
        val stored = prefs(context).getString(KEY_TILES, null)
            ?: return DEFAULT_ROWS
        val rows = stored.split(ROW_SEPARATOR)
            .map { row -> row.split(",").filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
        return if (rows.isEmpty()) DEFAULT_ROWS else rows
    }

    fun writeRows(context: Context, rows: List<List<String>>) {
        prefs(context).edit()
            .putString(
                KEY_TILES,
                rows.joinToString(ROW_SEPARATOR) { it.joinToString(",") }
            )
            .apply()
    }
}