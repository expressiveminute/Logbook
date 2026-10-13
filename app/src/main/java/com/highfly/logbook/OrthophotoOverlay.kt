package com.highfly.logbook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Legt den mitgelieferten Luftbild-Ausschnitt eines Flughafens auf die
 * CO₂-Karte. Das Bild ist in Web-Mercator (EPSG:3857) abgerufen, in dessen
 * Projektion osmdroid arbeitet; die Karte zeichnet es deshalb allein über die
 * beiden Eckkoordinaten exakt und zoombar auf.
 *
 * Die Ebene sitzt über Land und Grenzen, aber unter [Co2OffsetMapView]-Wald,
 * Orten und Flughafen-Kennzeichen, damit die eigentliche Aussage der Karte
 * sichtbar bleibt. Bei sehr kleiner Zoomstufe (Landesansicht) wäre der
 * Ausschnitt nur ein Punkt und wird ausgelassen.
 */
class OrthophotoOverlay : Overlay() {

    /** Das aktuelle Bild oder `null`, solange keines geladen ist. */
    var orthophoto: Orthophoto? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private val northWest = Point()
    private val southEast = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val photo = orthophoto ?: return
        if (mapView.zoomLevelDouble < MIN_ZOOM) return

        val projection = mapView.projection
        projection.toPixels(GeoPoint(photo.maxLat, photo.minLon), northWest)
        projection.toPixels(GeoPoint(photo.minLat, photo.maxLon), southEast)
        val left = northWest.x.toFloat()
        val top = northWest.y.toFloat()
        val right = southEast.x.toFloat()
        val bottom = southEast.y.toFloat()
        if (right <= left || bottom <= top) return

        destination.set(left, top, right, bottom)
        source.set(0, 0, photo.bitmap.width, photo.bitmap.height)
        canvas.drawBitmap(photo.bitmap, source, destination, paint)
    }

    /**
     * Liegt der sichtbare Ausschnitt vollständig im Luftbild? Dann darf die
     * gezeichnete Landesfläche (Farbton in [Co2OffsetMapView]) entfallen – das
     * Bild zeigt das Land bereits, und der Farbton würde es nur einfärben.
     */
    fun coversViewport(mapView: MapView): Boolean {
        val photo = orthophoto ?: return false
        if (mapView.zoomLevelDouble < MIN_ZOOM) return false
        val box = mapView.boundingBox ?: return false
        return box.latSouth >= photo.minLat && box.latNorth <= photo.maxLat &&
            box.lonWest >= photo.minLon && box.lonEast <= photo.maxLon
    }

    companion object {
        /**
         * Unterhalb dieser Zoomstufe ist der 12-km-Ausschnitt nur noch ein
         * kleiner Fleck; dann bleibt die gezeichnete Offline-Karte stehen.
         */
        const val MIN_ZOOM = 11.0
    }
}
