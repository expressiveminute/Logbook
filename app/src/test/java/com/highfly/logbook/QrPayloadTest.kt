package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Prüft den QR-Payload der Bordkarte ([QrPayload]): Encode/Decode-Roundtrip,
 * dass Reisebuddies nicht im Code landen, und dass kaputte oder fremde
 * Scans sauber abgelehnt werden.
 */
class QrPayloadTest {

    private fun sampleEntry() = LogbookEntry(
        date = LocalDate.of(2026, 3, 12),
        flightType = "Privat",
        classType = "Economy",
        seat = "24D",
        fromAirport = "FRA",
        toAirport = "JFK",
        airline = "LH",
        flightNumber = "400",
        aircraftType = "Airbus A340-600",
        registration = "D-AIHA",
        distanceKm = 6204,
        flightMinutes = 495,
        layover = true,
        layoverHours = 8,
        function = "Purser I",
        travelBuddy = "Anna, Berta",
        comment = "Schöner Flug! \"Danke\"\nZweite Zeile 😀"
    )

    @Test
    fun roundtrip_alleFelderAusserBuddy() {
        val encoded = QrPayload.encode(sampleEntry())
        assertTrue(encoded.startsWith(QrPayload.PREFIX))
        // Reisebuddy darf nicht im Payload stehen.
        assertFalse(encoded.contains("Anna"))
        assertFalse(encoded.contains("Berta"))

        val decoded = QrPayload.decode(encoded)
        assertEquals(sampleEntry().date, decoded?.date)
        assertEquals("Privat", decoded?.flightType)
        assertEquals("Economy", decoded?.classType)
        assertEquals("24D", decoded?.seat)
        assertEquals("FRA", decoded?.fromAirport)
        assertEquals("JFK", decoded?.toAirport)
        assertEquals("LH", decoded?.airline)
        assertEquals("400", decoded?.flightNumber)
        assertEquals("Airbus A340-600", decoded?.aircraftType)
        assertEquals("D-AIHA", decoded?.registration)
        assertEquals(6204, decoded?.distanceKm)
        assertEquals(495, decoded?.flightMinutes)
        assertTrue(decoded?.layover == true)
        assertEquals(8, decoded?.layoverHours)
        assertEquals("Purser I", decoded?.function)
        assertEquals("Schöner Flug! \"Danke\"\nZweite Zeile 😀", decoded?.comment)
        // Buddy kommt nicht mit zurück.
        assertNull(decoded?.travelBuddy)
    }

    @Test
    fun roundtrip_leererEintrag() {
        val entry = LogbookEntry(
            date = LocalDate.of(2026, 1, 1),
            fromAirport = "MUC",
            toAirport = "HAM"
        )
        val decoded = QrPayload.decode(QrPayload.encode(entry))
        assertEquals(entry.date, decoded?.date)
        assertEquals("MUC", decoded?.fromAirport)
        assertEquals("HAM", decoded?.toAirport)
        assertNull(decoded?.airline)
        assertNull(decoded?.distanceKm)
        assertFalse(decoded?.layover == true)
        assertNull(decoded?.comment)
    }

    @Test
    fun decode_fremdesPraefix_oder_keins_istNull() {
        assertNull(QrPayload.decode(null))
        assertNull(QrPayload.decode(""))
        assertNull(QrPayload.decode("https://example.com"))
        assertNull(QrPayload.decode("LOGBOOK2|{\"from\":\"FRA\"}"))
        assertNull(QrPayload.decode("LOGBOOK1|"))
        assertNull(QrPayload.decode("LOGBOOK1|keine json"))
        assertNull(QrPayload.decode("LOGBOOK1|{}"))
        assertNull(QrPayload.decode("LOGBOOK1|{\"d\":\"12.03.2026\",\"from\":\"FRA\",\"to\":\"JFK\"}"))
        assertNull(QrPayload.decode("LOGBOOK1|{\"d\":\"2026-03-12\"}"))
        assertNull(QrPayload.decode("LOGBOOK1|{\"d\":\"2026-03-12\",\"from\":\"\",\"to\":\"JFK\"}"))
        assertNull(QrPayload.decode("LOGBOOK1|{\"d\":\"2026-13-40\",\"from\":\"FRA\",\"to\":\"JFK\"}"))
    }

    @Test
    fun decode_unbekannteSchluessel_werdenUebersprungen() {
        val text = QrPayload.PREFIX + "{\"d\":\"2026-03-12\",\"from\":\"FRA\",\"to\":\"JFK\"," +
            "\"future\":{\"a\":1},\"km\":100}"
        // "future" ist ein Objekt - flaches Parsen scheitert daran => null.
        assertNull(QrPayload.decode(text))

        val flat = QrPayload.PREFIX + "{\"d\":\"2026-03-12\",\"from\":\"FRA\",\"to\":\"JFK\"," +
            "\"neu\":\"irgendwas\",\"km\":100}"
        val decoded = QrPayload.decode(flat)
        assertEquals("FRA", decoded?.fromAirport)
        assertEquals(100, decoded?.distanceKm)
        assertNull(decoded?.airline)
    }

    @Test
    fun decode_sonderzeichenImKommentar() {
        val entry = LogbookEntry(
            date = LocalDate.of(2026, 5, 1),
            fromAirport = "FRA",
            toAirport = "SIN",
            comment = "a\"b\\c\nd\te😀 üöä"
        )
        val decoded = QrPayload.decode(QrPayload.encode(entry))
        assertEquals(entry.comment, decoded?.comment)
    }
}
