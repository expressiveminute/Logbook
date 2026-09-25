package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CountryBordersTest {

    /** Eine Grenzlinie zum Testen: Bounding-Box und Koordinaten getrennt. */
    private class TestLine(val bbox: FloatArray, val points: FloatArray)

    private fun buffer(
        version: Int = 1,
        levels: List<Pair<ClosedFloatingPointRange<Double>, List<TestLine>>> =
            listOf(1.0..5.0 to emptyList())
    ): ByteArray {
        var size = 6 + levels.size * 24 + 4
        for ((_, lines) in levels) {
            size += lines.size * 20 + lines.sumOf { it.points.size / 2 * 8 }
        }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x444E4243)
        buf.put(version.toByte())
        buf.put(levels.size.toByte())
        for ((zoom, lines) in levels) {
            buf.putDouble(zoom.start)
            buf.putDouble(zoom.endInclusive)
            buf.putInt(lines.size)
            buf.putInt(lines.sumOf { it.points.size / 2 })
            for (line in lines) {
                for (value in line.bbox) buf.putFloat(value)
                buf.putInt(line.points.size / 2)
                for (value in line.points) buf.putFloat(value)
            }
        }
        buf.putFloat(-90.0f)
        return buf.array()
    }

    private fun emptyLevel(minZoom: Double, maxZoom: Double) = CountryBorders.Level(
        minZoom, maxZoom, 0, FloatArray(0), IntArray(0), IntArray(0), FloatArray(0)
    )

    @Test
    fun parse_readsLinesWithBboxAndCoordinates() {
        val first = TestLine(
            bbox = floatArrayOf(5f, 46f, 12f, 50f),
            points = floatArrayOf(7f, 48f, 8f, 49f)
        )
        val second = TestLine(
            bbox = floatArrayOf(11f, 45f, 13f, 48f),
            points = floatArrayOf(12f, 46f, 13f, 47f)
        )
        val levels = CountryBorders.parse(
            buffer(levels = listOf(5.0..7.0 to listOf(first, second)))
        )

        assertEquals(1, levels.size)
        val level = levels.first()
        assertEquals(5.0, level.minZoom, 0.0)
        assertEquals(7.0, level.maxZoom, 0.0)
        assertEquals(2, level.lineCount)
        assertEquals(5f, level.bbox[0], 0f)
        assertEquals(50f, level.bbox[3], 0f)
        assertEquals(listOf(0, 2), level.starts.toList())
        assertEquals(listOf(2, 2), level.counts.toList())
        assertEquals(7f, level.coords[0], 0f)
        assertEquals(13f, level.coords[6], 0f)
    }

    @Test(expected = IOException::class)
    fun parse_rejectsWrongMagic() {
        val bytes = buffer()
        bytes[0] = 0
        CountryBorders.parse(bytes)
    }

    @Test(expected = IOException::class)
    fun parse_rejectsUnsupportedVersion() {
        CountryBorders.parse(buffer(version = 9))
    }

    @Test
    fun selectLevel_picksMatchingDetailAndClampsOutOfRange() {
        val levels = listOf(
            emptyLevel(1.0, 5.0),
            emptyLevel(5.0, 7.0),
            emptyLevel(7.0, 10.0)
        )
        assertEquals(1.0, CountryBorders.selectLevel(levels, 2.0).minZoom, 0.0)
        assertEquals(5.0, CountryBorders.selectLevel(levels, 6.0).minZoom, 0.0)
        assertEquals(7.0, CountryBorders.selectLevel(levels, 9.0).minZoom, 0.0)
        assertEquals(1.0, CountryBorders.selectLevel(levels, 0.2).minZoom, 0.0)
        assertEquals(7.0, CountryBorders.selectLevel(levels, 42.0).minZoom, 0.0)
    }
}
