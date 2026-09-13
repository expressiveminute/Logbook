package com.highfly.logbook

import android.content.Context
import java.time.LocalDate

object LogbookRepository {

    private var db: LogbookDatabase? = null

    fun init(context: Context) {
        if (db == null) {
            db = LogbookDatabase(context.applicationContext)
            if (db?.count() == 0) {
                bufferDemoEntries()
            }
        }
    }

    private fun bufferDemoEntries() {
        addEntries(
            listOf(
                LogbookEntry(
                    date = LocalDate.of(2026, 9, 1),
                    flightType = "On Duty",
                    classType = "Business",
                    fromAirport = "FRA",
                    toAirport = "JFK",
                    airline = "LH",
                    flightNumber = "401",
                    aircraftType = "A350-900",
                    registration = "D-AIXA",
                    distanceKm = 6195,
                    flightMinutes = 480
                ),
                LogbookEntry(
                    date = LocalDate.of(2026, 3, 10),
                    flightType = "On Duty",
                    classType = "Economy",
                    fromAirport = "JFK",
                    toAirport = "FRA",
                    airline = "LH",
                    flightNumber = "400",
                    aircraftType = "A350-900",
                    registration = "D-AIXA",
                    distanceKm = 6195,
                    flightMinutes = 455,
                    layover = true
                ),
                LogbookEntry(
                    date = LocalDate.of(2024, 6, 15),
                    flightType = "Privat",
                    classType = "Economy",
                    fromAirport = "FRA",
                    toAirport = "CDG",
                    airline = "LH",
                    flightNumber = "1032",
                    aircraftType = "A320",
                    registration = "D-AIPP",
                    distanceKm = 450,
                    flightMinutes = 75
                ),
                LogbookEntry(
                    date = LocalDate.of(2023, 1, 5),
                    flightType = "Dienstreise",
                    classType = "Premium Eco",
                    fromAirport = "MUC",
                    toAirport = "ZRH",
                    airline = "LH",
                    flightNumber = "2140",
                    aircraftType = "A321",
                    registration = "D-AIRU",
                    distanceKm = 265,
                    flightMinutes = 60
                ),
            )
        )
    }

    fun addEntry(entry: LogbookEntry) {
        db?.insert(entry)
    }

    fun addEntries(newEntries: List<LogbookEntry>) {
        newEntries.forEach { addEntry(it) }
    }

    fun getEntries(): List<LogbookEntry> = db?.getAll() ?: emptyList()

    fun getEntry(id: Long): LogbookEntry? = db?.getEntry(id)

    fun updateEntry(entry: LogbookEntry) {
        db?.update(entry)
    }

    fun deleteEntry(id: Long) {
        db?.delete(id)
    }

    fun getYears(): List<Int> =
        getEntries().map { it.date.year }.distinct().sortedDescending()
}