package com.highfly.logbook

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

class LogbookDatabase(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ENTRIES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DATE TEXT NOT NULL,
                $COL_FLIGHT_TYPE TEXT,
                $COL_CLASS_TYPE TEXT,
                $COL_FROM_AIRPORT TEXT NOT NULL,
                $COL_TO_AIRPORT TEXT NOT NULL,
                $COL_AIRLINE TEXT,
                $COL_FLIGHT_NUMBER TEXT,
                $COL_AIRCRAFT_TYPE TEXT,
                $COL_REGISTRATION TEXT,
                $COL_DISTANCE_KM INTEGER,
                $COL_FLIGHT_MINUTES INTEGER,
                $COL_LAYOVER INTEGER,
                $COL_COMMENT TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_ENTRIES ADD COLUMN $COL_DISTANCE_KM INTEGER")
            db.execSQL("ALTER TABLE $TABLE_ENTRIES ADD COLUMN $COL_FLIGHT_MINUTES INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_ENTRIES ADD COLUMN $COL_LAYOVER INTEGER")
        }
    }

    fun insert(entry: LogbookEntry) {
        writableDatabase.insert(
            TABLE_ENTRIES,
            null,
            ContentValues().apply {
                put(COL_DATE, entry.date.toString())
                put(COL_FLIGHT_TYPE, entry.flightType)
                put(COL_CLASS_TYPE, entry.classType)
                put(COL_FROM_AIRPORT, entry.fromAirport)
                put(COL_TO_AIRPORT, entry.toAirport)
                put(COL_AIRLINE, entry.airline)
                put(COL_FLIGHT_NUMBER, entry.flightNumber)
                put(COL_AIRCRAFT_TYPE, entry.aircraftType)
                put(COL_REGISTRATION, entry.registration)
                put(COL_DISTANCE_KM, entry.distanceKm)
                put(COL_FLIGHT_MINUTES, entry.flightMinutes)
                put(COL_LAYOVER, if (entry.layover) 1 else 0)
                put(COL_COMMENT, entry.comment)
            }
        )
    }

    fun getEntry(id: Long): LogbookEntry? {
        readableDatabase.use { db ->
            db.query(
                TABLE_ENTRIES,
                null,
                "$COL_ID = ?",
                arrayOf(id.toString()),
                null,
                null,
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
                val dateIdx = cursor.getColumnIndexOrThrow(COL_DATE)
                val flightTypeIdx = cursor.getColumnIndexOrThrow(COL_FLIGHT_TYPE)
                val classTypeIdx = cursor.getColumnIndexOrThrow(COL_CLASS_TYPE)
                val fromIdx = cursor.getColumnIndexOrThrow(COL_FROM_AIRPORT)
                val toIdx = cursor.getColumnIndexOrThrow(COL_TO_AIRPORT)
                val airlineIdx = cursor.getColumnIndexOrThrow(COL_AIRLINE)
                val flightNumberIdx = cursor.getColumnIndexOrThrow(COL_FLIGHT_NUMBER)
                val aircraftTypeIdx = cursor.getColumnIndexOrThrow(COL_AIRCRAFT_TYPE)
                val registrationIdx = cursor.getColumnIndexOrThrow(COL_REGISTRATION)
                val distanceIdx = cursor.getColumnIndexOrThrow(COL_DISTANCE_KM)
                val minutesIdx = cursor.getColumnIndexOrThrow(COL_FLIGHT_MINUTES)
                val layoverIdx = cursor.getColumnIndexOrThrow(COL_LAYOVER)
                val commentIdx = cursor.getColumnIndexOrThrow(COL_COMMENT)
                return cursor.toEntry(
                    idIdx, dateIdx, flightTypeIdx, classTypeIdx,
                    fromIdx, toIdx, airlineIdx, flightNumberIdx,
                    aircraftTypeIdx, registrationIdx, distanceIdx, minutesIdx,
                    layoverIdx, commentIdx
                )
            }
        }
    }

    fun update(entry: LogbookEntry) {
        val id = entry.id ?: return
        writableDatabase.update(
            TABLE_ENTRIES,
            ContentValues().apply {
                put(COL_DATE, entry.date.toString())
                put(COL_FLIGHT_TYPE, entry.flightType)
                put(COL_CLASS_TYPE, entry.classType)
                put(COL_FROM_AIRPORT, entry.fromAirport)
                put(COL_TO_AIRPORT, entry.toAirport)
                put(COL_AIRLINE, entry.airline)
                put(COL_FLIGHT_NUMBER, entry.flightNumber)
                put(COL_AIRCRAFT_TYPE, entry.aircraftType)
                put(COL_REGISTRATION, entry.registration)
                put(COL_DISTANCE_KM, entry.distanceKm)
                put(COL_FLIGHT_MINUTES, entry.flightMinutes)
                put(COL_LAYOVER, if (entry.layover) 1 else 0)
                put(COL_COMMENT, entry.comment)
            },
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE_ENTRIES, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun getAll(): List<LogbookEntry> {
        val entries = mutableListOf<LogbookEntry>()
        readableDatabase.use { db ->
            db.query(
                TABLE_ENTRIES,
                null,
                null,
                null,
                null,
                null,
                "$COL_DATE DESC, $COL_ID DESC"
            ).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
                val dateIdx = cursor.getColumnIndexOrThrow(COL_DATE)
                val flightTypeIdx = cursor.getColumnIndexOrThrow(COL_FLIGHT_TYPE)
                val classTypeIdx = cursor.getColumnIndexOrThrow(COL_CLASS_TYPE)
                val fromIdx = cursor.getColumnIndexOrThrow(COL_FROM_AIRPORT)
                val toIdx = cursor.getColumnIndexOrThrow(COL_TO_AIRPORT)
                val airlineIdx = cursor.getColumnIndexOrThrow(COL_AIRLINE)
                val flightNumberIdx = cursor.getColumnIndexOrThrow(COL_FLIGHT_NUMBER)
                val aircraftTypeIdx = cursor.getColumnIndexOrThrow(COL_AIRCRAFT_TYPE)
                val registrationIdx = cursor.getColumnIndexOrThrow(COL_REGISTRATION)
                val distanceIdx = cursor.getColumnIndexOrThrow(COL_DISTANCE_KM)
                val minutesIdx = cursor.getColumnIndexOrThrow(COL_FLIGHT_MINUTES)
                val layoverIdx = cursor.getColumnIndexOrThrow(COL_LAYOVER)
                val commentIdx = cursor.getColumnIndexOrThrow(COL_COMMENT)

                while (cursor.moveToNext()) {
                    val entry = cursor.toEntry(
                        idIdx, dateIdx, flightTypeIdx, classTypeIdx,
                        fromIdx, toIdx, airlineIdx, flightNumberIdx,
                        aircraftTypeIdx, registrationIdx, distanceIdx, minutesIdx,
                        layoverIdx, commentIdx
                    )
                    if (entry != null) entries += entry
                }
            }
        }
        return entries
    }

    private fun android.database.Cursor.toEntry(
        idIdx: Int,
        dateIdx: Int,
        flightTypeIdx: Int,
        classTypeIdx: Int,
        fromIdx: Int,
        toIdx: Int,
        airlineIdx: Int,
        flightNumberIdx: Int,
        aircraftTypeIdx: Int,
        registrationIdx: Int,
        distanceIdx: Int,
        minutesIdx: Int,
        layoverIdx: Int,
        commentIdx: Int
    ): LogbookEntry? {
        val date = try {
            LocalDate.parse(getString(dateIdx))
        } catch (e: Exception) {
            return null
        }
        return LogbookEntry(
            id = getLong(idIdx),
            date = date,
            flightType = getString(flightTypeIdx),
            classType = getString(classTypeIdx),
            fromAirport = getString(fromIdx),
            toAirport = getString(toIdx),
            airline = getString(airlineIdx),
            flightNumber = getString(flightNumberIdx),
            aircraftType = getString(aircraftTypeIdx),
            registration = getString(registrationIdx),
            distanceKm = if (isNull(distanceIdx)) null else getInt(distanceIdx),
            flightMinutes = if (isNull(minutesIdx)) null else getInt(minutesIdx),
            layover = getInt(layoverIdx) != 0,
            comment = getString(commentIdx)
        )
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_ENTRIES", null)
            .use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }

    companion object {
        const val DATABASE_NAME = "logbook.db"
        const val DATABASE_VERSION = 3

        const val TABLE_ENTRIES = "entries"
        const val COL_ID = "_id"
        const val COL_DATE = "date"
        const val COL_FLIGHT_TYPE = "flight_type"
        const val COL_CLASS_TYPE = "class_type"
        const val COL_FROM_AIRPORT = "from_airport"
        const val COL_TO_AIRPORT = "to_airport"
        const val COL_AIRLINE = "airline"
        const val COL_FLIGHT_NUMBER = "flight_number"
        const val COL_AIRCRAFT_TYPE = "aircraft_type"
        const val COL_REGISTRATION = "registration"
        const val COL_DISTANCE_KM = "distance_km"
        const val COL_FLIGHT_MINUTES = "flight_minutes"
        const val COL_LAYOVER = "layover"
        const val COL_COMMENT = "comment"
    }
}