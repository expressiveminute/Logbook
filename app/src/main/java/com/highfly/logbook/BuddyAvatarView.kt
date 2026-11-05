package com.highfly.logbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/**
 * Runder Avatar eines Reisebuddys: entweder das hochgeladene Foto
 * ([setPhoto], kreisförmig zugeschnitten, randfüllend) oder ein farbiges
 * Avatar-Preset ([setPreset], z. B. [R.drawable.ic_avatar_m1], ungetintet
 * mittig im Kreis) oder - wenn keines von beidem gesetzt ist - ein Kreis in
 * der Primärfarbe mit dem Usericon ([R.drawable.ic_profile]) darauf.
 *
 * Die Fallback-Farben lassen sich über [setFallbackColors] setzen (die Story
 * hat feste dunkle Farben); ohne Angabe kommen die Theme-Farben
 * `colorPrimaryContainer`/`colorOnPrimaryContainer` zum Zug. Um den Kreis
 * läuft ein dünner Ring.
 */
class BuddyAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var photo: Bitmap? = null

    /** Farbiges Avatar-Preset; wird ungetintet gezeichnet. */
    private var preset: Drawable? = null

    private var fallbackBg: Int? = null
    private var fallbackTint: Int? = null
    private var ringColor: Int? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconDrawable: Drawable =
        AppCompatResources.getDrawable(context, R.drawable.ic_profile)!!
    private val circlePath = Path()
    private val drawMatrix = Matrix()
    private val iconRect = Rect()

    init {
        ringPaint.strokeWidth = 1f * resources.displayMetrics.density
    }

    /** Setzt das Foto (null zeigt wieder das Preset bzw. Usericon). */
    fun setPhoto(bitmap: Bitmap?) {
        photo = bitmap
        if (bitmap != null) preset = null
        invalidate()
    }

    /**
     * Setzt ein farbiges Avatar-Preset (null zeigt wieder das Usericon). Das
     * Preset wird in Originalfarben gezeichnet, nicht getintet.
     */
    fun setPreset(drawable: Drawable?) {
        preset = drawable
        if (drawable != null) photo = null
        invalidate()
    }

    /**
     * Setzt die Farben des Fallbacks (Foto fehlt): Kreis [bg], Icon [tint]
     * und optional die Ringfarbe [ring].
     */
    fun setFallbackColors(bg: Int, tint: Int, ring: Int? = null) {
        fallbackBg = bg
        fallbackTint = tint
        ringColor = ring
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = ringPaint.strokeWidth
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - stroke / 2f
        if (radius <= 0f) return

        bgPaint.color = fallbackBg ?: MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimaryContainer,
            android.graphics.Color.LTGRAY
        )
        canvas.drawCircle(cx, cy, radius, bgPaint)

        circlePath.reset()
        circlePath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(circlePath)

        val bmp = photo
        val presetDrawable = preset
        if (bmp != null) {
            // Foto zentriert und randfüllend in den Kreis zeichnen.
            val scale = maxOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            drawMatrix.reset()
            drawMatrix.postScale(scale, scale)
            drawMatrix.postTranslate(
                cx - bmp.width * scale / 2f,
                cy - bmp.height * scale / 2f
            )
            canvas.drawBitmap(bmp, drawMatrix, null)
        } else if (presetDrawable != null) {
            // Farbiges Avatar-Preset mittig und randfüllend im Kreis.
            val size = radius * 2f * 0.98f
            iconRect.set(
                (cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
                (cx + size / 2f).toInt(), (cy + size / 2f).toInt()
            )
            presetDrawable.bounds = iconRect
            presetDrawable.draw(canvas)
        } else {
            // Usericon mittig, etwas kleiner als der Kreis.
            val iconSize = radius * 1.15f
            iconRect.set(
                (cx - iconSize / 2f).toInt(), (cy - iconSize / 2f).toInt(),
                (cx + iconSize / 2f).toInt(), (cy + iconSize / 2f).toInt()
            )
            iconDrawable.setTint(
                fallbackTint ?: MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnPrimaryContainer,
                    android.graphics.Color.DKGRAY
                )
            )
            iconDrawable.bounds = iconRect
            iconDrawable.draw(canvas)
        }
        canvas.restore()

        ringPaint.color = ringColor ?: MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutlineVariant,
            android.graphics.Color.GRAY
        )
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }
}
