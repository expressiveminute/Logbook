package com.highfly.logbook

/**
 * Reine Planung der Layover-Animation auf der Weltkarte: Ist der Filter
 * Layover auf „Ja" gestellt, starten vom Flughafen mit den meisten
 * Layover-Abflueden kleine Flugzeuge in Richtung jedes Layover-Ziels und
 * ziehen eine Spur hinter sich her. Nach der Ankunft des letzten Flugzeugs
 * bleiben die Spuren noch kurz stehen und blenden dann aus - nur
 * die Dreilettercodes der Flughaefen bleiben sichtbar.
 *
 * Ohne Android-Abhaengigkeiten, damit [LayoverAnimationTest] Zuschnitt und
 * Zeiten pruefen kann.
 */
internal object LayoverAnimation {

    /** Flugzeit der laengsten Strecke; alle Flugzeuge starten gleichzeitig. */
    const val MAX_FLY_MS = 4000L

    /** Flugzeit der kuerzesten Strecke. */
    const val MIN_FLY_MS = 1500L

    /** Wie lange die Spuren nach der letzten Ankunft noch stehen bleiben. */
    const val TRAIL_HOLD_MS = 200L

    /** Ausblenddauer der Spuren, nachdem die Haltezeit abgelaufen ist. */
    const val TRAIL_FADE_MS = 800L

    /** Phasen der Animation, abgeleitet aus der vergangenen Zeit. */
    enum class Phase {
        /** Mindestens ein Flugzeug ist noch unterwegs. */
        FLYING,

        /** Alle Flugzeuge angekommen, die Spuren stehen noch. */
        HOLDING,

        /** Die Spuren blenden aus. */
        FADING,

        /** Vorbei; nur noch die Dreilettercodes sind zu sehen. */
        DONE
    }

    /** Eine Bahn vom Hub zu einem Layover-Ziel. */
    data class Route(
        val destination: String,
        /** Grosskreisbogen in Grad, Laengen wie bei den Polylines entrollt. */
        val points: List<AirportData.GeoLocation>,
        /** Flugzeit dieser Bahn; kuerzere Strecken sind frueher am Ziel. */
        val durationMs: Long
    )

    /** Der gesamte Animationsplan: ein Hub, eine Bahn je Ziel. */
    data class Plan(
        val hub: String,
        val hubPoint: AirportData.GeoLocation,
        val routes: List<Route>
    )

    /**
     * Baut den Plan aus den Layovern der (bereits gefilterten) Eintraege:
     * Der Hub ist der Abflughafen mit den meisten Layovern, die Ziele sind
     * die verschiedenen Layover-Orte ohne den Hub selbst. Gibt es kein Ziel
     * mit Koordinate, gibt es keine Animation ([null]).
     */
    fun plan(
        entries: List<LogbookEntry>,
        resolve: (String) -> AirportData.GeoLocation?
    ): Plan? {
        val layovers = entries.filter { it.layover }
        if (layovers.isEmpty()) return null
        val hub = layovers
            .groupingBy { it.fromAirport.uppercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: return null
        val hubPoint = resolve(hub) ?: return null
        val destinations = linkedMapOf<String, AirportData.GeoLocation>()
        for (entry in layovers) {
            val to = entry.toAirport.uppercase()
            if (to == hub || to in destinations) continue
            val location = resolve(to) ?: continue
            destinations[to] = location
        }
        if (destinations.isEmpty()) return null
        val arcs = destinations.map { (destination, location) ->
            val arc = GeoMath.greatCircleArc(hubPoint, location)
                .map { AirportData.GeoLocation(it.latitude, it.longitude) }
            Route(
                destination = destination,
                points = unwrap(arc),
                durationMs = 0L
            ) to GeoMath.distanceKm(hubPoint, location)
        }
        val maxKm = arcs.maxOf { it.second }
        val routes = arcs.map { (route, km) ->
            // Alle Flugzeuge starten gleichzeitig; die kuerzeste Strecke ist
            // als erste am Ziel, die laengste braucht MAX_FLY_MS.
            val duration = if (maxKm > 0.0) {
                MIN_FLY_MS + ((MAX_FLY_MS - MIN_FLY_MS) * km / maxKm).toLong()
            } else {
                MAX_FLY_MS
            }
            route.copy(durationMs = duration)
        }
        return Plan(hub, hubPoint, routes)
    }

    /** Laengste Flugzeit des Plans - wann das letzte Flugzeug ankommt. */
    fun maxFlyMs(plan: Plan): Long = plan.routes.maxOf { it.durationMs }

    /** Phase der Animation zur vergangenen Zeit [elapsedMs]. */
    fun phase(elapsedMs: Long, plan: Plan): Phase {
        val arrived = maxFlyMs(plan)
        return when {
            elapsedMs < arrived -> Phase.FLYING
            elapsedMs < arrived + TRAIL_HOLD_MS -> Phase.HOLDING
            elapsedMs < arrived + TRAIL_HOLD_MS + TRAIL_FADE_MS -> Phase.FADING
            else -> Phase.DONE
        }
    }

    /**
     * Anteil der Strecke (0..1), den das Flugzeug der Bahn [route] geflogen
     * ist; 1, sobald es am Ziel angekommen ist.
     */
    fun planeFraction(elapsedMs: Long, route: Route): Float =
        (elapsedMs.toFloat() / route.durationMs.toFloat()).coerceIn(0f, 1f)

    /**
     * Deckkraft der Spuren zur Zeit [elapsedMs]: voll, solange geflogen
     * oder gehalten wird, danach linear auf 0 ausblendend.
     */
    fun trailAlpha(elapsedMs: Long, plan: Plan): Int {
        val holdEnd = maxFlyMs(plan) + TRAIL_HOLD_MS
        return when {
            elapsedMs < holdEnd -> 255
            elapsedMs < holdEnd + TRAIL_FADE_MS -> {
                val t = (elapsedMs - holdEnd).toFloat() / TRAIL_FADE_MS
                (255f * (1f - t)).toInt().coerceIn(0, 255)
            }
            else -> 0
        }
    }

    /** Ob die Animation zur Zeit [elapsedMs] komplett vorbei ist. */
    fun isFinished(elapsedMs: Long, plan: Plan): Boolean =
        phase(elapsedMs, plan) == Phase.DONE

    /**
     * Entrollt die Laengen eines Bogens, damit die Linie nicht um die halbe
     * Welt laeuft, wenn der Bogen die Datumslinie quert - dasselbe Verfahren
     * wie bei den statischen Polylines der Karte.
     */
    private fun unwrap(points: List<AirportData.GeoLocation>): List<AirportData.GeoLocation> {
        val result = points.toMutableList()
        for (i in 1 until result.size) {
            val prev = result[i - 1].lon
            var lon = result[i].lon
            while (lon - prev > 180.0) lon -= 360.0
            while (lon - prev < -180.0) lon += 360.0
            result[i] = AirportData.GeoLocation(result[i].lat, lon)
        }
        return result
    }
}
