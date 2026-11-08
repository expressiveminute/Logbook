package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prüft das Binärformat von `state_shapes.bin` (Bundesstaaten/-länder). Der
 * Generator (`tools/build_states.py`) und der Parser hier müssen exakt
 * zusammenpassen - ein Fehler fällt sonst erst auf dem Gerät auf. Außerdem wird
 * das echte Asset gelesen und gegen `airports_regions.json` geprüft.
 */
class StateShapesTest {

    private data class Shape(
        val code: String,
        val iso2: String,
        val capLat: Float,
        val capLon: Float,
        val labelLat: Float,
        val labelLon: Float,
        val bbox: FloatArray,
        val nameDe: String,
        val nameEn: String,
        val capDe: String,
        val capEn: String,
        val rings: List<FloatArray>
    )

    private fun units(degrees: Float): Int =
        Math.round(degrees * 1440f)

    private fun buffer(
        version: Int = 2,
        shapes: List<Shape>
    ): ByteArray {
        val totalPoints = shapes.sumOf { s -> s.rings.sumOf { it.size / 2 } }
        val size = 8 +
            shapes.sumOf {
                5 + 2 + 16 + 16 +
                    1 + it.nameDe.toByteArray().size +
                    1 + it.nameEn.toByteArray().size +
                    1 + it.capDe.toByteArray().size +
                    1 + it.capEn.toByteArray().size +
                    8
            } +
            4 +
            shapes.sumOf { it.rings.size } * 8 +
            totalPoints * 8 +
            4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x48535453) // "STSH"
        buf.put(version.toByte())
        buf.put(0) // reserviert
        buf.putShort(shapes.size.toShort())
        var firstRing = 0
        for (s in shapes) {
            buf.put(s.code.padEnd(5).take(5).toByteArray(Charsets.US_ASCII))
            buf.put(s.iso2.toByteArray(Charsets.US_ASCII))
            buf.putFloat(s.capLat)
            buf.putFloat(s.capLon)
            buf.putFloat(s.labelLat)
            buf.putFloat(s.labelLon)
            for (v in s.bbox) buf.putFloat(v)
            buf.put(s.nameDe.toByteArray(Charsets.UTF_8).size.toByte())
            buf.put(s.nameDe.toByteArray(Charsets.UTF_8))
            buf.put(s.nameEn.toByteArray(Charsets.UTF_8).size.toByte())
            buf.put(s.nameEn.toByteArray(Charsets.UTF_8))
            buf.put(s.capDe.toByteArray(Charsets.UTF_8).size.toByte())
            buf.put(s.capDe.toByteArray(Charsets.UTF_8))
            buf.put(s.capEn.toByteArray(Charsets.UTF_8).size.toByte())
            buf.put(s.capEn.toByteArray(Charsets.UTF_8))
            buf.putInt(firstRing)
            buf.putInt(s.rings.size)
            firstRing += s.rings.size
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
                    buf.putInt(units(ring[i * 2]))
                    buf.putInt(units(ring[i * 2 + 1]))
                }
            }
        }
        buf.putFloat(-90.0f)
        return buf.array()
    }

    private fun sample() = listOf(
        Shape(
            code = "DE-BY",
            iso2 = "DE",
            capLat = 48.14f,
            capLon = 11.58f,
            labelLat = 49.0f,
            labelLon = 11.0f,
            bbox = floatArrayOf(8.9f, 47.2f, 13.9f, 50.6f),
            nameDe = "Bayern",
            nameEn = "Bavaria",
            capDe = "München",
            capEn = "Munich",
            rings = listOf(
                floatArrayOf(9.0f, 47.3f, 13.8f, 47.3f, 13.8f, 50.5f, 9.0f, 50.5f)
            )
        ),
        Shape(
            code = "US-CA",
            iso2 = "US",
            capLat = 38.58f,
            capLon = -121.49f,
            labelLat = 37.0f,
            labelLon = -119.0f,
            bbox = floatArrayOf(-124.4f, 32.5f, -114.1f, 42.0f),
            nameDe = "Kalifornien",
            nameEn = "California",
            capDe = "Sacramento",
            capEn = "Sacramento",
            rings = listOf(
                floatArrayOf(-124.0f, 33.0f, -114.2f, 33.0f, -114.2f, 41.9f),
                floatArrayOf(-120.0f, 38.0f, -118.0f, 38.0f, -118.0f, 39.0f)
            )
        )
    )

    @Test
    fun parse_liestCodeNamenHauptstadtUndAnker() {
        val states = StateShapes.parse(buffer(shapes = sample()))
        assertEquals(2, states.size)

        val by = states.first { it.code == "DE-BY" }
        assertEquals("DE", by.iso2)
        assertEquals("Bayern", by.nameDe)
        assertEquals("Bavaria", by.nameEn)
        assertEquals("München", by.capDe)
        assertEquals("Munich", by.capEn)
        assertEquals(48.14, by.capLat, 0.001)
        assertEquals(11.58, by.capLon, 0.001)
        assertEquals(49.0, by.labelLat, 0.001)
        assertEquals(11.0, by.labelLon, 0.001)
        assertEquals(8.9, by.bbox[0], 0.001)
        assertEquals(50.6, by.bbox[3], 0.001)
    }

    @Test
    fun parse_liestKoordinatenAusserhalbDesFeinenRasters() {
        // Längengrade bis ±180 passen nur, wenn sie nicht in ein zu grobes
        // int16-Raster landen; das 1/1440-Grad-Raster braucht int32.
        val ca = StateShapes.parse(buffer(shapes = sample())).first { it.code == "US-CA" }
        assertEquals(2, ca.rings.size)
        assertEquals(-124.0f, ca.rings[0][0], 0.01f)
        assertEquals(-120.0f, ca.rings[1][0], 0.01f)
        assertEquals(41.9f, ca.rings[0][5], 0.01f)
    }

    @Test
    fun parse_verwirftRingeMitWenigerAlsDreiPunkten() {
        val shapes = listOf(
            sample()[0].copy(
                rings = listOf(
                    floatArrayOf(9.0f, 47.3f, 13.8f, 50.5f),
                    floatArrayOf(9.5f, 48.0f, 10.5f, 49.0f, 11.5f, 50.0f)
                )
            )
        )
        val by = StateShapes.parse(buffer(shapes = shapes)).first()
        assertEquals(1, by.rings.size)
        assertEquals(3, by.rings[0].size / 2)
    }

    @Test(expected = IOException::class)
    fun parse_verwirftFalscheKennung() {
        val bytes = buffer(shapes = sample())
        bytes[0] = 0
        StateShapes.parse(bytes)
    }

    @Test(expected = IOException::class)
    fun parse_verwirftUnbekannteVersion() {
        StateShapes.parse(buffer(version = 9, shapes = sample()))
    }

    @Test
    fun parse_brichtBeiAbgeschnittenenDatenAb() {
        val full = buffer(shapes = sample())
        assertTrue(
            try {
                StateShapes.parse(full.copyOf(full.size / 2))
                false
            } catch (expected: IOException) {
                true
            }
        )
    }

    @Test
    fun unitRings_bildenEinheitsvektorenJeRingpunkt() {
        val states = StateShapes.parse(buffer(shapes = sample()))
        for (state in states) {
            assertEquals(state.rings.size, state.unitRings.size)
            for (r in state.rings.indices) {
                val ring = state.rings[r]
                val units = state.unitRings[r]
                assertEquals(ring.size / 2, units.size / 3)
                for (i in 0 until ring.size / 2) {
                    val lat = Math.toRadians(ring[i * 2 + 1].toDouble())
                    val lon = Math.toRadians(ring[i * 2].toDouble())
                    val laenge = kotlin.math.sqrt(
                        units[i * 3] * units[i * 3] +
                            units[i * 3 + 1] * units[i * 3 + 1] +
                            units[i * 3 + 2] * units[i * 3 + 2]
                    )
                    assertEquals(1.0, laenge, 1e-12)
                }
            }
        }
    }

    /**
     * Das echte, mitgelieferte Asset muss durch den echten Parser laufen und zu
     * `airports_regions.json` passen: jede Region dort muss einen Staat im Asset
     * haben, und jeder Staat eine Hauptstadt.
     */
    @Test
    fun parse_liestDasMitgelieferteAsset() {
        val asset = File("src/main/assets/${StateShapes.ASSET}")
        assertTrue("Asset nicht gefunden: ${asset.absolutePath}", asset.exists())
        val states = StateShapes.parse(asset.readBytes())

        // 50 US-Bundesstaaten + DC und 16 deutsche Bundesländer.
        assertEquals(51, states.count { it.iso2 == "US" })
        assertEquals(16, states.count { it.iso2 == "DE" })

        val codes = HashSet<String>()
        for (state in states) {
            assertTrue("Code '${state.code}'", state.code.matches(Regex("(US|DE)-[A-Z0-9]{1,3}")))
            assertTrue("Code '${state.code}' doppelt", codes.add(state.code))
            // Masken der gemeinsamen Grenzpunkte parallel zu den Ringen
            assertEquals("Masken ${state.code}", state.rings.size, state.sharedMasks.size)
            assertTrue("Name ${state.code}", state.nameDe.isNotBlank() && state.nameEn.isNotBlank())
            assertTrue("Hauptstadt ${state.code}", state.capDe.isNotBlank() && state.capEn.isNotBlank())
            assertTrue(
                "Hauptstadt-Koordinaten ${state.code}",
                state.capLat in -90.0..90.0 && state.capLon in -180.0..180.0
            )
            for (ring in state.rings) {
                for (i in 0 until ring.size) {
                    val limit = if (i % 2 == 0) 180.0 else 90.0
                    assertTrue(
                        "Koordinate ${state.code} = ${ring[i]}",
                        !ring[i].isNaN() && ring[i].toDouble() in -limit..limit
                    )
                }
            }
        }

        // Die Flughafen-Regionen müssen auf Staaten des Assets zeigen.
        val regionsFile = File("src/main/assets/airports/airports_regions.json")
        assertTrue(regionsFile.exists())
        val regionsJson = regionsFile.readText()
        val regionPattern = Regex(""""([A-Z]{3})":\s*"((?:US|DE)-[A-Z0-9]{1,3})"""")
        val regions = regionPattern.findAll(regionsJson).associate {
            it.groupValues[1] to it.groupValues[2]
        }
        assertTrue("zu wenige Regionen", regions.size > 1000)
        for ((iata, code) in regions) {
            assertTrue("Region $code von $iata ohne Staat im Asset", codes.contains(code))
        }
        // Bekannte Zuordnungen: FRA -> Hessen, MUC -> Bayern, JFK -> New York.
        assertEquals("DE-HE", regions["FRA"])
        assertEquals("DE-BY", regions["MUC"])
        assertEquals("US-NY", regions["JFK"])
    }

    @Test
    fun sharedMasks_gemeinsameStaatenGrenzenSindBitIdentisch() {
        val asset = File("src/main/assets/${StateShapes.ASSET}")
        val states = StateShapes.parse(asset.readBytes())

        fun keyOf(lonDeg: Float, latDeg: Float): Long {
            val x = Math.round(lonDeg * 1440f)
            val y = Math.round(latDeg * 1440f)
            return (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        }

        val keysPerState = HashMap<String, HashSet<Long>>()
        val users = HashMap<Long, Int>()
        for (st in states) {
            val local = HashSet<Long>()
            for (ring in st.rings) {
                for (i in 0 until ring.size step 2) local.add(keyOf(ring[i], ring[i + 1]))
            }
            keysPerState[st.code] = local
            for (k in local) users[k] = (users[k] ?: 0) + 1
        }
        // Stichprobe Texas/Oklahoma und Bayern/Baden-Württemberg: gemeinsame
        // Grenz-Vertices müssen in beiden Staaten liegen und dort markiert sein.
        for ((a, b) in listOf("US-TX" to "US-OK", "US-TX" to "US-NM", "DE-BY" to "DE-BW")) {
            val ka = keysPerState.getValue(a)
            val kb = keysPerState.getValue(b)
            val shared = ka.intersect(kb)
            assertTrue("$a/$b teilen nur ${shared.size} Vertices", shared.size > 20)
            fun masked(code: String): Set<Long> {
                val st = states.first { it.code == code }
                val out = HashSet<Long>()
                for (r in st.rings.indices) {
                    for (p in st.sharedMasks[r].indices) {
                        if (st.sharedMasks[r][p]) out.add(keyOf(st.rings[r][p * 2], st.rings[r][p * 2 + 1]))
                    }
                }
                return out
            }
            val ma = masked(a)
            val mb = masked(b)
            for (k in shared) {
                assertTrue("$a/$b Grenzpunkt nicht in Maske $a", ma.contains(k))
                assertTrue("$a/$b Grenzpunkt nicht in Maske $b", mb.contains(k))
            }
        }
    }
}
