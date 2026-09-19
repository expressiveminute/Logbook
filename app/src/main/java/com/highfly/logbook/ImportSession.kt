package com.highfly.logbook

enum class ImportMode {
    OVERWRITE,
    APPEND
}

object ImportSession {

    var mode: ImportMode? = null
    val entries: MutableList<LogbookEntry> = mutableListOf()
    val conflictIndexes: MutableList<Int> = mutableListOf()
    var loadedCount: Int = 0
    var processed: Boolean = false

    fun reset() {
        mode = null
        entries.clear()
        conflictIndexes.clear()
        loadedCount = 0
        processed = false
    }

    fun remainingConflicts(): Int = conflictIndexes.size
}