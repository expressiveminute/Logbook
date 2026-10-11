package com.highfly.logbook

import android.content.Context
import android.content.SharedPreferences

/**
 * Verwaltet die Nutzer der App.
 *
 * Bis zu [MAX_USERS] Nutzer lassen sich anlegen; jeder hat ein eigenes Logbuch
 * (eigene Datenbank, siehe [LogbookDatabase.databaseNameFor]) und eigene
 * Profileinstellungen (Name, Avatar, Rolle, Fluggesellschaft, Arbeitsposition,
 * selbst abgehakte Länder). Erscheinungsbild und Sprache bleiben dagegen
 * gemeinsam.
 *
 * Die Plätze sind mit 1 bis [MAX_USERS] durchnummeriert. Platz 1 gibt es immer;
 * er behält außerdem die alten Einstellungsschlüssel, damit eine bestehende
 * Installation ihr Profil und ihr Logbuch unverändert weiterbenutzt. Die
 * Auswahl liegt in einer eigenen kleinen Einstellungsdatei, weil sie vor dem
 * Laden der nutzerbezogenen Werte gelesen werden muss.
 */
object Users {

    const val MAX_USERS = 3

    private const val PREF_NAME = "logbook_users"
    private const val KEY_SLOTS = "user_slots"
    private const val KEY_ACTIVE = "active_user"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Vorhandene Nutzerplätze, aufsteigend; immer mindestens Platz 1. */
    fun ids(context: Context): List<Int> {
        val stored = prefs(context).getStringSet(KEY_SLOTS, null)
        val parsed = stored.orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 1..MAX_USERS }
            .toSortedSet()
        if (parsed.isEmpty()) parsed += 1
        return parsed.toList()
    }

    /**
     * Anzeigename eines Nutzerplatzes für Auswahllisten: "Nutzer 2" bzw.
     * mit Profilnamen "Nutzer 2 – Max".
     */
    fun label(context: Context, userId: Int): String {
        val name = Settings.getProfileName(context, userId).trim()
        return if (name.isBlank()) {
            context.getString(R.string.users_default_name, userId)
        } else {
            context.getString(R.string.users_entry_named, userId, name)
        }
    }

    fun count(context: Context): Int = ids(context).size

    fun hasRoom(context: Context): Boolean = ids(context).size < MAX_USERS

    /** Nummer des aktiven Nutzers; fällt auf den ersten Platz zurück. */
    fun activeId(context: Context): Int {
        val stored = prefs(context).getInt(KEY_ACTIVE, 1)
        val available = ids(context)
        return if (stored in available) stored else available.first()
    }

    fun setActiveId(context: Context, id: Int) {
        if (id !in ids(context)) return
        prefs(context).edit().putInt(KEY_ACTIVE, id).apply()
    }

    /**
     * Legt den nächsten freien Platz an und macht ihn aktiv. Liefert die Nummer
     * des neuen Nutzers oder null, wenn alle Plätze belegt sind.
     */
    fun addUser(context: Context): Int? {
        val used = ids(context).toMutableSet()
        val slot = (1..MAX_USERS).firstOrNull { it !in used } ?: return null
        used += slot
        prefs(context).edit()
            .putStringSet(KEY_SLOTS, used.map { it.toString() }.toSet())
            .putInt(KEY_ACTIVE, slot)
            .apply()
        return slot
    }

    /**
     * Entfernt einen angelegten Nutzer. Platz 1 bleibt bestehen. War der
     * gelöschte Nutzer der aktive, wird der kleinste verbliebene Platz aktiv.
     */
    fun removeUser(context: Context, id: Int) {
        if (id <= 1) return
        val remaining = ids(context).filter { it != id }
        val editor = prefs(context).edit()
            .putStringSet(KEY_SLOTS, remaining.map { it.toString() }.toSet())
        if (prefs(context).getInt(KEY_ACTIVE, 1) == id) {
            editor.putInt(KEY_ACTIVE, remaining.minOrNull() ?: 1)
        }
        editor.apply()
    }
}
