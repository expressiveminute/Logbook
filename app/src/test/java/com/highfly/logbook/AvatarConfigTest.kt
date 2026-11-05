package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tests für die Config des selbst zusammengestellten Avatars
 * ([AvatarConfig]): Serialisierung als Prefs-String (Round-trip), Parsing
 * mit Fallback bei ungültigen Werten und der Zufallsgenerator (nur Werte
 * aus den Paletten).
 */
class AvatarConfigTest {

    @Test
    fun toString_fromString_roundTrip() {
        val cfg = AvatarConfig(
            hair = "long",
            hairColor = 0xFFFDD835.toInt(),
            skin = 0xFFC68642.toInt(),
            shirt = 0xFFE91E63.toInt(),
            accessory = "glasses"
        )
        assertEquals(cfg, AvatarConfig.fromString(cfg.toString()))
    }

    @Test
    fun fromString_nullUndUngueltigesLiefertNull() {
        assertNull(AvatarConfig.fromString(null))
        assertNull(AvatarConfig.fromString(""))
        assertNull(AvatarConfig.fromString("nur-ein-teil"))
        assertNull(AvatarConfig.fromString("kurz|#FF0000|#FF0000|#FF0000|brille")) // unbekannte Schlüssel
        assertNull(AvatarConfig.fromString("short|keine-farbe|#FF0000|#FF0000|none"))
        assertNull(AvatarConfig.fromString("short|#1000000|#FF0000|#FF0000|none")) // zu gross
    }

    @Test
    fun fromString_akzeptiertGueltigeConfig() {
        val cfg = AvatarConfig.fromString("bun|#212121|#FFDBAC|#3F51B5|earrings")
        assertNotNull(cfg)
        assertEquals("bun", cfg!!.hair)
        assertEquals(0xFF212121.toInt(), cfg.hairColor)
        assertEquals(0xFFFFDBAC.toInt(), cfg.skin)
        assertEquals(0xFF3F51B5.toInt(), cfg.shirt)
        assertEquals("earrings", cfg.accessory)
    }

    @Test
    fun hex_schneidetAlphaAb() {
        assertEquals("#FFDBAC", AvatarConfig.hex(0xFFFFDBAC.toInt()))
        assertEquals("#000000", AvatarConfig.hex(0xFF000000.toInt()))
    }

    @Test
    fun random_nurWerteAusDenPaletten() {
        val random = Random(42)
        repeat(50) {
            val cfg = AvatarConfig.random(random)
            assertTrue(cfg.hair in AvatarConfig.HAIR_STYLES)
            assertTrue(cfg.accessory in AvatarConfig.ACCESSORIES)
            assertTrue(cfg.hairColor in AvatarConfig.HAIR_COLORS)
            assertTrue(cfg.skin in AvatarConfig.SKIN_COLORS)
            assertTrue(cfg.shirt in AvatarConfig.SHIRT_COLORS)
        }
    }

    @Test
    fun random_istNichtImmerGleich() {
        val random = Random(1)
        val configs = (1..20).map { AvatarConfig.random(random).toString() }.toSet()
        assertTrue(configs.size > 1)
    }
}
