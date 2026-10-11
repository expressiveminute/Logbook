package com.highfly.logbook

import android.content.Context
import java.io.File

/**
 * Die beiden Zerstörungs-Aktionen der Danger Zone in den Einstellungen.
 *
 * Beides wird erst ausgeführt, nachdem der Nutzer die erforderliche
 * Passphrase in einem Dialog eingegeben hat:
 *
 * - [clearActiveUser]: löscht nur das Logbuch und das Profil des aktiven
 *   Nutzers, der Platz selbst bleibt bestehen.
 * - [factoryReset]: setzt die App auf den Werkszustand zurück - alle Nutzer,
 *   Datenbanken, Einstellungen und hochgeladenen Fotos sind danach weg und
 *   die Willkommens-Tour erscheint beim nächsten Start wieder.
 */
object AppReset {

    /** Löscht alle Daten und Profileinstellungen des aktiven Nutzers. */
    fun clearActiveUser(context: Context) {
        LogbookRepository.clearActiveUserData(context)
        Settings.clearActiveUserProfile(context)
    }

    /**
     * Setzt die App vollständig zurück. Die Verbindungen werden zuerst
     * geschlossen, sonst kann das System die Datenbankdateien nicht löschen.
     * Anschließend wird das Repository neu aufgebaut, damit der nächste Start
     * (Willkommens-Tour) sofort funktioniert.
     */
    fun factoryReset(context: Context) {
        val app = context.applicationContext
        LogbookRepository.close()
        for (id in 1..Users.MAX_USERS) {
            app.deleteDatabase(LogbookDatabase.databaseNameFor(id))
        }
        app.deleteDatabase("logbook_demo.db")
        listOf("logbook_settings", "dashboard_layout", "logbook_users")
            .forEach { name ->
                app.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
        for (id in 1..Users.MAX_USERS) {
            File(app.filesDir, ProfileAvatar.fileNameForUser(id)).delete()
        }
        CrashLogger.clear()
        LogbookRepository.init(app)
    }

    /**
     * Entfernt einen angelegten Nutzer vollständig: Datenbank, Profil und
     * Foto. War er aktiv, wechselt die App auf den nächsten Platz.
     */
    fun removeUser(context: Context, userId: Int) {
        if (userId <= 1) return
        val app = context.applicationContext
        val wasActive = Users.activeId(app) == userId
        Users.removeUser(app, userId)
        if (wasActive) {
            // Schließt die noch offene Verbindung des entfernten Nutzers und
            // öffnet den neuen aktiven Platz.
            LogbookRepository.switchUser(app)
        }
        app.deleteDatabase(LogbookDatabase.databaseNameFor(userId))
        Settings.clearProfileForUser(app, userId)
        File(app.filesDir, ProfileAvatar.fileNameForUser(userId)).delete()
    }
}