package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die automatische Avatar-Wahl der Reisebuddies
 * ([BuddyAvatars.defaultPreset]): pro Name zufällig, aber stabil – derselbe
 * Buddy behält seinen Avatar über Sitzungen hinweg, und die Wahl ist immer
 * eines der Presets.
 */
class BuddyAvatarsTest {

    @Test
    fun defaultPreset_istStabilJeName() {
        assertEquals(
            BuddyAvatars.defaultPreset("Anna").id,
            BuddyAvatars.defaultPreset("Anna").id
        )
        // Gross-/Kleinschreibung und Leerraum sind derselbe Buddy.
        assertEquals(
            BuddyAvatars.defaultPreset("Anna Meier").id,
            BuddyAvatars.defaultPreset("  anna   meier ").id
        )
    }

    @Test
    fun defaultPreset_istImmerEinPreset() {
        val names = listOf("Anna", "Ben", "Carol", "David", "Emma", "Felix", "Greta")
        for (name in names) {
            assertTrue(BuddyAvatars.presets.contains(BuddyAvatars.defaultPreset(name)))
        }
    }

    @Test
    fun defaultPreset_nichtBeiJedemNamenGleich() {
        // Über einige Namen verteilt sollen nicht alle denselben Avatar
        // bekommen (Zufall aus dem Hash, nicht konstant).
        val ids = (1..20).map { BuddyAvatars.defaultPreset("Buddy $it").id }.toSet()
        assertTrue(ids.size > 1)
    }
}
