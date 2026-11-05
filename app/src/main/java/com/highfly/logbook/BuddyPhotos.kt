package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * Ablage der Reisebuddy-Fotos. Jeder Buddy kann auf seiner Detailseite ein
 * Foto aus der Galerie bekommen (zugeschnitten auf einen Kreis, siehe
 * [BuddyPhotoCropView]); gespeichert wird es als quadratisches JPEG im
 * internen Speicher unter `filesDir/buddy_photos/`.
 *
 * Der Dateiname hängt vom Nutzerschlüssel des Namens ab ([keyOf]): dieselbe
 * Normalisierung wie in [TravelBuddyStats] (Leerraum kollabieren, klein
 * schreiben), damit "Anna" und "anna" dasselbe Foto bekommen. Vorangestellt
 * ist die Nutzer-ID, denn das Logbuch ist pro Nutzer getrennt - wie beim
 * Profil-Avatar ([ProfileAvatar]). Beim Umbenennen eines Buddys wird die
 * Datei mit umbenannt ([rename]).
 *
 * Export/Import ([AppDataTransfer]) und Zurücksetzen ([AppReset]) laufen über
 * dieselben Dateien.
 */
object BuddyPhotos {

    /** Ordner unter filesDir mit allen Buddy-Fotos. */
    const val DIR_NAME = "buddy_photos"

    /** Kantenlänge, auf die ein gespeichertes Foto skaliert wird. */
    const val OUTPUT_SIZE = 512

    private val WHITESPACE = Regex("\\s+")

    /**
     * Normalisierung eines Buddy-Namens für den Datei-Schlüssel - dieselbe
     * wie in [TravelBuddyStats.parse]: trimmen, Leerraumfolge zu einem Leer-
     * zeichen, kleinschreiben.
     */
    fun normalize(name: String): String =
        name.trim().replace(WHITESPACE, " ").trim().lowercase()

    /**
     * Stabiler Schlüssel eines Buddy-Namens (16 Hex-Zeichen des SHA-256 der
     * normalisierten Form). Reine Funktion, damit [BuddyPhotosTest] sie ohne
     * Android prüfen kann.
     */
    fun keyOf(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalize(name).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Dateiname des Fotos eines Buddys bei einem Nutzerplatz. */
    fun fileNameForUser(userId: Int, name: String): String =
        "${userId}_${keyOf(name)}.jpg"

    /** Datei des Fotos eines Buddys bei einem Nutzerplatz. */
    fun fileFor(context: Context, userId: Int, name: String): File =
        File(File(context.filesDir, DIR_NAME), fileNameForUser(userId, name))

    /** Datei des Fotos eines Buddys des aktiven Nutzers. */
    fun fileForActive(context: Context, name: String): File =
        fileFor(context, Users.activeId(context), name)

    /** Ob für den Buddy des aktiven Nutzers ein Foto gespeichert ist. */
    fun hasPhoto(context: Context, name: String): Boolean =
        fileForActive(context, name).exists()

    /** Lädt das Foto des Buddys des aktiven Nutzers; null, wenn es keines gibt. */
    fun load(context: Context, name: String): Bitmap? =
        load(context, Users.activeId(context), name)

    /** Wie [load], aber für einen bestimmten Nutzerplatz. */
    fun load(context: Context, userId: Int, name: String): Bitmap? {
        val file = fileFor(context, userId, name)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Speichert das (bereits zugeschnittene) Foto des Buddys des aktiven
     * Nutzers. Das Bild wird auf [OUTPUT_SIZE] skaliert und als JPEG written.
     */
    fun save(context: Context, name: String, bitmap: Bitmap) {
        val scaled = if (bitmap.width == OUTPUT_SIZE && bitmap.height == OUTPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, OUTPUT_SIZE, OUTPUT_SIZE, true)
        }
        val file = fileForActive(context, name)
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        if (scaled != bitmap) scaled.recycle()
    }

    /** Benennt die Foto-Datei mit, wenn der Buddy umbenannt wird. */
    fun rename(context: Context, oldName: String, newName: String) {
        val userId = Users.activeId(context)
        val oldFile = fileFor(context, userId, oldName)
        val newFile = fileFor(context, userId, newName)
        if (oldFile.exists() && oldFile != newFile) {
            newFile.parentFile?.mkdirs()
            oldFile.renameTo(newFile)
        }
    }

    /** Löscht das Foto des Buddys des aktiven Nutzers, falls vorhanden. */
    fun delete(context: Context, name: String) {
        fileForActive(context, name).delete()
    }

    /** Löscht alle Buddy-Fotos eines Nutzerplatzes (z. B. beim Entfernen). */
    fun clearForUser(context: Context, userId: Int) {
        val dir = File(context.filesDir, DIR_NAME)
        val prefix = "${userId}_"
        dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    /** Löscht den gesamten Foto-Ordner (Werksreset). */
    fun clearAll(context: Context) {
        File(context.filesDir, DIR_NAME).deleteRecursively()
    }
}
