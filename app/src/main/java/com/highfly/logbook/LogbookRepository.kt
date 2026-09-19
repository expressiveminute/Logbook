package com.highfly.logbook

import android.content.Context

object LogbookRepository {

    private const val DEMO_DATABASE_NAME = "logbook_demo.db"

    private var db: LogbookDatabase? = null
    private var demoDb: LogbookDatabase? = null
    private var appContext: Context? = null
    private var cache: List<LogbookEntry>? = null

    fun init(context: Context) {
        if (db != null) return
        appContext = context.applicationContext
        if (!Settings.isDemoMigrationDone(context.applicationContext)) {
            Settings.setDemoDataEnabled(context.applicationContext, false)
            Settings.setDemoMigrationDone(context.applicationContext)
        }
        db = LogbookDatabase(context)
        demoDb = LogbookDatabase(context, DEMO_DATABASE_NAME)
        if (demoDb?.count() == 0) {
            demoDb?.replaceAll(DemoData.entries())
        }
        Thread {
            try {
                val entries = activeDb()?.getAll()?.toList() ?: return@Thread
                val changed = mutableListOf<LogbookEntry>()
                for (e in entries) {
                    val fc = e.fromCountry?.takeIf { it.isNotBlank() }
                        ?: AirportData.country(appContext ?: context, e.fromAirport)
                    val tc = e.toCountry?.takeIf { it.isNotBlank() }
                        ?: AirportData.country(appContext ?: context, e.toAirport)
                    if (fc != e.fromCountry || tc != e.toCountry) {
                        changed += e.copy(fromCountry = fc, toCountry = tc)
                    }
                }
                changed.forEach { activeDb()?.update(it) }
            } catch (e: Exception) {
                // Fehlende Länder-Rückmeldung ist nicht kritisch
            }
        }.start()
    }

    private fun activeDb(): LogbookDatabase? =
        if (appContext?.let { Settings.isDemoDataEnabled(it) } == true) demoDb ?: db else db

    @Synchronized
    fun addEntry(entry: LogbookEntry) {
        activeDb()?.insert(entry)
        invalidate()
    }

    @Synchronized
    fun addEntries(newEntries: List<LogbookEntry>) {
        newEntries.forEach { activeDb()?.insert(it) }
        invalidate()
    }

    @Synchronized
    fun getEntries(): List<LogbookEntry> {
        cache?.let { return it }
        val fresh = activeDb()?.getAll()?.sortedByDescending { it.date } ?: emptyList()
        cache = fresh
        return fresh
    }

    fun getEntry(id: Long): LogbookEntry? = activeDb()?.getEntry(id)

    @Synchronized
    fun updateEntry(entry: LogbookEntry) {
        activeDb()?.update(entry)
        invalidate()
    }

    @Synchronized
    fun deleteEntry(id: Long) {
        activeDb()?.delete(id)
        invalidate()
    }

    @Synchronized
    fun replaceAll(entries: List<LogbookEntry>) {
        activeDb()?.replaceAll(entries)
        invalidate()
    }

    @Synchronized
    fun invalidate() {
        cache = null
    }

    fun getYears(): List<Int> =
        getEntries().map { it.date.year }.distinct().sortedDescending()
}