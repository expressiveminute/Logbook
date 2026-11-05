package com.highfly.logbook

import kotlin.random.Random

/**
 * Konfiguration eines selbst zusammengestellten Buddy-Avatars: Frisur,
 * Accessoire und die drei Farben (Haare, Haut, Shirt). Reine Datenklasse
 * ohne Android-Abhängigkeiten, damit [AvatarConfigTest] Serialisierung und
 * Zufallsgenerator prüfen kann.
 *
 * Gespeichert wird die Config als einfacher String in den SharedPreferences
 * (Schlüssel `buddy_avatar_cfg_<hash>`, siehe [BuddyAvatars]) – der JSON-Export
 * der App nimmt die Einstellungsdatei als Ganzes mit, die Config wandert also
 * automatisch mit.
 *
 * Format: `frisur|#RRGGBB|#RRGGBB|#RRGGBB|accessoire|bart` (Reihenfolge siehe
 * [toString]). Farben ohne Alpha, Alpha ist immer FF. Ältere 5-Felder-Strings
 * (ohne Bart) werden beim Lesen akzeptiert: ein Accessoire „beard" wird zum
 * Vollbart, sonst „none".
 */
data class AvatarConfig(
    /** Frisur-Schlüssel, einer aus [HAIR_STYLES]. */
    val hair: String = "short",
    val hairColor: Int = 0xFF212121.toInt(),
    /** Hautfarbe (Kopf und Hals). */
    val skin: Int = 0xFFFFDBAC.toInt(),
    /** Shirtfarbe (Schultern). */
    val shirt: Int = 0xFF3F51B5.toInt(),
    /** Accessoire-Schlüssel, einer aus [ACCESSORIES]. */
    val accessory: String = "none",
    /** Bart-Schlüssel, einer aus [BEARD_STYLES]; wird in Haarfarbe getintet. */
    val beard: String = "none"
) {

    /** Serialisiert die Config als Prefs-String. */
    override fun toString(): String = listOf(
        hair,
        hex(hairColor),
        hex(skin),
        hex(shirt),
        accessory,
        beard
    ).joinToString("|")

    companion object {

        /** Alle Frisuren des Editors, in Anzeige-Reihenfolge. */
        val HAIR_STYLES = listOf(
            "short", "buzz", "sideburn", "curly", "afro",
            "long", "shoulder", "bun", "ponytail", "bald"
        )

        /** Alle Bärte des Editors, in Anzeige-Reihenfolge. */
        val BEARD_STYLES = listOf("none", "chin", "full", "mustache")

        /** Alle Accessoires des Editors, in Anzeige-Reihenfolge. */
        val ACCESSORIES = listOf("none", "glasses", "earrings")

        /** Hautfarben des Editors. */
        val SKIN_COLORS = listOf(
            0xFFDBAC, 0xF1C27D, 0xE0AC69, 0xC68642, 0x8D5524, 0x5D4037
        ).map { (0xFF000000.toInt() or it) }

        /**
         * Haarfarben des Editors, in Balken-Reihenfolge: links Schwarz/Braun,
         * direkt rechts neben Braun Blond, dann Grau/Weiss, ganz rechts Gelb
         * und Rot.
         */
        val HAIR_COLORS = listOf(
            0x212121, // schwarz
            0x3E2723, // dunkelbraun
            0x6D4C41, // braun
            0xE8C878, // blond (rechts neben braun)
            0xBDBDBD, // grau
            0xECEFF1, // weiss
            0xFDD835, // gelb
            0xC62828  // rot
        ).map { (0xFF000000.toInt() or it) }

        /** Shirtfarben des Editors. */
        val SHIRT_COLORS = listOf(
            0x3F51B5, 0x009688, 0xE91E63, 0x607D8B,
            0x7B1FA2, 0xE64A19, 0xF06292, 0x455A64
        ).map { (0xFF000000.toInt() or it) }

        /** Parst einen Prefs-String; null bei ungültigem Format. */
        fun fromString(raw: String?): AvatarConfig? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split("|")
            if (parts.size != 5 && parts.size != 6) return null
            val hair = parts[0]
            var accessory = parts[4]
            var beard = if (parts.size == 6) parts[5] else "none"
            if (parts.size == 5 && accessory == "beard") {
                // Legacy: Bart war ein Accessoire und wird zum Vollbart.
                accessory = "none"
                beard = "full"
            }
            if (hair !in HAIR_STYLES || accessory !in ACCESSORIES || beard !in BEARD_STYLES) {
                return null
            }
            val hairColor = parseHex(parts[1]) ?: return null
            val skin = parseHex(parts[2]) ?: return null
            val shirt = parseHex(parts[3]) ?: return null
            return AvatarConfig(hair, hairColor, skin, shirt, accessory, beard)
        }

        /** "#RRGGBB" als Int mit Alpha FF; null bei ungültig. */
        private fun parseHex(s: String): Int? {
            val rgb = s.removePrefix("#").toIntOrNull(16) ?: return null
            if (rgb < 0 || rgb > 0xFFFFFF) return null
            return 0xFF000000.toInt() or rgb
        }

        /** Int als "#RRGGBB" (Alpha wird abgeschnitten). */
        fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

        /** Eine zufällige Config. */
        fun random(random: Random = Random.Default): AvatarConfig = AvatarConfig(
            hair = HAIR_STYLES[random.nextInt(HAIR_STYLES.size)],
            hairColor = HAIR_COLORS[random.nextInt(HAIR_COLORS.size)],
            skin = SKIN_COLORS[random.nextInt(SKIN_COLORS.size)],
            shirt = SHIRT_COLORS[random.nextInt(SHIRT_COLORS.size)],
            accessory = ACCESSORIES[random.nextInt(ACCESSORIES.size)],
            beard = BEARD_STYLES[random.nextInt(BEARD_STYLES.size)]
        )
    }
}
