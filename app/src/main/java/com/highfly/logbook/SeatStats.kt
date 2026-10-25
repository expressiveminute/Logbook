package com.highfly.logbook

/**
 * Auswertung der erfassten Sitzplätze. Ohne Android-Abhängigkeiten, damit
 * das Parsen und die Position im Flugzeug im Test geprüft werden können.
 *
 * Ein Sitzplatz besteht aus Reihe (Zahl) und Buchstabe (A, B, C, ...).
 * A ist immer links am Fenster. Bei Kurzstreckenflugzeugen (3+3-Bestuhlung)
 * ist F rechts am Fenster, bei Langstreckenflugzeugen (3+4+3) ist K rechts
 * am Fenster. Welches Layout gilt, bestimmt [AircraftLayout.resolve] anhand
 * des Flugzeugtyps im Eintrag.
 *
 * Die Position wird als Bruchteil von vorne (0) bis hinten (1) angegeben:
 * Je kleiner die Reihe, desto weiter vorne. Innerhalb der Reihe zählt der
 * Buchstabe von links (A) nach rechts.
 *
 * Hat ein Eintrag eine Reiseklasse, die laut Layout nicht zur Reihe passt
 * (z. B. Reihe 20A in Business auf einer A320 mit Business Reihe 1–6), wird
 * die Position trotzdem anhand der tatsächlichen Reihe berechnet – die Heatmap
 * platziert den Punkt dann innerhalb des Klassenbereichs, den der Eintrag nennt.
 */
object SeatStats {

    /** Ein geparster Sitzplatz. */
    data class Seat(
        val row: Int,
        val letter: Char,
        /** 0 = ganz vorne, 1 = ganz hinten (geschätzt aus der Reihe). */
        val frontFraction: Float,
        /** 0 = ganz links (Fenster), 1 = ganz rechts (Fenster). */
        val sideFraction: Float,
        /** true, wenn der Platz am Fenster sitzt (A oder rechter Fensterplatz). */
        val isWindow: Boolean,
        /** true, wenn der Platz am Gang sitzt. */
        val isAisle: Boolean
    )

    /**
     * Heatmap-Daten zu einem Sitzplatz: Häufigkeit und Reiseklasse.
     * Die Klasse kommt aus dem Eintrag ([LogbookEntry.classType]) und bestimmt,
     * in welchem Kabinenbereich die Heatmap-Fläche gezeichnet wird.
     */
    data class SeatHeatData(
        val count: Int,
        /** Kanonischer Klassenname (z. B. "Economy", "Business"), oder null. */
        val className: String?
    )

    /** Ergebnis der Auswertung aller Sitzplätze. */
    data class Summary(
        /** Anzahl der Einträge mit Sitzplatz. */
        val total: Int,
        /** Durchschnittliche Position von vorne (0..1). */
        val avgFrontFraction: Float,
        /** Durchschnittliche Position von links (0..1). */
        val avgSideFraction: Float,
        /** Anteil Fensterplätze (0..1). */
        val windowFraction: Float,
        /** Anteil Gangplätze (0..1). */
        val aisleFraction: Float,
        /** Häufigste Reihe. */
        val mostCommonRow: Int?,
        /** Häufigster Buchstabe. */
        val mostCommonLetter: Char?,
        /** Alle geparsten Sitzplätze mit Häufigkeit und Klasse. */
        val seatHeat: Map<String, SeatHeatData>
    )

    /**
     * Parst einen Sitzplatz-String wie "1A", "24D" oder "12F".
     * Gibt null zurück, wenn das Format nicht erkannt wird.
     *
     * @param layout Optionales Kabinenlayout. Bestimmt die Gesamtzahl der
     *   Reihen (für [Seat.frontFraction]) und den rechten Fensterplatz
     *   (für [Seat.sideFraction] und [Seat.isWindow]). Ohne Layout werden
     *   40 Reihen und K als rechter Fensterplatz angenommen.
     */
    fun parse(seat: String?, layout: AircraftLayout.Layout = AircraftLayout.DEFAULT): Seat? {
        val trimmed = seat?.trim()?.uppercase() ?: return null
        if (trimmed.length < 2 || trimmed.length > 4) return null
        // Format: Zahl gefolgt von Buchstabe, z. B. "1A", "24D", "12F"
        val rowEnd = trimmed.indexOfFirst { it.isLetter() }
        if (rowEnd <= 0) return null
        val row = trimmed.substring(0, rowEnd).toIntOrNull() ?: return null
        if (row <= 0 || row > 100) return null
        val letter = trimmed.substring(rowEnd)
        if (letter.length != 1 || !letter[0].isLetter()) return null
        val letterChar = letter[0]

        // Rechter Fensterplatz hängt vom Layout ab (F bei 3+3, K bei 3+4+3).
        val maxLetter = layout.rightWindowLetter
        val letterRange = (maxLetter - 'A').coerceAtLeast(1)
        val sideFraction = (letterChar - 'A').toFloat() / letterRange
        val isWindow = letterChar == 'A' || letterChar == maxLetter

        // Gangplätze: C und letzter Buchstabe der linken Seite (bei 3+3: C,
        // bei 3+4+3: C und G), sowie erster und letzter Buchstabe der
        // rechten Seite (bei 3+3: D, bei 3+4+3: H und K – K ist aber Fenster).
        // Allgemein: die Buchstaben direkt neben den Gängen.
        val aisleLetters = mutableSetOf<Char>()
        for (aisleIdx in layout.aisleAfter) {
            // Buchstabe vor dem Gang (linke Seite des Gangs)
            if (aisleIdx >= 0 && aisleIdx < layout.seatLetters.size) {
                aisleLetters.add(layout.seatLetters[aisleIdx])
            }
            // Buchstabe nach dem Gang (rechte Seite des Gangs)
            if (aisleIdx + 1 < layout.seatLetters.size) {
                aisleLetters.add(layout.seatLetters[aisleIdx + 1])
            }
        }
        val isAisle = letterChar in aisleLetters && !isWindow

        // Front: Reihe 1 ist vorne, letzte Reihe hinten.
        val frontFraction = (row - 1).toFloat() / (layout.totalRows - 1).coerceAtLeast(1)

        return Seat(
            row = row,
            letter = letterChar,
            frontFraction = frontFraction.coerceIn(0f, 1f),
            sideFraction = sideFraction.coerceIn(0f, 1f),
            isWindow = isWindow,
            isAisle = isAisle
        )
    }

    /**
     * Wertet alle Sitzplätze aus den Einträgen aus. Einträge ohne Sitzplatz
     * oder mit ungültigem Format werden übersprungen.
     *
     * @param layout Das Kabinenlayout, das für die Positionen verwendet wird.
     *   Wird üblicherweise über [AircraftLayout.mostCommonLayout] aus den
     *   Einträgen ermittelt.
     */
    fun summarize(
        entries: List<LogbookEntry>,
        layout: AircraftLayout.Layout = AircraftLayout.DEFAULT
    ): Summary {
        val seats = entries.mapNotNull { parse(it.seat, layout) }
        if (seats.isEmpty()) {
            return Summary(0, 0f, 0f, 0f, 0f, null, null, emptyMap())
        }
        val total = seats.size
        val avgFront = seats.map { it.frontFraction }.average().toFloat()
        val avgSide = seats.map { it.sideFraction }.average().toFloat()
        val windowCount = seats.count { it.isWindow }
        val aisleCount = seats.count { it.isAisle }
        val rowCounts = seats.groupingBy { it.row }.eachCount()
        val letterCounts = seats.groupingBy { it.letter }.eachCount()
        val mostCommonRow = rowCounts.maxByOrNull { it.value }?.key
        val mostCommonLetter = letterCounts.maxByOrNull { it.value }?.key

        // Heatmap-Daten: Häufigkeit und Klasse pro Sitzplatz.
        // Die Klasse wird über ChartData normalisiert, damit "economy" und
        // "Economy" zusammengezählt werden.
        val validEntries = entries.filter { entry ->
            val key = entry.seat?.trim()?.uppercase()
            key != null && parse(key, layout) != null
        }
        val seatHeat = validEntries
            .groupingBy { it.seat!!.trim().uppercase() }
            .eachCount()
            .mapValues { (seatKey, count) ->
                // Die häufigste Klasse für diesen Sitzplatz gewinnt
                val className = validEntries
                    .filter { it.seat!!.trim().uppercase() == seatKey }
                    .mapNotNull { ChartData.normalizeClassType(it.classType) }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                SeatHeatData(count = count, className = className)
            }

        return Summary(
            total = total,
            avgFrontFraction = avgFront,
            avgSideFraction = avgSide,
            windowFraction = windowCount.toFloat() / total,
            aisleFraction = aisleCount.toFloat() / total,
            mostCommonRow = mostCommonRow,
            mostCommonLetter = mostCommonLetter,
            seatHeat = seatHeat
        )
    }
}
