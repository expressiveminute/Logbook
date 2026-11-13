package com.highfly.logbook

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * Erzeugt den QR-Code auf der Bordkarte als Bitmap (ZXing core, reiner
 * Java-Encoder - ohne Kamera-Teil). Fehlerkorrektur hoch (H), damit der Code
 * auch bei ungünstigem Licht/auf dem Display des anderen Handys zuverlässig
 * scannbar ist; ein so kleiner Payload verträgt das problemlos.
 */
object QrCode {

    /**
     * Rendert [text] als quadratischen QR-Code. Liefert null, wenn der Text
     * für einen QR-Code zu lang ist oder ein anderer Fehler auftritt.
     */
    fun qrBitmap(text: String, sizePx: Int): Bitmap? = try {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
            // Quiet Zone (weißer Rand) direkt im quadratischen Bitmap, damit
            // der Rand mittig im Bild sitzt und auf allen Seiten gleich breit ist.
            put(EncodeHintType.MARGIN, 4)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(
                    x, y,
                    if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
