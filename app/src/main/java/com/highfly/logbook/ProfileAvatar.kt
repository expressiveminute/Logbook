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

    fun resId(value: String): Int = when (value) {
        Settings.AVATAR_FLIGHT -> R.drawable.ic_flight
        Settings.AVATAR_WORLD -> R.drawable.ic_world_map
        else -> R.drawable.ic_profile
    }

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
