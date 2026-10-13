package com.highfly.logbook

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Tests fuer die Kachel "Zur Sonne" auf der Distanzseite: Wie oft die eigene
 * Flugstrecke die Entfernung zur Sonne (eine AE) abdeckt, im gleichen
 * Kommaformat wie "Erdumrundungen" und "Zum Mond".
 */
class DashboardStatsSunTest {

    private fun entry(km: Int) = LogbookEntry(
        date = LocalDate.of(2026, 5, 4),
        fromAirport = "FRA",
        toAirport = "JFK",
        distanceKm = km
    )

    // [DashboardStats.distanceDetails] nutzt den Context nicht weiter; eine
    // leere Application aus dem Mock-Android reicht als Kachel-Context.
    private val context: Context = Application()

    @Test
    fun sonnenTrip_entsprichtEinerAstronomischenEinheit() {
        val detail = DashboardStats.distanceDetails(context, listOf(entry(km = 149_600_000)))
        assertEquals(1.0, detail.sunTrips, 1e-9)
    }

    @Test
    fun sonnenTrip_skaliertSichLinear() {
        val detail = DashboardStats.distanceDetails(
            context,
            listOf(entry(km = 384_400), entry(km = 384_400))
        )
        assertEquals(2.0, detail.moonTrips, 1e-9)
        assertEquals(768_800.0 / 149_600_000.0, detail.sunTrips, 1e-9)
    }

    @Test
    fun formatHatGenauDreiNachkommastellen() {
        assertEquals("1,000", DashboardStats.factorText(1.0))
        assertEquals("0,003", DashboardStats.factorText(384400.0 / 149_600_000.0))
    }

    @Test
    fun sonnenProzent_passtZumWegfaktor() {
        // Volle AE: 100 % erreicht, nichts bleibt.
        val full = DashboardStats.sunPercentReached(149_600_000)
        assertEquals(100.0, full, 1e-9)
        // Ein Bruchteil: der Faktor mal 100.
        val teil = DashboardStats.sunPercentReached(149_600)
        assertEquals(
            DashboardStats.distanceDetails(context, listOf(entry(km = 149_600))).sunTrips * 100.0,
            teil,
            1e-9
        )
        // Ohne Kilometer ist noch nichts geschafft.
        assertEquals(0.0, DashboardStats.sunPercentReached(0), 1e-9)
    }

    @Test
    fun sonnenReststrecke_ergaenztDieGeflogenenKilometer() {
        // Geflogene plus verbleibende Kilometer ergeben genau eine AE.
        assertEquals(
            DashboardStats.SUN_DISTANCE_KM,
            DashboardStats.sunRemainingKm(149_600) + 149_600
        )
        // Voll geflogen bleibt nichts uebrig.
        assertEquals(0, DashboardStats.sunRemainingKm(DashboardStats.SUN_DISTANCE_KM))
        // Ueber eine AE hinaus wird nicht negativ.
        assertEquals(0, DashboardStats.sunRemainingKm(DashboardStats.SUN_DISTANCE_KM + 5_000))
        assertEquals(DashboardStats.SUN_DISTANCE_KM, DashboardStats.sunRemainingKm(0))
    }
}