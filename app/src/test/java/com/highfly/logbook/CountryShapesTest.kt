package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prüft das Binärformat von `country_shapes.bin`. Der Generator
 * (`tools/build_country_shapes.py`) und der Parser hier müssen exakt
 * zusammenpassen - ein Fehler fällt sonst erst auf dem Gerät auf, weil beide
 * Seiten das gleiche falsche Format für richtig halten.
 */
class CountryShapesTest {

    private data class Shape(
        val iso2: String,
        val nameDe: String,
        val nameEn: String,
        val labelLat: Float,
        val labelLon: Float,
        val bbox: FloatArray,
        val rings: List<FloatArray>
    )

    private fun units(degrees: Float): Short =
        Math.round(degrees * 180f).toShort()

    private fun buffer(
        version: Int = 1,
        shapes: List<Shape>
    ): ByteArray {
        val totalPoints = shapes.sumOf { s -> s.rings.sumOf { it.size / 2 } }
        val size = 8 +
            shapes.sumOf { 2 + 8 + 8 + 16 + 1 + it.nameDe.toByteArray().size + 1 + it.nameEn.toByteArray().size } +
            4 +
            shapes.sumOf { it.rings.size } * 8 +
            totalPoints * 4 +
            4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x50485343) // "CSHP"
        buf.put(version.toByte())
        buf.put(0) // reserviert
        buf.putShort(shapes.size.toShort())
        // Der Generator legt alle Ringe aller Länder hintereinander ab; firstRing
        // muss dazu passen, sonst zeigen die Länder auf fremde Umrisse.
        var firstRing = 0
        for (s in shapes) {
            buf.put(s.iso2.toByteArray(Charsets.US_ASCII))
            buf.putInt(firstRing)
            buf.putInt(s.rings.size)
            firstRing += s.rings.size
            buf.putFloat(s.labelLat)
            buf.putFloat(s.labelLon)
            for (v in s.bbox) buf.putFloat(v)
            buf.put(s.nameDe.toByteArray().size.toByte())
            buf.put(s.nameDe.toByteArray(Charsets.UTF_8))
            buf.put(s.nameEn.toByteArray().size.toByte())
            buf.put(s.nameEn.toByteArray(Charsets.UTF_8))
        }
        val ringCount = shapes.sumOf { it.rings.size }
        buf.putInt(ringCount)
        var cursor = 0
        for (s in shapes) {
            for (ring in s.rings) {
                buf.putInt(ring.size / 2)
                buf.putInt(cursor)
                cursor += ring.size / 2
            }
        }
        for (s in shapes) {
            for (ring in s.rings) {
                for (i in 0 until ring.size / 2) {
                    buf.putShort(units(ring[i * 2]))
                    buf.putShort(units(ring[i * 2 + 1]))
                }
            }
        }
        buf.putFloat(-90.0f)
        return buf.array()
    }

    private fun sample() = listOf(
        Shape(
            iso2 = "DE",
            nameDe = "Deutschland",
            nameEn = "Germany",
            labelLat = 51.0f,
            labelLon = 10.0f,
            bbox = floatArrayOf(5.9f, 47.3f, 15.0f, 55.1f),
            rings = listOf(
                floatArrayOf(6.0f, 47.3f, 15.0f, 47.3f, 15.0f, 55.1f, 6.0f, 55.1f)
            )
        ),
        Shape(
            iso2 = "NZ",
            nameDe = "Neuseeland",
            nameEn = "New Zealand",
            labelLat = -41.0f,
            labelLon = 174.0f,
            bbox = floatArrayOf(-178.0f, -52.6f, 178.8f, -8.5f),
            rings = listOf(
                floatArrayOf(172.0f, -41.0f, 174.0f, -41.0f, 174.0f, -39.0f),
                floatArrayOf(-176.0f, -40.0f, -174.0f, -40.0f, -174.0f, -38.0f)
            )
        )
    )

    @Test
    fun parse_liestNamenAnkerUndBoundingBox() {
        val countries = CountryShapes.parse(buffer(shapes = sample()))
        assertEquals(2, countries.size)

        val de = countries.first { it.iso2 == "DE" }
        assertEquals("Deutschland", de.nameDe)
        assertEquals("Germany", de.nameEn)
        assertEquals(51.0, de.labelLat, 0.001)
        assertEquals(10.0, de.labelLon, 0.001)
        assertEquals(5.9, de.bbox[0], 0.001)
        assertEquals(55.1, de.bbox[3], 0.001)
        assertEquals(7.8, de.latSpan, 0.001)
    }

    @Test
    fun parse_liestKoordinatenAusserhalbDesMilligradBereichs() {
        // Der entscheidende Punkt: Längengrade bis ±180 passen nur, wenn sie
        // nicht als Milligrad in einem int16 landen. 179.8 Grad wären als
        // 179800 nicht mehr darstellbar.
        val countries = CountryShapes.parse(buffer(shapes = sample()))
        val nz = countries.first { it.iso2 == "NZ" }
        assertEquals(2, nz.rings.size)
        assertEquals(174.0f, nz.rings[0][2], 0.01f)
        assertEquals(-176.0f, nz.rings[1][0], 0.01f)
        assertEquals(-40.0f, nz.rings[1][1], 0.01f)
    }

    @Test
    fun parse_erhaeltDieRingreihenfolge() {
        val countries = CountryShapes.parse(buffer(shapes = sample()))
        val de = countries.first { it.iso2 == "DE" }
        assertEquals(1, de.rings.size)
        assertEquals(8, de.rings[0].size)
        assertEquals(6.0f, de.rings[0][0], 0.01f)
        assertEquals(55.1f, de.rings[0][5], 0.01f)
    }

    @Test
    fun parse_verwirftRingeMitWenigerAlsDreiPunkten() {
        val shapes = listOf(
            sample()[0].copy(
                rings = listOf(
                    floatArrayOf(6.0f, 47.3f, 15.0f, 55.1f),
                    floatArrayOf(7.0f, 48.0f, 8.0f, 49.0f, 9.0f, 50.0f)
                )
            )
        )
        val de = CountryShapes.parse(buffer(shapes = shapes)).first()
        assertEquals(1, de.rings.size)
        assertEquals(3, de.rings[0].size / 2)
    }

    @Test(expected = IOException::class)
    fun parse_verwirftFalscheKennung() {
        val bytes = buffer(shapes = sample())
        bytes[0] = 0
        CountryShapes.parse(bytes)
    }

    @Test(expected = IOException::class)
    fun parse_verwirftUnbekannteVersion() {
        CountryShapes.parse(buffer(version = 9, shapes = sample()))
    }

    @Test
    fun parse_brichtBeiAbgeschnittenenDatenAb() {
        val full = buffer(shapes = sample())
        assertTrue(
            try {
                CountryShapes.parse(full.copyOf(full.size / 2))
                false
            } catch (expected: IOException) {
                true
            }
        )
    }

    /**
     * Der wichtigste Test: das echte, mitgelieferte Asset muss durch den echten
     * Parser laufen. Ein Fehler im Generator - etwa Koordinaten außerhalb des
     * int16-Bereichs - fällt sonst erst auf dem Gerät auf.
     */
    @Test
    fun parse_liestDasMitgelieferteAsset() {
        val asset = File("src/main/assets/$ASSET_NAME")
        assertTrue("Asset nicht gefunden: ${asset.absolutePath}", asset.exists())
        val countries = CountryShapes.parse(asset.readBytes())

        assertTrue("nur ${countries.size} Länder", countries.size > 200)

        val codes = HashSet<String>()
        for (country in countries) {
            assertTrue("ISO2 '${country.iso2}'", country.iso2.length == 2)
            assertTrue("ISO2 '${country.iso2}' doppelt", codes.add(country.iso2))
            assertTrue("Anker ${country.iso2}", !country.labelLat.isNaN() && !country.labelLon.isNaN())
            assertTrue(
                "Anker ${country.iso2} ausserhalb der Erde",
                country.labelLat in -90.0..90.0 && country.labelLon in -180.0..180.0
            )
            for (value in country.bbox) {
                assertTrue("Bounding-Box ${country.iso2}: $value", !value.isNaN())
                assertTrue("Bounding-Box ${country.iso2}: $value", value in -180.0..180.0)
            }
            // Genau hier schlägt eine zu kleine Koordinatenskala fehl: Werte
            // jenseits von ±32.7 Grad können nicht in einem int16 Milligrad
            // stehen, die ganze Karte wäre dann ein Fleck am Äquator.
            for (ring in country.rings) {
                for (i in 0 until ring.size) {
                    val limit = if (i % 2 == 0) 180.0 else 90.0
                    assertTrue(
                        "Koordinate ${country.iso2} = ${ring[i]}",
                        !ring[i].isNaN() && ring[i].toDouble() in -limit..limit
                    )
                }
            }
        }

        val de = countries.first { it.iso2 == "DE" }
        assertEquals("Deutschland", de.nameDe)
        assertTrue("Deutschland ohne Ringe", de.rings.isNotEmpty())
        assertTrue("Deutschland ${de.labelLat}", de.labelLat in 47.0..56.0)
        assertTrue("Deutschland ${de.labelLon}", de.labelLon in 5.0..16.0)
        assertTrue("Deutschland zu klein", de.latSpan > 5.0)
    }

    private companion object {
        const val ASSET_NAME = "country_shapes.bin"
    }
}
