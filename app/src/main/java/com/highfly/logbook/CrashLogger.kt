package com.highfly.logbook

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CrashLogger {

    private const val LOG_FILE_NAME = "crash_log.log"
    private const val TAG = "LogbookCrashLogger"
    private const val MAX_LOG_LINES = 500

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    private var logDir: File? = null
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        if (logDir != null) return
        logDir = context.applicationContext.filesDir
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (originalHandler !is CrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler())
        }
    }

    fun logFile(): File? =
        logDir?.let { File(it, LOG_FILE_NAME) }

    fun exists(): Boolean =
        logFile()?.exists() == true && (logFile()?.length() ?: 0L) > 0L

    private fun append(line: String) {
        val file = logFile() ?: return
        try {
            file.appendText(line + System.lineSeparator())
        } catch (_: IOException) {
        }
    }

    private fun appendTitle(title: String) {
        val now = LocalDateTime.now().format(timeFormatter)
        append("=".repeat(72))
        append("[$now] $title")
        append("=".repeat(72))
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        appendTitle("FEHLER: $tag")
        if (throwable != null) {
            append(Log.getStackTraceString(throwable))
        } else {
            append(message)
        }
    }

    fun logInfo(tag: String, message: String) {
        appendTitle("INFO: $tag: $message")
    }

    fun currentContent(context: Context): String {
        val sb = StringBuilder()
        appendMetadata(sb, context)
        sb.append(System.lineSeparator())
        sb.append(LocalDateTime.now().format(timeFormatter))
            .append(" Logcat-Auszug (dieser Prozess):")
            .append(System.lineSeparator())
        sb.append(dumpLogcat())
        sb.append(System.lineSeparator())
        val file = logFile()
        if (file != null && file.exists()) {
            sb.append("=== Crash-/Fehlerdatei (${
                file.length() / 1024
            } KB, letzte Zeilen) ===")
                .append(System.lineSeparator())
            try {
                val lines = file.readLines()
                lines.takeLast(MAX_LOG_LINES).forEach { sb.append(it).append(System.lineSeparator()) }
            } catch (_: IOException) {
            }
        } else {
            sb.append("=== Crash-/Fehlerdatei ===")
                .append(System.lineSeparator())
                .append("Bisher keine Einträge.")
                .append(System.lineSeparator())
        }
        return sb.toString()
    }

    private fun appendMetadata(sb: StringBuilder, context: Context) {
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        sb.append("=== Logbook Crash-Log ===")
            .append(System.lineSeparator())
            .append("Erzeugt: ")
            .append(LocalDateTime.now().format(timeFormatter))
            .append(System.lineSeparator())
            .append("App-Version: ")
            .append(packageInfo?.versionName ?: "?")
            .append(" (")
            .append(packageInfo?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0L)
            .append(")")
            .append(System.lineSeparator())
            .append("Gerät: ")
            .append(Build.MANUFACTURER)
            .append(" ")
            .append(Build.MODEL)
            .append(System.lineSeparator())
            .append("Android: ")
            .append(Build.VERSION.RELEASE)
            .append(" (API ")
            .append(Build.VERSION.SDK_INT)
            .append(")")
            .append(System.lineSeparator())
            .append("Sprache: ")
            .append(java.util.Locale.getDefault().toLanguageTag())
            .append(System.lineSeparator())
    }

    private fun dumpLogcat(): String {
        return try {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "-v",
                "time",
                "--pid=${Process.myPid()}",
                "*:E"
            ).redirectErrorStream(true).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val out = reader.useLines { lines -> lines.take(MAX_LOG_LINES).joinToString(System.lineSeparator()) }
            process.waitFor()
            if (out.isNullOrEmpty()) "(keine Einträge)" else out
        } catch (_: Throwable) {
            "(logcat nicht verfügbar)"
        }
    }

    private class CrashHandler : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            appendTitle("ABSTURZ (unbehandelter Fehler)")
            append("Thread: ${t.name}")
            append(Log.getStackTraceString(e))
            Log.e(TAG, "Uncaught exception in thread ${t.name}", e)
            originalHandler?.uncaughtException(t, e)
        }
    }
}