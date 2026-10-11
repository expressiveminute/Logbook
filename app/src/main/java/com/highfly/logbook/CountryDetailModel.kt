package com.highfly.logbook

/**
 * Auswertung der Flugeinträge für die Länderseite des Dashboards: Welche
 * Flughäfen eines Landes angeflogen wurden, welche Verbindungen innerhalb des
 * Landes liegen (Inlandsrouten) und welche ins Ausland führen
 * (Auslandsflüge). Die Auslandsflüge werden auf der Karte nur als kurze,
 * verblassende Linie am Flughafen des Landes gezeigt - deshalb starten sie
 * hier immer am Flughafen im Land, egal ob der Flug dort begann oder endete.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Zuordnung im Test ohne
 * Asset- und Datenbankzugriff geprüft werden kann. Länder und Koordinaten
 * werden als Funktionen übergeben.
 */
object CountryDetailModel {

    /** Angeflogener Flughafen des Landes mit seiner Position für die Karte. */
    data class AirportMarker(
        val iata: String,
        val lat: Double,
        val lon: Double
    )

    /**
     * Rechteckiger Kartenausschnitt in Weltkoordinaten. Längengrade liegen
     * schon in [-180, 180], Nord/Süd in [-85, 85].
     */
    data class GeoBox(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    /**
     * Eine geflogene Verbindung, gruppiert nach ihrer Strecke.
     *
     * @param from Flughafen, an dem die Linie auf der Karte beginnt. Bei
     *   [international] ist das immer der Flughafen im Land.
     * @param to Gegenseite; liegt bei [international] im Ausland.
     */
    data class Route(val from: String, val to: String, val count: Int, val international: Boolean)

    data class Result(
        /** Angeflogene Flughäfen des Landes, ohne Doppelte, nach Code sortiert. */
        val airports: List<AirportMarker>,
        /** Verbindungen, deren beide Enden im Land liegen. */
        val domestic: List<Route>,
        /** Verbindungen zwischen dem Land und dem Ausland. */
        val international: List<Route>,
        /** Zahl der Flugeinträge, die das Land berühren. */
        val flights: Int
    )

    /**
     * @param entries Flugeinträge des Logbuchs
     * @param iso2 das Land als ISO-2-Code
     * @param countryOf ordnet einem IATA-Code sein ISO-2-Land zu; genutzt,
     *   wenn der Eintrag das Land nicht gespeichert hat
     * @param location ordnet einem IATA-Code seine Koordinaten zu, null bei
     *   unbekanntem Flughafen
     */
    fun analyze(
        entries: List<LogbookEntry>,
        iso2: String,
        countryOf: (String) -> String?,
        location: (String) -> AirportData.GeoLocation?
    ): Result {
        val target = iso2.trim().uppercase()
        val markers = LinkedHashMap<String, AirportMarker>()
        val domesticCounts = LinkedHashMap<Pair<String, String>, Int>()
        val intlCounts = LinkedHashMap<Pair<String, String>, Int>()
        var flights = 0

        for (entry in entries) {
            val from = entry.fromAirport.trim().uppercase()
            val to = entry.toAirport.trim().uppercase()
            if (from.isEmpty() || to.isEmpty()) continue
            val fromIso = iso2Of(entry.fromCountry?.takeIf { it.isNotBlank() } ?: countryOf(from))
            val toIso = iso2Of(entry.toCountry?.takeIf { it.isNotBlank() } ?: countryOf(to))
            val fromLoc = location(from)
            val toLoc = location(to)

            val touches = fromIso == target || toIso == target
            if (touches) flights++

            if (fromIso == target) {
                fromLoc?.let { markers.putIfAbsent(from, AirportMarker(from, it.lat, it.lon)) }
            }
            if (toIso == target) {
                toLoc?.let { markers.putIfAbsent(to, AirportMarker(to, it.lat, it.lon)) }
            }
            if (fromLoc == null || toLoc == null) continue

            when {
                fromIso == target && toIso == target -> {
                    val key = from to to
                    domesticCounts[key] = (domesticCounts[key] ?: 0) + 1
                }
                fromIso == target -> {
                    // Ausflug: die Linie beginnt im Land.
                    val key = from to to
                    intlCounts[key] = (intlCounts[key] ?: 0) + 1
                }
                toIso == target -> {
                    // Heimflug: die Linie beginnt wieder im Land.
                    val key = to to from
                    intlCounts[key] = (intlCounts[key] ?: 0) + 1
                }
            }
        }

        fun routes(counts: Map<Pair<String, String>, Int>, international: Boolean): List<Route> =
            counts.map { (key, count) ->
                Route(key.first, key.second, count, international)
            }.sortedWith(
                compareByDescending<Route> { it.count }.thenBy { it.from }.thenBy { it.to }
            )

        return Result(
            airports = markers.values.sortedBy { it.iata },
            domestic = routes(domesticCounts, international = false),
            international = routes(intlCounts, international = true),
            flights = flights
        )
    }

    /**
     * Fokus-Ausschnitt für den Heranzoom-Knopf der Länderseite: die Box über
     * alle angeflogenen Flughäfen. Die Umriss-Box des Landes reicht bei
     * Ländern wie Frankreich oder den Niederlanden bis in den Pazifik und
     * macht den eigentlichen Flugraum winzig; die Flughäfen zeigen dagegen,
     * wo wirklich geflogen wurde. Nach einem Klick bleiben sie alle im Bild,
     * weiter hereinzoomen kann der Nutzer danach selbst.
     *
     * @return Rechteck um alle Flughäfen, null ohne Flughäfen
     */
    fun focusBounds(airports: List<AirportMarker>): GeoBox? {
        val reference = airports.firstOrNull()?.lon ?: return null
        return box(airports, reference)
    }

    /** Rechteck um [airports], mit Mindestgröße und Weltkarten-Grenzen. */
    private fun box(airports: List<AirportMarker>, reference: Double): GeoBox? {
        if (airports.isEmpty()) return null
        var west = Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        var south = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        for (airport in airports) {
            val lon = unwind(airport.lon, reference)
            west = minOf(west, lon)
            east = maxOf(east, lon)
            south = minOf(south, airport.lat)
            north = maxOf(north, airport.lat)
        }
        if (east - west < MIN_SPAN_DEGREES) {
            val center = (west + east) / 2
            west = center - MIN_SPAN_DEGREES / 2
            east = center + MIN_SPAN_DEGREES / 2
        }
        if (north - south < MIN_SPAN_DEGREES) {
            val center = (north + south) / 2
            south = center - MIN_SPAN_DEGREES / 2
            north = center + MIN_SPAN_DEGREES / 2
        }
        west = west.coerceIn(-180.0, 180.0)
        east = east.coerceIn(-180.0, 180.0)
        if (west >= east) east = (west + 1.0).coerceAtMost(180.0)
        south = south.coerceIn(-85.0, 85.0)
        north = north.coerceIn(-85.0, 85.0)
        if (south >= north) north = (south + 1.0).coerceAtMost(85.0)
        return GeoBox(south, north, west, east)
    }

    /** Verschiebt einen Längengrad nahe an [reference], über die Datumslinie. */
    private fun unwind(lon: Double, reference: Double): Double {
        var value = lon
        while (value - reference > 180.0) value -= 360.0
        while (reference - value > 180.0) value += 360.0
        return value
    }

    private fun iso2Of(value: String?): String? {
        val code = value?.trim()?.uppercase() ?: return null
        return if (code.length == 2 && code.all { it in 'A'..'Z' }) code else null
    }

    /** Mindestausdehnung des Fokus (Grad), auch für einen einzelnen Flughafen. */
    private const val MIN_SPAN_DEGREES = 6.0
}