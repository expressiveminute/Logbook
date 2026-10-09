package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Rolle ist die zentrale Definition dafuer, was die App zeigt und was der
 * Nutzer neu eingeben darf. Der Test haelt die beiden Auspraegungen fest, damit
 * die Ableitungen (Layover, Funktion, Reisearten, Dashboard-Kacheln) nicht
 * unbemerkt auseinanderlaufen.
 */
class RoleTest {

    @Test
    fun crew_darfAlles() {
        val crew = Role.CREW
        assertTrue(crew.isCrew)
        assertFalse(crew.isPassenger)
        assertTrue(crew.showsLayover)
        assertTrue(crew.showsFunction)
        assertEquals(
            listOf(
                ChartData.PRIVATE_CANONICAL,
                ChartData.ON_DUTY_CANONICAL,
                ChartData.DEADHEAD_CANONICAL,
                ChartData.FERRY_CANONICAL,
                ChartData.GROUND_TRANSFER_CANONICAL,
                ChartData.DUTY_TRAVEL_CANONICAL,
            ),
            crew.travelTypes
        )
    }

    @Test
    fun passenger_nurPrivatUndDienstlich() {
        val passenger = Role.PASSENGER
        assertFalse(passenger.isCrew)
        assertTrue(passenger.isPassenger)
        assertFalse(passenger.showsLayover)
        assertFalse(passenger.showsFunction)
        assertEquals(
            listOf(ChartData.PRIVATE_CANONICAL, ChartData.DUTY_TRAVEL_CANONICAL),
            passenger.travelTypes
        )
    }

    @Test
    fun allowsTravelType_richtetSichNachDerRolle() {
        assertTrue(Role.CREW.allowsTravelType(ChartData.ON_DUTY_CANONICAL))
        assertTrue(Role.CREW.allowsTravelType(ChartData.FERRY_CANONICAL))
        assertFalse(Role.PASSENGER.allowsTravelType(ChartData.ON_DUTY_CANONICAL))
        assertFalse(Role.PASSENGER.allowsTravelType(ChartData.DEADHEAD_CANONICAL))
        assertTrue(Role.PASSENGER.allowsTravelType(ChartData.PRIVATE_CANONICAL))
        assertTrue(Role.PASSENGER.allowsTravelType(ChartData.DUTY_TRAVEL_CANONICAL))
        // Ohne Angabe gibt es nichts abzulehnen.
        assertTrue(Role.PASSENGER.allowsTravelType(null))
    }

    @Test
    fun dashboardKacheln_layoverUndFunktionNurFuerCrew() {
        assertTrue(DashboardPrefs.isTileAvailable("layover", Role.CREW))
        assertTrue(DashboardPrefs.isTileAvailable("function", Role.CREW))
        assertFalse(DashboardPrefs.isTileAvailable("layover", Role.PASSENGER))
        assertFalse(DashboardPrefs.isTileAvailable("function", Role.PASSENGER))
        // Alle uebrigen Kacheln bleiben beiden Rollen erhalten.
        assertTrue(DashboardPrefs.isTileAvailable("flights", Role.PASSENGER))
        assertTrue(DashboardPrefs.isTileAvailable("traveltype", Role.PASSENGER))
    }

    @Test
    fun fromKey_unbekanntErgibtCrew() {
        assertEquals(Role.CREW, Role.fromKey("crew"))
        assertEquals(Role.PASSENGER, Role.fromKey("passenger"))
        assertEquals(Role.DEFAULT, Role.fromKey(null))
        assertEquals(Role.DEFAULT, Role.fromKey("voellig-unbekannt"))
    }
}
