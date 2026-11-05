package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Tests für die programmgesteuerte Zoom-Rechnung der Rubbelkarte, siehe
 * [MapFocus].
 */
class MapFocusTest {

    @Test
    fun zoomForSpan_begrenztAufDenZoombereich() {
        // Eine Ausdehnung über 170 Grad (halbe Welt) wird auf 170° gedeckelt und
        // landet bei Zoom ~1.0 (nie unter ZOOM_MIN), eine winzige Region nicht
        // über ZOOM_MAX hinaus.
        assertEquals(1.004f, MapFocus.zoomForSpan(179.0), 1e-3f)
        assertEquals(RubbelkarteView.ZOOM_MAX, MapFocus.zoomForSpan(1.0), 1e-4f)
        assertEquals(RubbelkarteView.ZOOM_MAX, MapFocus.zoomForSpan(0.0), 1e-4f)
    }

    @Test
    fun zoomForSpan_kleinereRegionZoomtStaerker() {
        // Je kleiner die Region, desto größer die Zoomstufe.
        val welt = MapFocus.zoomForSpan(180.0)
        val kontinent = MapFocus.zoomForSpan(60.0)
        val land = MapFocus.zoomForSpan(10.0)
        assertTrue(welt < kontinent)
        assertTrue(kontinent < land)
        // 60 Grad Ausdehnung: sin(30°) = 0.5, damit landet Zoom bei 1.0/0.5 = 2.0.
        assertEquals(2.0f, kontinent, 1e-3f)
    }

    @Test
    fun zoomForSpan_istImGueltigenBereich() {
        for (span in listOf(0.5, 2.0, 9.0, 25.0, 60.0, 110.0, 170.0, 180.0, 360.0)) {
            val zoom = MapFocus.zoomForSpan(span)
            assertTrue(zoom in RubbelkarteView.ZOOM_MIN..RubbelkarteView.ZOOM_MAX)
        }
    }

    @Test
    fun centerOfPoints_istDatumslinienfest() {
        // Zwei Punkte beiderseits der Datumslinie mitteln sich nahe 180°, nicht
        // nahe 0°.
        val center = MapFocus.centerOfPoints(listOf(0.0 to 179.0, 0.0 to -179.0))!!
        assertEquals(0.0, center.first, 1e-6)
        assertTrue(kotlin.math.abs(center.second) > 170.0)
    }

    @Test
    fun centerOfPoints_leereListeIstNull() {
        assertNull(MapFocus.centerOfPoints(emptyList()))
    }

    @Test
    fun spanAround_verdoppeltDenAbstand() {
        // Ein Punkt 10° östlich und einer 10° westlich: Ausdehnung 20°.
        val span = MapFocus.spanAround(0.0, 0.0, listOf(0.0 to 10.0, 0.0 to -10.0))
        assertEquals(20.0, span, 1e-3)
    }

    @Test
    fun spanAround_ueberDieDatumslinie() {
        // 179° und -179° liegen je 1° von 180° entfernt (2° auseinander, nicht
        // 358°): die Ausdehnung ist 2°.
        val span = MapFocus.spanAround(0.0, 180.0, listOf(0.0 to 179.0, 0.0 to -179.0))
        assertEquals(2.0, span, 1e-3)
    }

    @Test
    fun forCountries_nimmtAnkerDerGefiltertenLaender() {
        val countries = listOf(
            country("DE", 48.0, 11.0),
            country("FR", 46.0, 2.0),
            country("JP", 36.0, 138.0)
        )
        val focus = MapFocus.forCountries(countries) { it.iso2 != "JP" }!!
        // Mittelpunkt liegt zwischen Deutschland und Frankreich.
        assertEquals(47.0, focus.lat, 2.0)
        assertTrue(focus.lon in 0.0..15.0)
        assertTrue(focus.zoom > RubbelkarteView.ZOOM_MIN)
    }

    @Test
    fun forCountries_ohneTrefferIstNull() {
        assertNull(MapFocus.forCountries(emptyList()) { true })
    }

    @Test
    fun forCountry_nutztDenAnkerDesLandes() {
        val focus = MapFocus.forCountry(country("DE", 51.0, 10.0))
        assertEquals(51.0, focus.lat, 1e-6)
        assertEquals(10.0, focus.lon, 1e-6)
    }

    @Test
    fun forStates_mitteltUeberDieStaatenanker() {
        val states = listOf(
            state("US-CA", "US", 37.0, -119.0),
            state("US-NY", "US", 43.0, -75.0)
        )
        val focus = MapFocus.forStates(states)!!
        assertEquals(40.0, focus.lat, 3.0)
        assertTrue(focus.lon < -90.0)
    }

    @Test
    fun forState_nutztDenAnkerDesStaates() {
        val focus = MapFocus.forState(state("DE-BY", "DE", 48.8, 11.5))
        assertEquals(48.8, focus.lat, 1e-6)
        assertEquals(11.5, focus.lon, 1e-6)
    }

    @Test
    fun ringPunkteWerdenFuerDieAusdehnungGenutzt() {
        // Ein Land mit Ring um den Anker: die Ausdehnung wächst mit dem Ring.
        val klein = country("XX", 10.0, 10.0, ring = listOf(floatArrayOf(10f, 10f, 11f, 10f, 11f, 11f, 10f, 11f)))
        val gross = country("XX", 10.0, 10.0, ring = listOf(floatArrayOf(10f, 10f, 30f, 10f, 30f, 30f, 10f, 30f)))
        assertTrue(MapFocus.forCountry(gross).zoom < MapFocus.forCountry(klein).zoom)
    }

    private fun country(
        iso2: String,
        lat: Double,
        lon: Double,
        ring: List<FloatArray> = emptyList()
    ) = CountryShapes.Country(
        iso2 = iso2,
        nameDe = iso2,
        nameEn = iso2,
        labelLat = lat,
        labelLon = lon,
        bbox = doubleArrayOf(lon - 1, lat - 1, lon + 1, lat + 1),
        rings = ring
    )

    private fun state(code: String, iso2: String, lat: Double, lon: Double) = StateShapes.State(
        code = code,
        iso2 = iso2,
        capLat = lat,
        capLon = lon,
        labelLat = lat,
        labelLon = lon,
        bbox = doubleArrayOf(lon - 1, lat - 1, lon + 1, lat + 1),
        nameDe = code,
        nameEn = code,
        capDe = "",
        capEn = "",
        rings = emptyList()
    )

    @Test
    fun forCountry_ueberseegebietZiehtDenZoomNichtAufWeltansicht() {
        // Frankreich: großer Haupt ring um 46N/2E, dazu Französisch-Guayana
        // als kleiner Exklave bei 4N/-53W. Ohne Exklaven-Filter läge die
        // Ausdehnung bei ~55 Grad und der Zoom bei ~1 (Weltansicht).
        val mainland = floatArrayOf(
            -2f, 43f, 8f, 43f, 8f, 51f, -2f, 51f
        ) // (lon, lat)
        val guayana = floatArrayOf(
            -54f, 3f, -51f, 3f, -51f, 5f, -54f, 5f
        )
        val frankreich = country("FR", 46.0, 2.0, ring = listOf(mainland, guayana))
        val focus = MapFocus.forCountry(frankreich)
        // Ausdehnung nur des Festlands (~10 Grad): Zoom deutlich über 1.
        assertTrue("zoom=${focus.zoom}", focus.zoom > 4f)
        // Zum Vergleich: alle Ringe würden den Zoom auf ~1 drücken.
        val spanAlle = MapFocus.spanAround(
            46.0, 2.0,
            listOf(
                43.0 to -2.0, 43.0 to 8.0, 51.0 to 8.0, 51.0 to -2.0,
                3.0 to -54.0, 3.0 to -51.0, 5.0 to -51.0, 5.0 to -54.0
            )
        )
        assertTrue(MapFocus.zoomForSpan(spanAlle) < 2f)
        assertTrue(focus.zoom > MapFocus.zoomForSpan(spanAlle))
    }

    @Test
    fun forCountry_mehrereGrosseInselnBleibenErhalten() {
        // Indonesien: mehrere große Inseln - alle >= 20 % der größten Fläche
        // bleiben in der Ausdehnung, der Zoom wird nicht auf eine Insel eng.
        val borneo = floatArrayOf(109f, -3f, 119f, -3f, 119f, 7f, 109f, 7f)
        val sumatra = floatArrayOf(95f, -6f, 106f, -6f, 106f, 6f, 95f, 6f)
        val sulawesi = floatArrayOf(119f, -5f, 122f, -5f, 122f, 1f, 119f, 1f)
        val indonesien = country("ID", 0.0, 113.0, ring = listOf(borneo, sumatra, sulawesi))
        val focus = MapFocus.forCountry(indonesien)
        // Ausdehnung über alle Inseln (~24 Grad) - Zoom deutlich unter dem
        // einer einzelnen Insel.
        assertTrue("zoom=${focus.zoom}", focus.zoom < 5f)
    }

    @Test
    fun pointInRings_erkenntPunktImRing() {
        // Quadrat um (10, 10) als (lon, lat)-Paare.
        val ring = listOf(floatArrayOf(9f, 9f, 11f, 9f, 11f, 11f, 9f, 11f))
        assertTrue(MapFocus.pointInRings(10.0, 10.0, ring))
        assertTrue(MapFocus.pointInRings(9.5, 10.5, ring))
        assertTrue(!MapFocus.pointInRings(20.0, 20.0, ring))
    }

    @Test
    fun pointInRings_istDatumslinienfest() {
        // Ring über die Datumslinie: 179 bis -179 (entfaltet um 180).
        val ring = listOf(floatArrayOf(179f, -1f, -179f, -1f, -179f, 1f, 179f, 1f))
        assertTrue(MapFocus.pointInRings(0.0, 180.0, ring))
        assertTrue(!MapFocus.pointInRings(0.0, 0.0, ring))
    }
}
