package com.highfly.logbook

/**
 * Kabinenlayouts für bekannte Flugzeugtypen. Ohne Android-Abhängigkeiten,
 * damit die Logik im Test geprüft werden kann.
 *
 * Ein Layout beschreibt, wie viele Reihen das Flugzeug hat, welche Reihen zu
 * welcher Reiseklasse gehören und wie die Sitzplätze von links nach rechts
 * beschriftet sind (A ist immer links am Fenster). Der rechte Fensterplatz
 * ist bei Schmalrumpfflugzeugen F (3+3), bei Langstreckenflugzeugen K (3+4+3,
 * I wird in der Luftfahrt ausgelassen).
 *
 * Die Sitzplatz-Logik: A ist immer links am Fenster, dann wird von links nach
 * rechts durchgezählt. Bei 3+3 ist die Reihenfolge A B C | D E F, bei 3+4+3
 * ist sie A B C | D E F G | H J K.
 *
 * Wenn ein Eintrag eine Reiseklasse hat, die nicht zum Schema passt (z. B.
 * Reihe 20A in Business auf einer A320, wo Business nur Reihe 1–6 ist), wird
 * der Sitzplatz trotzdem in der passenden Klasse angezeigt, nur weiter hinten
 * im jeweiligen Bereich.
 */
object AircraftLayout {

    /** Ein Abschnitt im Kabinenlayout: Reihenbereich einer Reiseklasse. */
    data class ClassSection(
        /** Kanonischer Klassenname, z. B. "Economy", "Business". */
        val className: String,
        /** Erste Reihe (1-basiert, inklusive). */
        val startRow: Int,
        /** Letzte Reihe (1-basiert, inklusive). */
        val endRow: Int
    )

    /** Vollständiges Kabinenlayout eines Flugzeugtyps. */
    data class Layout(
        /** Gesamtzahl der Reihen. */
        val totalRows: Int,
        /** Abschnitte nach Reiseklasse, in Reihenfolge von vorne nach hinten. */
        val sections: List<ClassSection>,
        /** Alle Sitzbuchstaben von links nach rechts (A bis rechter Fensterplatz). */
        val seatLetters: List<Char>,
        /**
         * Indizes in [seatLetters], nach denen ein Gang gezeichnet wird.
         * Bei 3+3 (A B C | D E F) ist das [2] (nach C). Bei 3+4+3
         * (A B C | D E F G | H J K) ist es [2, 6] (nach C und nach G).
         */
        val aisleAfter: List<Int>,
        /** Buchstabe des rechten Fensterplatzes (F oder K). */
        val rightWindowLetter: Char
    ) {
        /** Linke Sitzbuchstaben (vor dem ersten Gang). */
        val leftLetters: List<Char> = seatLetters.take(aisleAfter.firstOrNull()?.plus(1) ?: seatLetters.size)

        /** Rechte Sitzbuchstaben (nach dem letzten Gang). */
        val rightLetters: List<Char> = if (aisleAfter.isNotEmpty()) {
            seatLetters.drop(aisleAfter.last() + 1)
        } else {
            emptyList()
        }

        /**
         * Findet den Abschnitt, der die gegebene Reihe enthält, oder null.
         */
        fun sectionForRow(row: Int): ClassSection? =
            sections.firstOrNull { row in it.startRow..it.endRow }

        /**
         * Findet den Abschnitt für eine Reiseklasse (kanonischer Name), oder null.
         */
        fun sectionForClass(className: String): ClassSection? =
            sections.firstOrNull { it.className.equals(className, ignoreCase = true) }
    }

    // --- Bekannte Layouts ---

    /** Buchstaben für 3+3-Bestuhlung (Schmalrumpf): A B C | D E F. */
    private val NARROW_LETTERS = listOf('A', 'B', 'C', 'D', 'E', 'F')
    private val NARROW_AISLES = listOf(2) // Gang nach C (Index 2)

    /** Buchstaben für 3+4+3-Bestuhlung (Langstrecke): A B C | D E F G | H J K. */
    private val WIDE_LETTERS = listOf('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K')
    private val WIDE_AISLES = listOf(2, 6) // Gänge nach C und G

    private val layouts: Map<String, Layout> = mapOf(
        // --- Schmalrumpf: Business + Eco, Fenster rechts F ---
        "A319" to Layout(
            totalRows = 25,
            sections = listOf(
                ClassSection("Business", 1, 5),
                ClassSection("Economy", 6, 25)
            ),
            seatLetters = NARROW_LETTERS,
            aisleAfter = NARROW_AISLES,
            rightWindowLetter = 'F'
        ),
        "A320" to Layout(
            totalRows = 32,
            sections = listOf(
                ClassSection("Business", 1, 6),
                ClassSection("Economy", 7, 32)
            ),
            seatLetters = NARROW_LETTERS,
            aisleAfter = NARROW_AISLES,
            rightWindowLetter = 'F'
        ),
        "A320NEO" to Layout(
            totalRows = 32,
            sections = listOf(
                ClassSection("Business", 1, 6),
                ClassSection("Economy", 7, 32)
            ),
            seatLetters = NARROW_LETTERS,
            aisleAfter = NARROW_AISLES,
            rightWindowLetter = 'F'
        ),
        "A321" to Layout(
            totalRows = 38,
            sections = listOf(
                ClassSection("Business", 1, 6),
                ClassSection("Economy", 7, 38)
            ),
            seatLetters = NARROW_LETTERS,
            aisleAfter = NARROW_AISLES,
            rightWindowLetter = 'F'
        ),
        "A321NEO" to Layout(
            totalRows = 39,
            sections = listOf(
                ClassSection("Business", 1, 6),
                ClassSection("Economy", 7, 39)
            ),
            seatLetters = NARROW_LETTERS,
            aisleAfter = NARROW_AISLES,
            rightWindowLetter = 'F'
        ),

        // --- Langstrecke: Business + Premium Eco + Eco, Fenster rechts K ---
        "A330" to Layout(
            totalRows = 46,
            sections = listOf(
                ClassSection("Business", 1, 11),
                ClassSection("Premium Eco", 15, 19),
                ClassSection("Economy", 22, 46)
            ),
            seatLetters = WIDE_LETTERS,
            aisleAfter = WIDE_AISLES,
            rightWindowLetter = 'K'
        ),
        "A340" to Layout(
            totalRows = 46,
            sections = listOf(
                ClassSection("Business", 1, 5),
                ClassSection("Premium Eco", 10, 14),
                ClassSection("Economy", 16, 46)
            ),
            seatLetters = WIDE_LETTERS,
            aisleAfter = WIDE_AISLES,
            rightWindowLetter = 'K'
        ),
        "A350" to Layout(
            totalRows = 48,
            sections = listOf(
                ClassSection("First", 1, 1),
                ClassSection("Business", 2, 16),
                ClassSection("Premium Eco", 18, 23),
                ClassSection("Economy", 24, 48)
            ),
            seatLetters = WIDE_LETTERS,
            aisleAfter = WIDE_AISLES,
            rightWindowLetter = 'K'
        )
    )

    /**
     * Generisches Layout für unbekannte Flugzeugtypen: 40 Reihen, 3+3-Bestuhlung,
     * vier Klassenabschnitte wie bisher.
     */
    val DEFAULT: Layout = Layout(
        totalRows = 40,
        sections = listOf(
            ClassSection("First", 1, 2),
            ClassSection("Business", 3, 12),
            ClassSection("Premium Eco", 13, 16),
            ClassSection("Economy", 17, 40)
        ),
        seatLetters = NARROW_LETTERS,
        aisleAfter = NARROW_AISLES,
        rightWindowLetter = 'F'
    )

    /**
     * Normalisiert einen Flugzeugtyp-String: Grossbuchstaben, ohne Leerzeichen,
     * ohne Herstellerwörter und ohne Bindestriche. "Airbus A330-300" → "A330300",
     * "A350-900" → "A350900".
     */
    fun normalize(type: String?): String {
        val raw = type?.trim()?.uppercase() ?: return ""
        // Herstellerwörter entfernen
        val withoutManufacturer = raw
            .replace("AIRBUS", "")
            .replace("BOEING", "")
            .replace("  ", " ")
            .trim()
        // Leerzeichen und Bindestriche entfernen
        return withoutManufacturer.replace(" ", "").replace("-", "")
    }

    /**
     * Ermittelt das Kabinenlayout zum eingetragenen Flugzeugtyp.
     * Erkennt Familien und Varianten über Präfix-Matching:
     * "A330-300" → A330, "A350-900" → A350, "A320neo" → A320NEO.
     * Unbekannte Typen erhalten [DEFAULT].
     */
    fun resolve(aircraftType: String?): Layout {
        val norm = normalize(aircraftType)
        if (norm.isEmpty()) return DEFAULT

        // Exakter Treffer (längste Schlüssel zuerst, damit A320NEO vor A320 gewinnt)
        layouts.keys.sortedByDescending { it.length }.firstOrNull { norm == it }?.let {
            return layouts.getValue(it)
        }
        // Präfix-Treffer: "A330300" startet mit "A330", "A350900" mit "A350"
        layouts.keys.sortedByDescending { it.length }.firstOrNull { norm.startsWith(it) }?.let {
            return layouts.getValue(it)
        }
        return DEFAULT
    }

    /**
     * Ermittelt das häufigste Layout unter den Einträgen mit Sitzplatz.
     * Einträge ohne Flugzeugtyp oder mit unbekanntem Typ tragen nicht zur
     * Mehrheit bei. Gibt es keine passenden Einträge, wird [DEFAULT] zurückgegeben.
     */
    fun mostCommonLayout(entries: List<LogbookEntry>): Layout {
        val typeCounts = entries
            .filter { !it.seat.isNullOrBlank() }
            .mapNotNull { it.aircraftType }
            .groupingBy { normalize(it) }
            .eachCount()
        if (typeCounts.isEmpty()) return DEFAULT

        // Finde den normalisierten Typ mit den meisten Sitzen
        val bestNorm = typeCounts.maxByOrNull { it.value }?.key ?: return DEFAULT
        // Präfix-Matching gegen bekannte Layouts (wie in resolve)
        layouts.keys.sortedByDescending { it.length }.firstOrNull { bestNorm == it || bestNorm.startsWith(it) }?.let {
            return layouts.getValue(it)
        }
        return DEFAULT
    }
}
