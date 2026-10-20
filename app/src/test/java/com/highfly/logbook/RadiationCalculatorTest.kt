package com.highfly.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RadiationCalculatorTest {

    private val fra = AirportData.GeoLocation(50.0333, 8.5706)
    private val jfk = AirportData.GeoLocation(40.6413, -73.7781)

    private val lookup: (String) -> AirportData.GeoLocation? = { code ->
        when (code) {
            "FRA" -> fra
            "JFK" -> jfk
            else -> null
        }
    }

    private fun entry(
        date: LocalDate = LocalDate.of(2026, 3, 15),
        from: String = "FRA",
        to: String = "JFK",
        flightMinutes: Int? = 60,
        distanceKm: Int? = 1000,
        flightType: String = "Privat"
    ) = LogbookEntry(
        date = date,
        flightType = flightType,
        fromAirport = from,
        toAirport = to,
        distanceKm = distanceKm,
        flightMinutes = flightMinutes
    )

    @Test
    fun cruiseAltitudeKm_steigt_von_9_auf_12_km_und_deckelt() {
        assertEquals(9.0, RadiationCalculator.cruiseAltitudeKm(0.0), 1e-9)
        assertEquals(9.166666666666666, RadiationCalculator.cruiseAltitudeKm(500.0), 1e-9)
        assertEquals(9.5, RadiationCalculator.cruiseAltitudeKm(1500.0), 1e-9)
        assertEquals(10.0, RadiationCalculator.cruiseAltitudeKm(3000.0), 1e-9)
        assertEquals(12.0, RadiationCalculator.cruiseAltitudeKm(9000.0), 1e-9)
        assertEquals(12.0, RadiationCalculator.cruiseAltitudeKm(20000.0), 1e-9)
    }

    @Test
    fun meanLatitude_liegt_am_Grosskreis_Mittelpunkt_und_ist_richtungssymmetrisch() {
        val midEquator = RadiationCalculator.meanLatitude(
            AirportData.GeoLocation(0.0, 0.0),
            AirportData.GeoLocation(0.0, 90.0)
        )
        assertEquals(0.0, midEquator, 1e-9)
        val midFraJfk = RadiationCalculator.meanLatitude(fra, jfk)
        assertTrue(midFraJfk > 50.0 && midFraJfk < 60.0)
        assertEquals(midFraJfk, RadiationCalculator.meanLatitude(jfk, fra), 1e-9)
        val muc = AirportData.GeoLocation(48.3538, 11.7861)
        val midFraMuc = RadiationCalculator.meanLatitude(fra, muc)
        assertTrue(midFraMuc > 48.3 && midFraMuc < 50.1)
        val midPolar = RadiationCalculator.meanLatitude(
            AirportData.GeoLocation(80.0, 0.0),
            AirportData.GeoLocation(80.0, 180.0)
        )
        assertTrue(midPolar > 70.0)
    }

    @Test
    fun doseRateUSvPerH_waechst_mit_Hoehe_und_Breite() {
        assertEquals(3.0, RadiationCalculator.doseRateUSvPerH(10.0, 0.0), 1e-9)
        assertEquals(6.0, RadiationCalculator.doseRateUSvPerH(10.0, 90.0), 1e-9)
        assertEquals(
            3.0 * Math.exp(0.35),
            RadiationCalculator.doseRateUSvPerH(11.0, 0.0),
            1e-9
        )
        assertEquals(1.75, RadiationCalculator.doseRateUSvPerH(10.0, 60.0) / 3.0, 1e-9)
    }

    @Test
    fun doseUSv_rechnet_Dosisleistung_mal_Flugzeit() {
        val short = entry(flightMinutes = 480, distanceKm = 6200)
        val altitude = RadiationCalculator.cruiseAltitudeKm(6200.0)
        assertTrue(altitude > 11.0 && altitude < 12.0)
        val expected = RadiationCalculator.doseRateUSvPerH(
            altitude,
            RadiationCalculator.meanLatitude(fra, jfk)
        ) * 480.0 / 60.0
        assertEquals(expected, RadiationCalculator.doseUSv(short, lookup), 1e-9)
        val long = entry(flightMinutes = 480, distanceKm = 9000)
        val expectedLong = RadiationCalculator.doseRateUSvPerH(
            12.0,
            RadiationCalculator.meanLatitude(fra, jfk)
        ) * 480.0 / 60.0
        assertEquals(expectedLong, RadiationCalculator.doseUSv(long, lookup), 1e-9)
    }

    @Test
    fun doseUSv_ist_0_bei_Ground_Transfer() {
        val transfer = entry(flightMinutes = 480, distanceKm = 6200, flightType = "Ground Transfer")
        assertEquals(0.0, RadiationCalculator.doseUSv(transfer, lookup), 1e-9)
    }

    @Test
    fun doseUSv_schaetzt_fehlende_Flugzeit_aus_der_Distanz() {
        val e = entry(flightMinutes = null, distanceKm = 805)
        val altitude = RadiationCalculator.cruiseAltitudeKm(805.0)
        val expected = RadiationCalculator.doseRateUSvPerH(
            altitude,
            RadiationCalculator.meanLatitude(fra, jfk)
        ) * 85.0 / 60.0
        assertEquals(expected, RadiationCalculator.doseUSv(e, lookup), 1e-9)
    }

    @Test
    fun doseUSv_nutzt_Koordinaten_wenn_Strecke_und_Zeit_fehlen() {
        val e = entry(flightMinutes = null, distanceKm = null)
        val distance = GeoMath.distanceKm(fra, jfk)
        val expected = RadiationCalculator.doseRateUSvPerH(
            RadiationCalculator.cruiseAltitudeKm(distance),
            RadiationCalculator.meanLatitude(fra, jfk)
        ) * GeoMath.flightMinutes(distance).toDouble() / 60.0
        assertEquals(expected, RadiationCalculator.doseUSv(e, lookup), 1e-9)
    }

    @Test
    fun doseUSv_ist_0_ohne_Zeit_und_Strecke() {
        val e = entry(from = "XXX", to = "YYY", flightMinutes = null, distanceKm = null)
        assertEquals(0.0, RadiationCalculator.doseUSv(e, lookup), 1e-9)
    }

    @Test
    fun doseUSv_ist_an_den_Polen_hoeher_als_am_Aequator() {
        val polar = entry(from = "N90", to = "N91", flightMinutes = 120, distanceKm = 500)
        val equator = entry(from = "E00", to = "E01", flightMinutes = 120, distanceKm = 500)
        val lookup: (String) -> AirportData.GeoLocation? = { code ->
            when (code) {
                "N90" -> AirportData.GeoLocation(80.0, 0.0)
                "N91" -> AirportData.GeoLocation(80.0, 90.0)
                "E00" -> AirportData.GeoLocation(0.0, 0.0)
                "E01" -> AirportData.GeoLocation(0.0, 90.0)
                else -> null
            }
        }
        assertTrue(
            RadiationCalculator.doseUSv(polar, lookup) >
                RadiationCalculator.doseUSv(equator, lookup)
        )
    }

    @Test
    fun byYear_gruppiert_nach_Jahr_neustes_zuerst_ohne_Ground_Transfer() {
        val entries = listOf(
            entry(date = LocalDate.of(2024, 6, 1), flightMinutes = 60, distanceKm = 1000),
            entry(date = LocalDate.of(2026, 1, 10), flightMinutes = 120, distanceKm = 1000),
            entry(date = LocalDate.of(2026, 2, 20), flightMinutes = 30, distanceKm = 1000),
            entry(
                date = LocalDate.of(2026, 3, 1),
                flightMinutes = 600,
                distanceKm = 1000,
                flightType = "Ground Transfer"
            )
        )
        val years = RadiationStats.byYear(entries, lookup)
        assertEquals(listOf(2026, 2024), years.map { it.year })
        assertEquals(150, years[0].minutes)
        assertEquals(60, years[1].minutes)
        val dose2026 = RadiationCalculator.doseUSv(entries[1], lookup) +
            RadiationCalculator.doseUSv(entries[2], lookup)
        assertEquals(dose2026, years[0].uSv, 1e-9)
    }

    @Test
    fun summary_nimmt_das_laufende_Jahr_und_summiert_alle_Jahre() {
        val entries = listOf(
            entry(date = LocalDate.of(2024, 6, 1), flightMinutes = 60, distanceKm = 1000),
            entry(date = LocalDate.of(2026, 1, 10), flightMinutes = 120, distanceKm = 1000)
        )
        val summary = RadiationStats.summary(entries, lookup, LocalDate.of(2026, 10, 19))
        assertEquals(2026, summary.year)
        assertEquals(
            RadiationCalculator.doseUSv(entries[1], lookup),
            summary.yearUSv,
            1e-9
        )
        assertEquals(
            summary.yearUSv + RadiationCalculator.doseUSv(entries[0], lookup),
            summary.totalUSv,
            1e-9
        )
        assertEquals(listOf(2026, 2024), summary.years.map { it.year })
    }

    @Test
    fun msvText_formatiert_Mikrosievert_als_Millisievert() {
        assertEquals("0,42", RadiationCalculator.msvText(420.0, java.util.Locale.GERMANY))
        assertEquals("0.42", RadiationCalculator.msvText(420.0, java.util.Locale.ENGLISH))
        assertEquals("1,23", RadiationCalculator.msvText(1234.0, java.util.Locale.GERMANY))
        assertEquals("0,00", RadiationCalculator.msvText(0.0, java.util.Locale.GERMANY))
    }

    @Test
    fun annualLimit_ist_20_mSv() {
        assertEquals(20_000.0, RadiationCalculator.ANNUAL_LIMIT_USV, 0.0)
    }

    @Test
    fun percentOfAnnualLimit_steigt_mit_der_Dosis() {
        assertEquals(0, RadiationCalculator.percentOfAnnualLimit(0.0))
        assertEquals(50, RadiationCalculator.percentOfAnnualLimit(10_000.0))
        assertEquals(100, RadiationCalculator.percentOfAnnualLimit(20_000.0))
    }

    @Test
    fun percentOfAnnualLimit_kann_ueber_100_liegen() {
        assertEquals(130, RadiationCalculator.percentOfAnnualLimit(26_000.0))
    }

    @Test
    fun percentOfAnnualLimit_rundet_auf_ganze_Prozent() {
        // 0,1 mSv = 0,5 % - gerundet 1 %.
        assertEquals(1, RadiationCalculator.percentOfAnnualLimit(100.0))
    }
}
