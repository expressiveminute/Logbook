package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * Tests für [LayoverAnimation], die Planung der Layover-Animation auf der
 * Weltkarte: Hub-Wahl, Ziele, Flugzeiten und Phasen.
 */
class LayoverAnimationTest {

    private val locations = mapOf(
        "FRA" to AirportData.GeoLocation(50.0, 8.6),
        "MUC" to AirportData.GeoLocation(48.35, 11.79),
        "JFK" to AirportData.GeoLocation(40.64, -73.78),
        "SIN" to AirportData.GeoLocation(1.35, 103.99),
        "LAX" to AirportData.GeoLocation(33.94, -118.41),
        "SYD" to AirportData.GeoLocation(-33.87, 151.21)
    )

    private fun resolve(code: String) = locations[code]

    private fun entry(
        id: Long,
        from: String,
        to: String,
        layover: Boolean = true
    ) = LogbookEntry(
        id = id,
        date = LocalDate.of(2024, 1, 1 + id.toInt()),
        fromAirport = from,
        toAirport = to,
        layover = layover
    )

    @Test
    fun hubIstDerAbflughafenMitDenMeistenLayovern() {
        val entries = listOf(
            entry(1, "MUC", "JFK"),
            entry(2, "FRA", "JFK"),
            entry(3, "FRA", "SIN"),
            entry(4, "MUC", "SIN"),
            entry(5, "FRA", "JFK")
        )
        val plan = LayoverAnimation.plan(entries, ::resolve)
        assertNotNull(plan)
        assertEquals("FRA", plan!!.hub)
        assertEquals(locations.getValue("FRA"), plan.hubPoint)
    }

    @Test
    fun beiGleichstandGewinntDerZuerstGenannteHub() {
        val entries = listOf(
            entry(1, "MUC", "JFK"),
            entry(2, "FRA", "JFK"),
            entry(3, "MUC", "SIN"),
            entry(4, "FRA", "SIN")
        )
        val plan = LayoverAnimation.plan(entries, ::resolve)
        assertEquals("MUC", plan!!.hub)
    }

    @Test
    fun hubUndZieleWerdenInGrossbuchstabenGeplant() {
        val entries = listOf(
            entry(1, "fra", "jfk"),
            entry(2, "FRA", "sin")
        )
        val plan = LayoverAnimation.plan(entries, ::resolve)!!
        assertEquals("FRA", plan.hub)
        assertEquals(listOf("JFK", "SIN"), plan.routes.map { it.destination })
    }

    @Test
    fun derHubSelbstIstKeinZiel() {
        // JFK-FRA ist ein Layover zurück zum Hub und darf keine eigene Bahn
        // bekommen.
        val entries = listOf(
            entry(1, "FRA", "JFK"),
            entry(2, "JFK", "FRA"),
            entry(3, "FRA", "SIN")
        )
        val plan = LayoverAnimation.plan(entries, ::resolve)!!
        assertEquals(listOf("JFK", "SIN"), plan.routes.map { it.destination })
    }

    @Test
    fun ohneLayoverGibtEsKeinenPlan() {
        val entries = listOf(entry(1, "FRA", "JFK", layover = false))
        assertNull(LayoverAnimation.plan(entries, ::resolve))
        assertNull(LayoverAnimation.plan(emptyList(), ::resolve))
    }

    @Test
    fun unbekannteFlughaefenFallenHeraus() {
        // Nur ein Ziel ohne Koordinate: kein Plan.
        assertNull(LayoverAnimation.plan(listOf(entry(1, "FRA", "XXX")), ::resolve))
        // Ein bekanntes Ziel bleibt übrig.
        val entries = listOf(entry(1, "FRA", "XXX"), entry(2, "FRA", "JFK"))
        val plan = LayoverAnimation.plan(entries, ::resolve)!!
        assertEquals(listOf("JFK"), plan.routes.map { it.destination })
    }

    @Test
    fun laengsteStreckeBrauchtMaxFlyMsDieKuerzereWeniger() {
        val entries = listOf(entry(1, "FRA", "JFK"), entry(2, "FRA", "SIN"))
        val plan = LayoverAnimation.plan(entries, ::resolve)!!
        val byDest = plan.routes.associateBy { it.destination }
        // FRA-SIN ist die längste Strecke und setzt die Flugzeit.
        assertEquals(LayoverAnimation.MAX_FLY_MS, byDest.getValue("SIN").durationMs)
        val jfk = byDest.getValue("JFK").durationMs
        assertTrue(jfk > LayoverAnimation.MIN_FLY_MS)
        assertTrue(jfk < LayoverAnimation.MAX_FLY_MS)
        // Die Bögen beginnen am Hub und enden am Ziel (der Bogen rechnet in
        // Radians, daher der Toleranzvergleich).
        for (route in plan.routes) {
            val start = route.points.first()
            assertEquals(plan.hubPoint.lat, start.lat, 1e-9)
            assertEquals(plan.hubPoint.lon, start.lon, 1e-9)
            val end = route.points.last()
            val ziel = locations.getValue(route.destination)
            assertEquals(ziel.lat, end.lat, 1e-9)
            assertEquals(ziel.lon, end.lon, 1e-9)
        }
    }

    @Test
    fun phasenWechselnNachAnkunftHaltenUndAusblenden() {
        val entries = listOf(entry(1, "FRA", "SIN"))
        val plan = LayoverAnimation.plan(entries, ::resolve)!!
        val arrived = LayoverAnimation.maxFlyMs(plan)
        val halteEnde = arrived + LayoverAnimation.TRAIL_HOLD_MS
        val fadeEnde = halteEnde + LayoverAnimation.TRAIL_FADE_MS
        assertEquals(LayoverAnimation.Phase.FLYING, LayoverAnimation.phase(0, plan))
        assertEquals(LayoverAnimation.Phase.FLYING, LayoverAnimation.phase(arrived - 1, plan))
        assertEquals(LayoverAnimation.Phase.HOLDING, LayoverAnimation.phase(arrived, plan))
        assertEquals(LayoverAnimation.Phase.HOLDING, LayoverAnimation.phase(halteEnde - 1, plan))
        assertEquals(LayoverAnimation.Phase.FADING, LayoverAnimation.phase(halteEnde, plan))
        assertEquals(LayoverAnimation.Phase.FADING, LayoverAnimation.phase(fadeEnde - 1, plan))
        assertEquals(LayoverAnimation.Phase.DONE, LayoverAnimation.phase(fadeEnde, plan))
    }

    @Test
    fun flugzeugAnteilLaeuftVonNullAufEins() {
        val route = LayoverAnimation.Route("JFK", emptyList(), 4000L)
        assertEquals(0f, LayoverAnimation.planeFraction(0L, route), 0f)
        assertEquals(0.5f, LayoverAnimation.planeFraction(2000L, route), 0.0001f)
        assertEquals(1f, LayoverAnimation.planeFraction(4000L, route), 0f)
        // Nach der Ankunft bleibt das Flugzeug am Ziel (Anteil 1).
        assertEquals(1f, LayoverAnimation.planeFraction(9000L, route), 0f)
    }

    @Test
    fun spurenStehenDannBlendenSieAus() {
        val entries = listOf(entry(1, "FRA", "SIN"))
        val plan = LayoverAnimation.plan(entries, ::resolve)!!
        val halteEnde = LayoverAnimation.maxFlyMs(plan) + LayoverAnimation.TRAIL_HOLD_MS
        assertEquals(255, LayoverAnimation.trailAlpha(0, plan))
        assertEquals(255, LayoverAnimation.trailAlpha(halteEnde, plan))
        // Halbwegs durch die Ausblendung ist die Spur halb so deckend.
        val halb = halteEnde + LayoverAnimation.TRAIL_FADE_MS / 2
        assertEquals(127, LayoverAnimation.trailAlpha(halb, plan))
        assertEquals(
            0,
            LayoverAnimation.trailAlpha(halteEnde + LayoverAnimation.TRAIL_FADE_MS, plan)
        )
    }

    @Test
    fun nachAblaufIstDieAnimationVorbei() {
        val entries = listOf(entry(1, "FRA", "SIN"))
        val plan = LayoverAnimation.plan(entries, ::resolve)!!
        val ende = LayoverAnimation.maxFlyMs(plan) +
            LayoverAnimation.TRAIL_HOLD_MS + LayoverAnimation.TRAIL_FADE_MS
        assertFalse(LayoverAnimation.isFinished(ende - 1, plan))
        assertTrue(LayoverAnimation.isFinished(ende, plan))
    }

    @Test
    fun bogenUeberDieDatumslinieWirdEntrollt() {
        // LAX-SYD quert die Datumslinie; die Linie darf nicht um die halbe
        // Welt springen, sondern läuft stetig nach Westen.
        val entries = listOf(entry(1, "LAX", "SYD"))
        val plan = LayoverAnimation.plan(entries, ::resolve)!!
        val points = plan.routes.single().points
        for (i in 1 until points.size) {
            assertTrue(abs(points[i].lon - points[i - 1].lon) <= 180.0)
        }
        // Sydney liegt dann jenseits der Datumslinie (151° als -209°).
        assertEquals(151.21 - 360.0, points.last().lon, 1e-6)
    }
}
