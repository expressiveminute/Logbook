package com.highfly.logbook

import android.content.Context

object LogbookRepository {

    private const val DEMO_DATABASE_NAME = "logbook_demo.db"

    private var db: LogbookDatabase? = null
    private var demoDb: LogbookDatabase? = null
    private var appContext: Context? = null
    private var cache: List<LogbookEntry>? = null

    /** Nutzerplatz, für den [db] gerade geöffnet ist. */
    private var openUserId: Int = 1

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        if (!Settings.isDemoMigrationDone(app)) {
            Settings.setDemoDataEnabled(app, false)
            Settings.setDemoMigrationDone(app)
        }
        demoDb = LogbookDatabase(app, DEMO_DATABASE_NAME)
        // Die Demodatenbank wird neu aufgebaut, wenn sie leer ist oder der
        // Datensatz inzwischen in einer anderen Fassung vorliegt. Sonst bliebe
        // nach einem Update der alte, beim ersten Start angelegte Bestand
        // stehen - ein neuer Datensatz kaeme nie an.
        if (demoDb?.count() == 0 ||
            Settings.getDemoDataVersion(app) != DemoData.VERSION
        ) {
            demoDb?.replaceAll(DemoData.entries(app))
            Settings.setDemoDataVersion(app, DemoData.VERSION)
        }
        openUserDatabase(app)
        enrichCountriesAsync(app)
    }

    /**
     * Oeffnet die Datenbank des aktiven Nutzers. Beim Wechsel wird die vorige
     * geschlossen und der Zwischenspeicher verworfen, damit wirklich das Logbuch
     * des neuen Nutzers angezeigt wird.
     */
    @Synchronized
    fun switchUser(context: Context) {
        val app = context.applicationContext
        openUserDatabase(app)
        enrichCountriesAsync(app)
    }

    @Synchronized
    private fun openUserDatabase(app: Context) {
        val id = Users.activeId(app)
        if (db != null && openUserId == id) return
        db?.close()
        db = LogbookDatabase(app, LogbookDatabase.databaseNameFor(id))
        openUserId = id
        cache = null
        // Die Homebase des vorigen Nutzers darf nicht stehen bleiben.
        Homebase.update(emptyList())
    }

    @Synchronized
    private fun ensureOpen(app: Context) {
        if (db == null) openUserDatabase(app)
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
        // Homebase zentral mitführen: meistgenutzter Flughafen des Logbuchs.
        Homebase.update(fresh)
        return fresh
    }

    fun getEntry(id: Long): LogbookEntry? = activeDb()?.getEntry(id)

    /**
     * Eintraege bis einschliesslich heute. Das ist die Grundlage jeder
     * Auswertung: Ein Flug, der erst noch bevorsteht, steht unter "Upcoming"
     * und zaehlt erst ab seinem Tag mit.
     */
    fun getFlownEntries(): List<LogbookEntry> = UpcomingEntries.flown(getEntries())

    /** Geplante Fluege mit Datum in der Zukunft, der naechste zuerst. */
    fun getUpcomingEntries(): List<LogbookEntry> = UpcomingEntries.upcoming(getEntries())

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

    /**
     * Benennt einen Reisebuddy in jedem Eintrag um. Der Name steht als
     * Freitext im Feld "Reisebuddy", mehrere Namen durch Komma getrennt; nur
     * der passende Name wird ersetzt, die übrigen bleiben, siehe
     * [TravelBuddyStats.rename]. So muss ein Namenswechsel nicht in jedem Flug
     * einzeln nachgetragen werden.
     */
    @Synchronized
    fun renameTravelBuddy(oldName: String, newName: String) {
        val db = activeDb() ?: return
        db.getAll().forEach { entry ->
            val renamed = TravelBuddyStats.rename(entry.travelBuddy, oldName, newName)
            if (renamed != null && renamed != entry.travelBuddy) {
                db.update(entry.copy(travelBuddy = renamed))
            }
        }
        invalidate()
    }

    @Synchronized
    fun replaceAll(entries: List<LogbookEntry>) {
        activeDb()?.replaceAll(entries)
        invalidate()
    }

    /**
     * Liest die Eintraege eines bestimmten Nutzers aus seinem echten Logbuch.
     * Fuer Import/Export: auch ein Nutzer, der gerade nicht aktiv ist, laesst
     * sich so auslesen, ohne dass der aktive Nutzer gewechselt wird. Bei
     * aktivierten Demodaten gilt das echte Logbuch, nicht die Demobank.
     */
    @Synchronized
    fun entriesForUser(context: Context, userId: Int): List<LogbookEntry> {
        val app = context.applicationContext
        ensureOpen(app)
        if (userId == openUserId) {
            return db?.getAll()?.sortedByDescending { it.date } ?: emptyList()
        }
        val tmp = LogbookDatabase(app, LogbookDatabase.databaseNameFor(userId))
        return try {
            tmp.getAll().sortedByDescending { it.date }
        } finally {
            tmp.close()
        }
    }

    /** Ersetzt alle Eintraege eines bestimmten Nutzers (Import, Überschreiben). */
    @Synchronized
    fun replaceAllForUser(context: Context, userId: Int, entries: List<LogbookEntry>) {
        val app = context.applicationContext
        ensureOpen(app)
        if (userId == openUserId) {
            db?.replaceAll(entries)
            invalidate()
        } else {
            val tmp = LogbookDatabase(app, LogbookDatabase.databaseNameFor(userId))
            try {
                tmp.replaceAll(entries)
            } finally {
                tmp.close()
            }
        }
    }

    /** Fuegt Eintraege zum Logbuch eines bestimmten Nutzers hinzu (Import, Ergänzen). */
    @Synchronized
    fun addEntriesForUser(context: Context, userId: Int, entries: List<LogbookEntry>) {
        val app = context.applicationContext
        ensureOpen(app)
        if (userId == openUserId) {
            entries.forEach { db?.insert(it) }
            invalidate()
        } else {
            val tmp = LogbookDatabase(app, LogbookDatabase.databaseNameFor(userId))
            try {
                entries.forEach { tmp.insert(it) }
            } finally {
                tmp.close()
            }
        }
    }

    /**
     * Leert das echte Logbuch des aktiven Nutzers (nicht die Demodatenbank).
     * Fuer die Danger Zone "Daten dieses Nutzers loeschen".
     */
    @Synchronized
    fun clearActiveUserData(context: Context) {
        val app = context.applicationContext
        ensureOpen(app)
        db?.replaceAll(emptyList())
        invalidate()
    }

    /**
     * Schliesst alle Verbindungen, damit ein Werksreset die Datenbankdateien
     * loeschen kann. [init] oeffnet danach alles neu.
     */
    @Synchronized
    fun close() {
        db?.close()
        db = null
        demoDb?.close()
        demoDb = null
        appContext = null
        cache = null
        openUserId = 1
        Homebase.update(emptyList())
    }

    @Synchronized
    fun invalidate() {
        cache = null
    }

    private fun enrichCountriesAsync(context: Context) {
        Thread {
            try {
                val entries = activeDb()?.getAll()?.toList() ?: return@Thread
                val changed = mutableListOf<LogbookEntry>()
                for (e in entries) {
                    val fc = e.fromCountry?.takeIf { it.isNotBlank() }
                        ?: AirportData.country(context, e.fromAirport)
                    val tc = e.toCountry?.takeIf { it.isNotBlank() }
                        ?: AirportData.country(context, e.toAirport)
                    if (fc != e.fromCountry || tc != e.toCountry) {
                        changed += e.copy(fromCountry = fc, toCountry = tc)
                    }
                }
                if (changed.isNotEmpty()) {
                    changed.forEach { activeDb()?.update(it) }
                    invalidate()
                }
            } catch (e: Exception) {
                // Fehlende Länder-Rückmeldung ist nicht kritisch
            }
        }.start()
    }

    fun getYears(): List<Int> =
        getFlownEntries().map { it.date.year }.distinct().sortedDescending()
}