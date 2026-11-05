package com.highfly.logbook

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat

/**
 * Avatar-Presets und selbst zusammengestellte Avatare für Reisebuddies.
 *
 * **Presets**: zehn menschenähnliche Büsten im Avatarstil (verschiedene
 * Frisuren, männlich/weiblich, Haut- und Haarfarben) als farbige
 * Vektor-Drawables unter `drawable/ic_avatar_*.xml`. Jeder Buddy bekommt
 * automatisch einen Preset-Avatar zugeteilt, sobald er das erste Mal ohne
 * eigene Wahl angezeigt wird: abgeleitet aus dem Namens-Hash
 * ([BuddyPhotos.keyOf]) ist die Wahl zufällig, aber stabil.
 *
 * **Eigener Avatar**: der Editor auf der Buddy-Detailseite stellt einen
 * Avatar aus Teilen zusammen ([AvatarConfig]: Frisur, Accessoire, Farben).
 * Die Config liegt als String in den SharedPreferences (Schlüssel
 * `buddy_avatar_cfg_<hash>`) und wird zu einem [LayerDrawable] gerendert
 * ([buildDrawable]). Prefs-Wholesale-Export ([AppDataTransfer]) und
 * Umbenennen ([rename]) nehmen beides mit.
 *
 * **Anzeige-Reihenfolge** (gilt für Header und Story): Foto, wenn die Datei
 * existiert ([BuddyPhotos]) – erst danach der Avatar: eigene Config, sonst
 * die Preset-Wahl (`buddy_avatar_<hash>`), sonst der automatische Default.
 */
object BuddyAvatars {

    /**
     * Legacy-Wert in den Preferences: früher schrieb das Foto-Uploaden
     * `file` in den Auswahl-Schlüssel. Heute entscheidet allein die Existenz
     * der Foto-Datei; der Wert wird nur noch gelesen und fällt auf den
     * Default zurück.
     */
    const val PHOTO = "file"

    /** Name der Einstellungsdatei, siehe [Settings]. */
    private const val SETTINGS_PREF_NAME = "logbook_settings"

    /** Ein wählbares Avatar-Preset. */
    data class Preset(
        val id: String,
        @DrawableRes val iconRes: Int
    )

    /** Alle Presets – menschenähnliche Avatare mit verschiedenen Frisuren. */
    val presets: List<Preset> = listOf(
        Preset("m1", R.drawable.ic_avatar_m1),
        Preset("m2", R.drawable.ic_avatar_m2),
        Preset("m3", R.drawable.ic_avatar_m3),
        Preset("m4", R.drawable.ic_avatar_m4),
        Preset("m5", R.drawable.ic_avatar_m5),
        Preset("f1", R.drawable.ic_avatar_f1),
        Preset("f2", R.drawable.ic_avatar_f2),
        Preset("f3", R.drawable.ic_avatar_f3),
        Preset("f4", R.drawable.ic_avatar_f4),
        Preset("f5", R.drawable.ic_avatar_f5)
    )

    /**
     * Frisur-Schlüssel aus [AvatarConfig.HAIR_STYLES] auf die beiden
     * Haar-Teile (hinten/ vor dem Kopf; 0 = Teil gibt es nicht).
     */
    private val HAIR_PARTS: Map<String, Pair<Int, Int>> = mapOf(
        "short" to (R.drawable.avatar_part_hair_back_short to 0),
        "buzz" to (R.drawable.avatar_part_hair_back_buzz to 0),
        "sideburn" to (R.drawable.avatar_part_hair_back_sideburn to 0),
        "curly" to (R.drawable.avatar_part_hair_back_curly to 0),
        "afro" to (R.drawable.avatar_part_hair_back_afro to 0),
        "long" to (R.drawable.avatar_part_hair_back_long to R.drawable.avatar_part_hair_front_cap),
        "shoulder" to (R.drawable.avatar_part_hair_back_shoulder to R.drawable.avatar_part_hair_front_cap),
        "bun" to (R.drawable.avatar_part_hair_back_bun to R.drawable.avatar_part_hair_front_cap),
        "ponytail" to (R.drawable.avatar_part_hair_back_ponytail to R.drawable.avatar_part_hair_front_cap),
        "bald" to (0 to 0)
    )

    /**
     * Bart-Schlüssel aus [AvatarConfig.BEARD_STYLES] auf das Bart-Teil;
     * `chin`/`full` liegen hinter dem Kopf, `mustache` davor, 0 = keiner.
     */
    private val BEARD_PARTS: Map<String, Pair<Int, Boolean>> = mapOf(
        "chin" to (R.drawable.avatar_part_beard_chin to false),
        "full" to (R.drawable.avatar_part_beard_full to false),
        "mustache" to (R.drawable.avatar_part_beard_mustache to true)
    )

    /** Feste Farbe der Brille. */
    private val GLASSES_COLOR = 0xFF263238.toInt()

    /** Feste Farbe der Ohrringe. */
    private val EARRINGS_COLOR = 0xFFFFD54F.toInt()

    private fun prefs(context: Context) =
        context.getSharedPreferences(SETTINGS_PREF_NAME, Context.MODE_PRIVATE)

    /** Preference-Schlüssel der Preset-Wahl eines Buddys. */
    private fun prefKey(name: String): String = "buddy_avatar_" + BuddyPhotos.keyOf(name)

    /** Preference-Schlüssel der eigenen Avatar-Config eines Buddys. */
    private fun cfgKey(name: String): String = "buddy_avatar_cfg_" + BuddyPhotos.keyOf(name)

    /** Die gespeicherte Preset-Wahl: eine Preset-ID oder null (Default/Legacy). */
    fun selection(context: Context, name: String): String? =
        prefs(context).getString(prefKey(name), null)

    /** Speichert die Preset-Wahl und entfernt eine evtl. vorhandene eigene Config. */
    fun setSelection(context: Context, name: String, value: String) {
        prefs(context).edit()
            .putString(prefKey(name), value)
            .remove(cfgKey(name))
            .apply()
    }

    /** Die gespeicherte eigene Avatar-Config; null, wenn keine existiert. */
    fun config(context: Context, name: String): AvatarConfig? =
        AvatarConfig.fromString(prefs(context).getString(cfgKey(name), null))

    /** Speichert eine eigene Avatar-Config und entfernt eine Preset-Wahl. */
    fun saveConfig(context: Context, name: String, config: AvatarConfig) {
        prefs(context).edit()
            .putString(cfgKey(name), config.toString())
            .remove(prefKey(name))
            .apply()
    }

    /**
     * Der automatische Avatar eines Buddys: aus dem Namens-Hash abgeleitet,
     * also pro Buddy zufällig, aber stabil (derselbe Name, derselbe Avatar).
     */
    fun defaultPreset(name: String): Preset {
        val hash = BuddyPhotos.keyOf(name).hashCode()
        return presets[(hash and Int.MAX_VALUE) % presets.size]
    }

    /**
     * Das Preset, das der Buddy aktuell zeigen soll: die explizite Wahl, sonst
     * der automatische Default ([defaultPreset]).
     */
    fun presetFor(context: Context, name: String): Preset {
        val selected = selection(context, name)
        return presets.firstOrNull { it.id == selected } ?: defaultPreset(name)
    }

    /**
     * Das Avatar-Drawable des Buddys (ohne Foto): die eigene Config als
     * [LayerDrawable], sonst das Preset. Nie null.
     */
    fun avatarDrawable(context: Context, name: String): Drawable {
        config(context, name)?.let { return buildDrawable(context, it) }
        return AppCompatResources.getDrawable(context, presetFor(context, name).iconRes)!!
    }

    /**
     * Baut aus einer [AvatarConfig] das fertige Avatar-Drawable: die Teile als
     * Ebenen übereinander, jede einfarbige Ebene zur Laufzeit getintet.
     * Reihenfolge: Shirt, Bart hinten (Kinn/Vollbart), Haare hinten, Kopf
     * (Haut), Haare vorne (Pony), Bart vorne (Schnurrbart), Augen (fest),
     * Brille/Ohrringe. Bart wird in Haarfarbe getintet.
     */
    fun buildDrawable(context: Context, config: AvatarConfig): Drawable {
        val layers = mutableListOf<Drawable>()
        layers += tinted(context, R.drawable.avatar_part_shirt, config.shirt)
        val beard = BEARD_PARTS[config.beard]
        if (beard != null && !beard.second) {
            // Bart hinter den Kopf legen; der untere Rand bleibt sichtbar.
            layers += tinted(context, beard.first, config.hairColor)
        }
        val (hairBack, hairFront) = HAIR_PARTS[config.hair] ?: (0 to 0)
        if (hairBack != 0) layers += tinted(context, hairBack, config.hairColor)
        layers += tinted(context, R.drawable.avatar_part_head, config.skin)
        if (hairFront != 0) layers += tinted(context, hairFront, config.hairColor)
        if (beard != null && beard.second) {
            layers += tinted(context, beard.first, config.hairColor)
        }
        // Augen mit fester Farbe, nicht getintet.
        layers += AppCompatResources.getDrawable(context, R.drawable.avatar_part_eyes)!!.mutate()
        when (config.accessory) {
            "glasses" -> layers += tinted(context, R.drawable.avatar_part_acc_glasses, GLASSES_COLOR)
            "earrings" -> layers += tinted(context, R.drawable.avatar_part_acc_earrings, EARRINGS_COLOR)
        }
        return LayerDrawable(layers.toTypedArray())
    }

    /** Lädt ein Teil-Drawable und tintet es (mutate, damit die Ebenen unabhängig bleiben). */
    private fun tinted(context: Context, @DrawableRes res: Int, color: Int): Drawable {
        val drawable = AppCompatResources.getDrawable(context, res)!!.mutate()
        DrawableCompat.setTint(drawable, color)
        return drawable
    }

    /**
     * Benennt die Avatar-Wahl mit um (Preset-Schlüssel, Config-Schlüssel und
     * Foto-Datei), wenn der Buddy umbenannt wird.
     */
    fun rename(context: Context, oldName: String, newName: String) {
        val editor = prefs(context).edit()
        val oldKey = prefKey(oldName)
        val value = prefs(context).getString(oldKey, null)
        if (value != null) {
            editor.remove(oldKey).putString(prefKey(newName), value)
        }
        val oldCfgKey = cfgKey(oldName)
        val cfg = prefs(context).getString(oldCfgKey, null)
        if (cfg != null) {
            editor.remove(oldCfgKey).putString(cfgKey(newName), cfg)
        }
        editor.apply()
        BuddyPhotos.rename(context, oldName, newName)
    }
}
