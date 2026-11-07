package com.highfly.logbook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Maßband der Karten: wählt zu einer verfügbaren Breite eine runde Entfernung
 * (1, 2,5 oder 5 mal 10^n Meter - also z. B. 500, 250, 100, 50 km) und
 * beschriftet sie je nach Größe in Metern oder Kilometern.
 */
object ScaleBar {

    /** Länge in Metern, die höchstens in die vorgegebene Breite passt. */
    fun niceDistance(maxMeters: Double): Double {
        if (!maxMeters.isFinite() || maxMeters <= 0.0) return 0.0
        val base = 10.0.pow(floor(log10(maxMeters)))
        for (factor in doubleArrayOf(5.0, 2.5, 1.0)) {
            val candidate = factor * base
            if (candidate <= maxMeters) return candidate
        }
        return base
    }

    /**
     * Beschriftung: unter 1 km in Metern, darüber in Kilometern. Eine
     * gebrochene Kilometerzahl (2,5 km) behält eine Nachkommastelle, volle
     * Kilometer stehen ohne (500 km, nicht 500,0 km).
     */
    fun distanceText(meters: Double, locale: Locale): String = if (meters >= 1000.0) {
        LayoverTimeScale.trimmedDecimal(meters / 1000.0, locale) + " km"
    } else {
        String.format(locale, "%,d m", meters.roundToInt())
    }
}

/**
 * Maßband unten links auf einer osmdroid-Karte: eine Linie mit Endstrichen und
 * der Entfernung, die sie auf der Karte abdeckt (z. B. „250 km"). Die Länge
 * wird bei jedem Zeichnen aus dem aktuellen Zoom neu gewählt (runde Zahl, die
 * in rund [MAX_BAR_DP] dp passt) und ändert sich damit mit dem Zoomen mit.
 *
 * Wird von der CO₂-Karte ([Co2OffsetMapView]) und der Weltkarte des Dashboards
 * ([WorldMapFragment]) genutzt. Über [leftInsetPx]/[bottomInsetPx] kann die
 * Karte zusätzlichen Rand vorgeben, z. B. die Systemleiste unten.
 */
class ScaleBarOverlay(
    private val density: Float,
    dark: Boolean,
    private val locale: Locale
) : Overlay() {

    /** Zusätzlicher linker Rand in px. */
    var leftInsetPx: Int = 0

    /** Zusätzlicher unterer Rand in px (z. B. Systemleiste). */
    var bottomInsetPx: Int = 0

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = SCALE_TEXT_DP * density
        color = if (dark) Color.rgb(226, 232, 240) else Color.rgb(38, 44, 40)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
        color = textPaint.color
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.argb(170, 18, 22, 26) else Color.argb(190, 255, 255, 255)
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return
        val metersPerPixel = metersPerPixel(mapView)
        if (metersPerPixel <= 0.0 || !metersPerPixel.isFinite()) return

        val distance = ScaleBar.niceDistance(MAX_BAR_DP * density * metersPerPixel)
        if (distance <= 0.0) return
        val barPx = (distance / metersPerPixel).toFloat()
        val label = ScaleBar.distanceText(distance, locale)

        val margin = SCALE_MARGIN_DP * density
        val pad = 4f * density
        val tick = 4f * density
        val labelWidth = textPaint.measureText(label)
        val fm = textPaint.fontMetrics

        val boxWidth = maxOf(barPx, labelWidth) + 2f * pad
        val barY = h - margin - bottomInsetPx - pad
        val baseline = barY - tick - 2f * density - fm.descent
        val boxTop = baseline + fm.ascent - pad
        val boxLeft = margin + leftInsetPx

        canvas.drawRoundRect(
            boxLeft, boxTop, boxLeft + boxWidth, h - margin - bottomInsetPx,
            3f * density, 3f * density, backgroundPaint
        )
        canvas.drawText(label, boxLeft + (boxWidth - labelWidth) / 2f, baseline, textPaint)

        val barLeft = boxLeft + (boxWidth - barPx) / 2f
        canvas.drawLine(barLeft, barY, barLeft + barPx, barY, barPaint)
        canvas.drawLine(barLeft, barY, barLeft, barY - tick, barPaint)
        canvas.drawLine(barLeft + barPx, barY, barLeft + barPx, barY - tick, barPaint)
    }

    /** Bodenmeter je Bildschirmpixel am Kartenmittelpunkt (Web-Mercator). */
    private fun metersPerPixel(mapView: MapView): Double {
        val center = mapView.mapCenter ?: return 0.0
        val projection = mapView.projection
        val start = Point()
        val end = Point()
        projection.toPixels(GeoPoint(center.latitude, center.longitude), start)
        projection.toPixels(GeoPoint(center.latitude + SCALE_SAMPLE_DEG, center.longitude), end)
        val dy = abs(end.y - start.y)
        if (dy <= 0) return 0.0
        return SCALE_SAMPLE_DEG * METERS_PER_DEGREE_LAT / dy
    }

    companion object {
        /** Mittlerer Abstand zweier Breitengrade in Metern. */
        const val METERS_PER_DEGREE_LAT = 111_320.0

        /** Schriftgröße, Rand und größte Länge des Maßbands (dp). */
        const val SCALE_TEXT_DP = 10f
        const val SCALE_MARGIN_DP = 10f
        const val MAX_BAR_DP = 96f

        /** Breitengrad-Schritt, mit dem der Maßstab des Maßbands gemessen wird. */
        const val SCALE_SAMPLE_DEG = 0.01
    }
}
