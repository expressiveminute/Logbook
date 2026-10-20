package com.highfly.logbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Prozentzahl des Strahlungsbalkens steht ab 50 % im ausgefüllten Balken,
 * darunter rechts daneben auf der Spur.
 */
class RadiationLimitBarTest {

    @Test
    fun unter50ProzentStehtDieZahlNebenDemBalken() {
        assertFalse(RadiationLimitBar.labelInsideBar(0))
        assertFalse(RadiationLimitBar.labelInsideBar(25))
        assertFalse(RadiationLimitBar.labelInsideBar(49))
    }

    @Test
    fun ab50ProzentStehtDieZahlImBalken() {
        assertTrue(RadiationLimitBar.labelInsideBar(50))
        assertTrue(RadiationLimitBar.labelInsideBar(100))
    }

    @Test
    fun ueber100ProzentStehtDieZahlImBalken() {
        assertTrue(RadiationLimitBar.labelInsideBar(130))
    }
}
