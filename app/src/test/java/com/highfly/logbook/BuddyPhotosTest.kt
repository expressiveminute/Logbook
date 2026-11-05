package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für den Datei-Schlüssel der Reisebuddy-Fotos ([BuddyPhotos.keyOf]).
 * Der Schlüssel muss zur Normalisierung von [TravelBuddyStats] passen: derselbe
 * Name in anderer Schreibweise oder mit anderem Leerraum ist derselbe Buddy
 * und bekommt dasselbe Foto, ein anderer Name ein anderes.
 */
class BuddyPhotosTest {

    @Test
    fun keyOf_gleicheSchreibweiseGleicherSchluessel() {
        assertEquals(BuddyPhotos.keyOf("Anna"), BuddyPhotos.keyOf("anna"))
        assertEquals(BuddyPhotos.keyOf("Anna"), BuddyPhotos.keyOf("  Anna  "))
        assertEquals(BuddyPhotos.keyOf("Anna Meier"), BuddyPhotos.keyOf("anna   meier"))
    }

    @Test
    fun keyOf_andereNamenAnders() {
        assertNotEquals(BuddyPhotos.keyOf("Anna"), BuddyPhotos.keyOf("Anne"))
        assertNotEquals(BuddyPhotos.keyOf("Anna"), BuddyPhotos.keyOf("Anna Meier"))
    }

    @Test
    fun keyOf_ist16HexZeichen() {
        val key = BuddyPhotos.keyOf("Ben")
        assertEquals(16, key.length)
        assertTrue(key.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun keyOf_passtZuTravelBuddyStatsNormalisierung() {
        // parse() liefert den Namen getrimmt mit kollabiertem Leerraum; der
        // Schlüssel in build() ist die kleingeschriebene Form - keyOf muss
        // auf dieselbe Form kommen, sonst gäbe es zwei Fotos für einen Buddy.
        val parsed = TravelBuddyStats.parse("  Anna ,  BEN\n carol ")
        assertEquals(listOf("Anna", "BEN", "carol"), parsed)
        assertEquals(BuddyPhotos.keyOf("anna"), BuddyPhotos.keyOf(parsed[0]))
        assertEquals(BuddyPhotos.keyOf("ben"), BuddyPhotos.keyOf(parsed[1]))
        assertEquals(BuddyPhotos.keyOf("carol"), BuddyPhotos.keyOf(parsed[2]))
    }

    @Test
    fun normalize_kollabiertLeerraumUndKlein() {
        assertEquals("anna meier", BuddyPhotos.normalize("  Anna   Meier "))
        assertEquals("", BuddyPhotos.normalize("   "))
    }
}
