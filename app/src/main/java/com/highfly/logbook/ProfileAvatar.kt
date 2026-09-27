package com.highfly.logbook

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.io.File

/**
 * Resolves the avatar the user picked in the profile settings to a drawable so
 * it can be shown both on the profile tile and in the bottom navigation.
 */
object ProfileAvatar {

    const val FILE_NAME = "profile_avatar.png"

    /** A selectable avatar: [value] is what gets stored in the settings,
     * [iconRes] is the symbol shown on the profile tile and in the navigation. */
    data class Preset(val value: String, val iconRes: Int, val nameRes: Int)

    val presets: List<Preset> = listOf(
        Preset("ic_profile", R.drawable.ic_profile, R.string.profile_avatar_opt_person),
        Preset("ic_flight", R.drawable.ic_flight, R.string.profile_avatar_opt_flight),
        Preset("ic_aircraft", R.drawable.ic_aircraft, R.string.profile_avatar_opt_aircraft),
        Preset("ic_world_map", R.drawable.ic_world_map, R.string.profile_avatar_opt_world_map),
        Preset("ic_earth", R.drawable.ic_earth, R.string.profile_avatar_opt_earth),
        Preset("ic_moon", R.drawable.ic_moon, R.string.profile_avatar_opt_moon),
        Preset("ic_fir", R.drawable.ic_fir, R.string.profile_avatar_opt_fir),
        Preset("ic_tree", R.drawable.ic_tree, R.string.profile_avatar_opt_tree),
        Preset("ic_heart", R.drawable.ic_heart, R.string.profile_avatar_opt_heart),
        Preset("ic_class", R.drawable.ic_class, R.string.profile_avatar_opt_class),
        Preset("ic_work", R.drawable.ic_work, R.string.profile_avatar_opt_work),
        Preset("ic_tag", R.drawable.ic_tag, R.string.profile_avatar_opt_tag),
        Preset("ic_umbrella", R.drawable.ic_umbrella, R.string.profile_avatar_opt_umbrella),
        Preset("ic_co2", R.drawable.ic_co2, R.string.profile_avatar_opt_co2),
        Preset("ic_distance", R.drawable.ic_distance, R.string.profile_avatar_opt_distance),
        Preset("ic_routes", R.drawable.ic_routes, R.string.profile_avatar_opt_routes),
    )

    private val byValue = presets.associateBy { it.value }

    /** Earlier versions only offered three presets stored as "avatar:…". */
    private val legacyValues = mapOf(
        "avatar:person" to "ic_profile",
        "avatar:flight" to "ic_flight",
        "avatar:world" to "ic_world_map",
    )

    fun normalize(value: String): String = legacyValues[value] ?: value

    fun resId(value: String): Int = byValue[normalize(value)]?.iconRes ?: R.drawable.ic_profile

    /** Anzeigename eines Presets, fuer Beschriftungen im Auswahldialog. */
    fun presetNameRes(value: String): Int = byValue[normalize(value)]?.nameRes ?: R.string.profile_avatar_opt_person

    fun load(context: Context): Drawable? {
        val value = Settings.getAvatar(context)
        if (value == Settings.AVATAR_FILE) {
            val file = File(context.filesDir, FILE_NAME)
            val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            if (bitmap != null) {
                val circular = RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
                    isCircular = true
                }
                return TintIgnoringDrawable(circular)
            }
        }
        return AppCompatResources.getDrawable(context, resId(value))
    }

    /**
     * Keeps an uploaded photo in its original colours; the navigation bar
     * tints its icons, which would otherwise flatten the image into a
     * silhouette.
     */
    private class TintIgnoringDrawable(drawable: Drawable) :
        android.graphics.drawable.DrawableWrapper(drawable) {
        override fun setTintList(tint: ColorStateList?) = Unit
        override fun setTint(tintColor: Int) = Unit
    }
}
