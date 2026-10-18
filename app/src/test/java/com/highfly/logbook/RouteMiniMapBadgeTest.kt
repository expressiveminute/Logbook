package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Dreilettercodes der Mehrstrecken-Ansicht (Reisebuddy, Hangar) sitzen
 * exakt auf ihren Flughäfen. Getestet wird die reine Rechnung hinter
 * [RouteMiniMapView.badgeAnchor]: Der Anker kommt aus den echten Koordinaten,
 * der Längengrad wird an der Datumslinie auf die Kartenmitte entrollt, damit
 * Strecken über die Datumslinie das Schild nicht um 360 Grad versetzen.
 */
class RouteMiniMapBadgeTest {

    private val lax = AirportData.GeoLocation(33.94, -118.41)
    private val nrt = AirportData.GeoLocation(35.76, 140.39)
    private val fra = AirportData.GeoLocation(50.03, 8.56)
    private val lhr = AirportData.GeoLocation(51.47, -0.45)

    private val breite = 1000f
    private val hoehe = 500f

    @Test
    fun schildSitztAufDemPunktUeberDerDatumslinie() {
        // LAX -> NRT liegt als Bogen bei entrollten Laengengraden um -220 bis
        // -118; NRT selbst hat +140. Ohne Entrollen kaeme das Schild um 360
        // Grad versetzt heraus und lande weit ausserhalb der Karte.
        val bogen = RouteMiniMapView.buildArc(lax, nrt)
        val projektion = RouteMiniMapView.buildProjectionMulti(listOf(bogen), breite, hoehe)!!
        val letzter = bogen.size - 2

        val anker = RouteMiniMapView.badgeAnchor(projektion, nrt)
        // Genau die Position, an der die Karte den Punkt zeichnet.
        assertEquals(projektion.x(bogen[letzter].toDouble()), anker[0], 0.01f)
        assertEquals(projektion.y(bogen[letzter + 1].toDouble()), anker[1], 0.01f)
        // Der Anker liegt im sichtbaren Ausschnitt.
        assertTrue(anker[0] in 0f..breite)
        // Ohne Entrollen laege derselbe Anker weit ausserhalb (der alte Bug).
        assertTrue(projektion.x(nrt.lon) !in 0f..breite)
    }

    @Test
    fun schildSitztAufDemPunktOhneDatumslinie() {
        // Eine Strecke quer durch Europa: Entrollen aendert nichts, der Anker
        // bleibt auf dem Punkt.
        val bogen = RouteMiniMapView.buildArc(fra, lhr)
        val projektion = RouteMiniMapView.buildProjectionMulti(listOf(bogen), breite, hoehe)!!
        val letzter = bogen.size - 2

        val anker = RouteMiniMapView.badgeAnchor(projektion, lhr)
        assertEquals(projektion.x(bogen[letzter].toDouble()), anker[0], 0.01f)
        assertEquals(projektion.y(bogen[letzter + 1].toDouble()), anker[1], 0.01f)
    }

    @Test
    fun gleicherFlughafenGleicherAnkerEgalWelcheStrecke() {
        // NRT einmal als Ziel ohne Datumslinie (Laengengrad +140), einmal als
        // Ziel ueber die Datumslinie (im Bogen entrollt auf -220): Der Anker
        // ueber die echten Koordinaten ist derselbe.
        val boegen = listOf(
            RouteMiniMapView.buildArc(fra, nrt),
            RouteMiniMapView.buildArc(lax, nrt)
        )
        val projektion = RouteMiniMapView.buildProjectionMulti(boegen, breite, hoehe)!!
        val zweiter = boegen[1]
        val endpunkt = projektion.x(
            RouteMiniMapView.unwrapNear(zweiter[zweiter.size - 2].toDouble(), projektion.centerLon)
        )

        val anker = RouteMiniMapView.badgeAnchor(projektion, nrt)
        assertEquals(endpunkt, anker[0], 0.01f)
        assertTrue(anker[0] in 0f..breite)
    }

    @Test
    fun entrollenLiefertDieKopieAnDerKartenmitte() {
        // Nahe der Kartenmitte bleibt der Laengengrad, sonst wird um 360 Grad
        // verschoben.
        assertEquals(140.39, RouteMiniMapView.unwrapNear(140.39, 0.0), 0.01)
        assertEquals(8.56, RouteMiniMapView.unwrapNear(8.56, -39.6), 0.01)
        assertEquals(-219.61, RouteMiniMapView.unwrapNear(140.39, -169.0), 0.01)
        assertEquals(174.79, RouteMiniMapView.unwrapNear(-185.21, 0.0), 0.01)
    }

    @Test
    fun haeufigsteFlughaefenWerdenZuletztGezeichnet() {
        // Alle Codes kommen in der Reihenfolge vor - keiner faellt weg. Bei
        // Ueberlappungen liegt der Code des Flughafens mit den meisten
        // Strecken oben, zeichnet also zuletzt; Gleichstand entscheidet der
        // Code.
        val counts = mapOf("FRA" to 5, "LHR" to 2, "MUC" to 9, "CDG" to 2)
        assertEquals(
            listOf("CDG", "LHR", "FRA", "MUC"),
            RouteMiniMapView.badgeDrawOrder(counts)
        )
    }
}
