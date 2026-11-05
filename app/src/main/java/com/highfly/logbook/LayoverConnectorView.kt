package com.highfly.logbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Verbindung zwischen dem unteren Rand des Ankunftsfelds (TO) und dem
 * Layover-Panel darunter: ein schmaler Strich in der Mitte, der oben und
 * unten breiter wird und dort in die Kacheln uebergeht. Die Farbe ist
 * dieselbe wie der Hintergrund der Kacheln ([bg_input_tile_filled]), sodass
 * die beiden Kacheln verbunden wirken, sobald das Layover-Kaestchen an ist.
 */
class LayoverConnectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(
            this@LayoverConnectorView,
            com.google.android.material.R.attr.colorSurfaceContainerHigh
        )
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val h = height.toFloat()
        // Oben und unten (an den Kacheln) breit, in der Mitte schmal.
        val wide = 10f * density
        val narrow = 2f * density
        path.reset()
        path.moveTo(cx - wide, 0f)
        path.lineTo(cx - narrow, h / 2f)
        path.lineTo(cx - wide, h)
        path.lineTo(cx + wide, h)
        path.lineTo(cx + narrow, h / 2f)
        path.lineTo(cx + wide, 0f)
        path.close()
        canvas.drawPath(path, fill)
    }
}
