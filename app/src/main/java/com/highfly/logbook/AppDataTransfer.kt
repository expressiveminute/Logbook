package com.highfly.logbook

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Vollständiger Export/Import aller App-Daten als JSON. Im Unterschied zum
 * CSV-Export ([LogbookCsv]) landen hier nicht nur die Flugeinträge, sondern
 * alles, was die App speichert: die Flugeinträge aller Nutzer, die
 * Einstellungen (SharedPreferences), das Dashboard-Layout, die Nutzerdaten
 * und die Avatare. Damit lässt sich die App vollständig sichern und auf einem
 * anderen Gerät oder nach einer Neuinstallation wiederherstellen.
 *
 * Wird in der App später eine neue Funktion ergänzt, die eigene Daten
 * speichert, gehört sie hierher: Export und Import laufen zentral über
 * [exportJson] und [importJson], damit nichts vergessen wird. Die
 * Einstellungsdateien werden als Ganzes übernommen ([prefsToJson] /
 * [jsonToPrefs]), damit auch künftige Schlüssel automatisch mitwandern.
 */
object AppDataTransfer {

    /** Kennung des JSON-Formats, damit ein Import Fremddateien ablehnt. */
    private const val FORMAT_KEY = "logbook_export"
    private const val FORMAT_VERSION = 1

    /** Name der Einstellungsdatei, siehe [Settings]. */
    private const val SETTINGS_PREF_NAME = "logbook_settings"

    /** Name der Dashboard-Layout-Datei, siehe [DashboardPrefs]. */
    private const val DASHBOARD_PREF_NAME = "dashboard_layout"

    /** Name der Nutzerdaten-Datei, siehe [Users]. */
    private const val USERS_PREF_NAME = "logbook_users"

    // --- Export -----------------------------------------------------------

    /**
     * Baut das JSON-Dokument mit allen App-Daten. Läuft auf einem
     * Hintergrund-Thread, weil die Datenbanken gelesen werden.
     */
    fun exportJson(context: Context): String {
        val app = context.applicationContext
        val root = JSONObject()
        root.put(FORMAT_KEY, FORMAT_VERSION)

        // Nutzer und ihre Flugeinträge
        val usersArray = JSONArray()
        for (userId in Users.ids(app)) {
            val userObj = JSONObject()
            userObj.put("id", userId)
            val entries = JSONArray()
            for (entry in LogbookRepository.entriesForUser(app, userId)) {
                entries.put(entryToJson(entry))
            }
            userObj.put("entries", entries)
            usersArray.put(userObj)
        }
        root.put("users", usersArray)

        // Aktiver Nutzer
        root.put("activeUserId", Users.activeId(app))

        // Einstellungsdateien als Ganzes: alle Schlüssel wandern mit,
        // auch künftige. Profileinstellungen der Nutzer liegen in derselben
        // Datei (mit Suffix), deshalb sind Name, Avatar, Rolle, Länder usw.
        // automatisch enthalten.
        root.put("settings", prefsToJson(app.getSharedPreferences(SETTINGS_PREF_NAME, Context.MODE_PRIVATE)))
        root.put("dashboard", prefsToJson(app.getSharedPreferences(DASHBOARD_PREF_NAME, Context.MODE_PRIVATE)))
        root.put("usersPrefs", prefsToJson(app.getSharedPreferences(USERS_PREF_NAME, Context.MODE_PRIVATE)))

        // Avatare (als Base64, falls als Datei gespeichert)
        val avatarsArray = JSONArray()
        for (userId in Users.ids(app)) {
            if (Settings.getAvatar(app, userId) == Settings.AVATAR_FILE) {
                val file = File(app.filesDir, ProfileAvatar.fileNameForUser(userId))
                if (file.exists()) {
                    val bytes = file.readBytes()
                    avatarsArray.put(JSONObject().apply {
                        put("userId", userId)
                        put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    })
                }
            }
        }
        root.put("avatars", avatarsArray)

        return root.toString(2)
    }

    private fun entryToJson(entry: LogbookEntry): JSONObject = JSONObject().apply {
        put("date", entry.date.toString())
        put("flightType", entry.flightType)
        put("classType", entry.classType)
        put("seat", entry.seat)
        put("fromAirport", entry.fromAirport)
        put("toAirport", entry.toAirport)
        put("airline", entry.airline)
        put("flightNumber", entry.flightNumber)
        put("aircraftType", entry.aircraftType)
        put("registration", entry.registration)
        put("distanceKm", entry.distanceKm)
        put("flightMinutes", entry.flightMinutes)
        put("layover", entry.layover)
        put("layoverHours", entry.layoverHours)
        put("fromCountry", entry.fromCountry)
        put("toCountry", entry.toCountry)
        put("function", entry.function)
        put("travelBuddy", entry.travelBuddy)
        put("comment", entry.comment)
    }

    private fun prefsToJson(prefs: android.content.SharedPreferences): JSONObject {
        val obj = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is String -> obj.put(key, value)
                is Int -> obj.put(key, value)
                is Long -> obj.put(key, value)
                is Float -> obj.put(key, value.toDouble())
                is Boolean -> obj.put(key, value)
                is Set<*> -> obj.put(key, JSONArray(value.map { it.toString() }))
                else -> obj.put(key, value.toString())
            }
        }
        return obj
    }

    // --- Import -----------------------------------------------------------

    /**
     * Stellt alle App-Daten aus einem JSON-Dokument wieder her. Überschreibt
     * die Datenbanken aller Nutzer und die Einstellungen. Läuft auf einem
     * Hintergrund-Thread.
     *
     * @return true bei Erfolg, false wenn die Datei kein gültiges
     *   Logbook-Exportformat ist.
     */
    fun importJson(context: Context, json: String): Boolean {
        val app = context.applicationContext
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return false
        }
        if (root.optInt(FORMAT_KEY, 0) != FORMAT_VERSION) return false
        // Vor dem Überschreiben prüfen, dass das Dokument vollständig ist -
        // sonst gingen beim Leeren alle Einstellungen verloren.
        val usersArray = root.optJSONArray("users") ?: return false

        // Einstellungsdateien leeren und aus dem Dokument übernehmen. Die
        // Nutzerdaten (Slots, aktiver Nutzer) kommen aus "usersPrefs", danach
        // stehen die Nutzerplätze bereit.
        val settingsPrefs = app.getSharedPreferences(SETTINGS_PREF_NAME, Context.MODE_PRIVATE)
        val dashboardPrefs = app.getSharedPreferences(DASHBOARD_PREF_NAME, Context.MODE_PRIVATE)
        val usersPrefs = app.getSharedPreferences(USERS_PREF_NAME, Context.MODE_PRIVATE)

        settingsPrefs.edit().clear().apply()
        dashboardPrefs.edit().clear().apply()
        usersPrefs.edit().clear().apply()

        jsonToPrefs(root.optJSONObject("usersPrefs"), usersPrefs)
        jsonToPrefs(root.optJSONObject("settings"), settingsPrefs)
        jsonToPrefs(root.optJSONObject("dashboard"), dashboardPrefs)

        // Aktiven Nutzer setzen, falls im Dokument vorhanden und gültig
        val userIds = mutableListOf<Int>()
        for (i in 0 until usersArray.length()) {
            val userObj = usersArray.optJSONObject(i) ?: continue
            val userId = userObj.optInt("id", 0)
            if (userId > 0) userIds.add(userId)
        }
        val activeUserId = root.optInt("activeUserId", 1)
        if (activeUserId in userIds) {
            Users.setActiveId(app, activeUserId)
        } else if (userIds.isNotEmpty()) {
            Users.setActiveId(app, userIds.first())
        }

        // Datenbank des aktiven Nutzers der Datei öffnen, BEVOR die Einträge
        // geschrieben werden: replaceAllForUser schreibt den aktiven Nutzer
        // sonst in die Datenbank des bisher aktiven Nutzers.
        LogbookRepository.switchUser(app)

        // Einträge je Nutzer in dessen Datenbank schreiben
        for (i in 0 until usersArray.length()) {
            val userObj = usersArray.optJSONObject(i) ?: continue
            val userId = userObj.optInt("id", 0)
            if (userId <= 0) continue
            val entriesArray = userObj.optJSONArray("entries") ?: continue
            val entries = mutableListOf<LogbookEntry>()
            for (j in 0 until entriesArray.length()) {
                val entryObj = entriesArray.optJSONObject(j) ?: continue
                val entry = jsonToEntry(entryObj) ?: continue
                entries.add(entry)
            }
            LogbookRepository.replaceAllForUser(app, userId, entries)
        }

        // Avatare wiederherstellen
        val avatarsArray = root.optJSONArray("avatars")
        if (avatarsArray != null) {
            for (i in 0 until avatarsArray.length()) {
                val avatarObj = avatarsArray.optJSONObject(i) ?: continue
                val userId = avatarObj.optInt("userId", 0)
                val data = avatarObj.optString("data", "")
                if (userId <= 0 || data.isEmpty()) continue
                try {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    File(app.filesDir, ProfileAvatar.fileNameForUser(userId)).writeBytes(bytes)
                } catch (e: Exception) {
                    // Avatar konnte nicht wiederhergestellt werden - nicht kritisch
                }
            }
        }

        // Caches invalidieren, damit die App die neuen Daten sofort sieht
        LogbookRepository.invalidate()
        return true
    }

    private fun jsonToPrefs(obj: JSONObject?, prefs: android.content.SharedPreferences) {
        if (obj == null) return
        val editor = prefs.edit()
        for (key in obj.keys()) {
            when (val value = obj.opt(key)) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> {
                    // Float-Werte (z. B. tile_scale_v1) werden als Double
                    // serialisiert und hier als Float zurückgeschrieben.
                    if (value == value.toFloat().toDouble()) {
                        editor.putFloat(key, value.toFloat())
                    } else {
                        editor.putLong(key, value.toLong())
                    }
                }
                is Boolean -> editor.putBoolean(key, value)
                is JSONArray -> {
                    val set = (0 until value.length()).map { value.optString(it) }.toSet()
                    editor.putStringSet(key, set)
                }
                else -> editor.putString(key, value?.toString().orEmpty())
            }
        }
        editor.apply()
    }

    private fun jsonToEntry(obj: JSONObject): LogbookEntry? {
        val dateStr = obj.optString("date", "")
        val date = try {
            LocalDate.parse(dateStr)
        } catch (e: Exception) {
            return null
        }
        return LogbookEntry(
            id = null,
            date = date,
            flightType = obj.optString("flightType").ifEmpty { null },
            classType = obj.optString("classType").ifEmpty { null },
            seat = obj.optString("seat").ifEmpty { null },
            fromAirport = obj.optString("fromAirport", ""),
            toAirport = obj.optString("toAirport", ""),
            airline = obj.optString("airline").ifEmpty { null },
            flightNumber = obj.optString("flightNumber").ifEmpty { null },
            aircraftType = obj.optString("aircraftType").ifEmpty { null },
            registration = obj.optString("registration").ifEmpty { null },
            distanceKm = obj.optInt("distanceKm").takeIf { obj.has("distanceKm") && !obj.isNull("distanceKm") },
            flightMinutes = obj.optInt("flightMinutes").takeIf { obj.has("flightMinutes") && !obj.isNull("flightMinutes") },
            layover = obj.optBoolean("layover", false),
            layoverHours = obj.optInt("layoverHours").takeIf { obj.has("layoverHours") && !obj.isNull("layoverHours") },
            fromCountry = obj.optString("fromCountry").ifEmpty { null },
            toCountry = obj.optString("toCountry").ifEmpty { null },
            function = obj.optString("function").ifEmpty { null },
            travelBuddy = obj.optString("travelBuddy").ifEmpty { null },
            comment = obj.optString("comment").ifEmpty { null }
        )
    }
}
