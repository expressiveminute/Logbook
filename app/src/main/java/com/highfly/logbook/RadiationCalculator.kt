package com.highfly.logbook

import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Schätzt die effektive Dosis aus kosmischer Höhenstrahlung (Höhenstrahlung)
 * für die erfassten Flüge - keine Messung, sondern ein Modell aus den
 * Logbuchdaten: Dosis = Dosisleistung x Flugzeit.
 *
 * Die Dosisleistung in Reiseflughöhe hängt von zwei Grössen ab, die das
 * Logbuch nicht direkt speichert, aber ableiten lässt:
 *  * die Reiseflughöhe - geschätzt aus der Grosskreis-Distanz der Strecke
 *    (Kurzstrecke niedriger, Langstrecke höher, siehe [cruiseAltitudeKm]);
 *  * die geografische Breite der Strecke - Mass für die Abschirmung durch
 *    das Erdmagnetfeld (am Äquator am geringsten, an den Polen etwa doppelt
 *    so hoch), siehe [doseRateUSvPerH].
 *
 * Ankerpunkte des Modells (Quellen siehe Infoseite der Kachel):
 *  * BfS "Höhenstrahlung und Fliegen" (StrahlenschutzKonkret): am Boden ca.
 *    300 µSv pro Jahr, in 11 km Höhe nördlich des 60. Breitengrads 8-9 µSv/h,
 *    Frankfurt-New York hin und zurück ca. 120 µSv;
 *  * FED-Standard der Luftfahrt: 4 µSv/h in 10 km Höhe (Mittel über die
 *    Breitengrade).
 *
 * Die Konstante [REFERENCE_USV_PER_H] liegt mit 3 µSv/h in 10 km zwischen
 * dem Äquatorwert (~2 µSv/h) und dem FED-Mittel; der Höhenverlauf
 * (Faktor e^0,35 je km) und der Breitenfaktor (1 + sin²φ) sind in der
 * Literatur beschriebene Näherungen. Flugzeit und Strecke kommen aus dem
 * Logbucheintrag; die Koordinaten der Strecke aus [AirportData].
 */
object RadiationCalculator {

    /** Referenz-Dosisleistung in µSv/h bei [REFERENCE_ALTITUDE_KM]. */
    const val REFERENCE_USV_PER_H = 3.0

    /** Referenzhöhe der Dosisleistung in km. */
    const val REFERENCE_ALTITUDE_KM = 10.0

    /** Zunahme der Dosisleistung je km Höhe (e^0,35 ≈ Faktor 1,42 je km). */
    const val ALTITUDE_EXPONENT_PER_KM = 0.35

    /** Geschätzte Reiseflughöhe in km bei sehr kurzer Strecke. */
    const val MIN_CRUISE_ALTITUDE_KM = 9.0

    /** Geschätzte Reiseflughöhe in km ab dieser Distanz (Deckel). */
    const val MAX_CRUISE_ALTITUDE_KM = 12.0

    /** Distanz in km, über die die geschätzte Höhe um 1 km steigt. */
    const val CRUISE_ALTITUDE_SPAN_KM = 3000.0

    /**
     * Breite, die angesetzt wird, wenn die Strecke keine Koordinaten hat
     * (mittlere Breite, documented auf der Infoseite).
     */
    const val FALLBACK_LATITUDE_DEG = 45.0

    /**
     * Jahresgrenzwert in µSv (20 mSv): Obergrenze für die jährliche
     * Strahlenbelastung beruflich strahlenexponierter Personen der
     * Kategorie A (darunter das fliegende Personal), Strahlenschutzverordnung.
     * Balken und Prozent der Kachel beziehen sich auf diesen Wert.
     */
    const val ANNUAL_LIMIT_USV = 20_000.0

    /**
     * Anteil von [uSv] am Jahresgrenzwert [ANNUAL_LIMIT_USV] in Prozent,
     * gerundet. Kann über 100 liegen - die Kachel zeigt dann den vollen
     * Balken, den echten Prozentsatz und ein Warndreieck.
     */
    fun percentOfAnnualLimit(uSv: Double): Int =
        (uSv / ANNUAL_LIMIT_USV * 100.0).roundToInt()

    /**
     * Geschätzte Reiseflughöhe aus der Grosskreis-Distanz: 9 km auf der
     * Kurzstrecke, je [CRUISE_ALTITUDE_SPAN_KM] ein km mehr, gedeckelt bei
     * 12 km (erreicht ab 9000 km). Die meisten Jets fliegen Kurzstrecke
     * niedriger als Langstrecke (leichtere Maschine, kürzere Steigphase).
     */
    fun cruiseAltitudeKm(distanceKm: Double): Double =
        MIN_CRUISE_ALTITUDE_KM + (distanceKm / CRUISE_ALTITUDE_SPAN_KM)
            .coerceIn(0.0, MAX_CRUISE_ALTITUDE_KM - MIN_CRUISE_ALTITUDE_KM)

    /**
     * Mittlere Breite der Strecke als Breite des Grosskreis-Mittelpunkts.
     * Mass für die Abschirmung durch das Erdmagnetfeld entlang der Route.
     */
    fun meanLatitude(from: AirportData.GeoLocation, to: AirportData.GeoLocation): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val bx = cos(lat2) * cos(dLon)
        val by = cos(lat2) * sin(dLon)
        return Math.toDegrees(
            atan2(sin(lat1) + sin(lat2), sqrt((cos(lat1) + bx) * (cos(lat1) + bx) + by * by))
        )
    }

    /**
     * Dosisleistung in µSv/h in [altitudeKm] Höhe bei [latitudeDeg] Breite:
     * Referenzwert, Höhenverlauf und Breitenfaktor (1 + sin²φ) - am Äquator
     * einfach, an den Polen etwa doppelt.
     */
    fun doseRateUSvPerH(altitudeKm: Double, latitudeDeg: Double): Double {
        val heightFactor = exp(ALTITUDE_EXPONENT_PER_KM * (altitudeKm - REFERENCE_ALTITUDE_KM))
        val latRad = Math.toRadians(latitudeDeg)
        val latitudeFactor = 1.0 + sin(latRad) * sin(latRad)
        return REFERENCE_USV_PER_H * heightFactor * latitudeFactor
    }

    /**
     * Geschätzte Dosis eines Fluges in µSv. Ground Transfers zählen nicht
     * (kein Flug). Flugzeit aus dem Eintrag, sonst aus der Distanz geschätzt
     * (siehe [GeoMath.flightMinutes]); Strecke aus dem Eintrag, sonst als
     * Grosskreis aus den Flughafenkoordinaten. Ohne beides keine Dosis.
     */
    fun doseUSv(
        entry: LogbookEntry,
        lookup: (String) -> AirportData.GeoLocation?
    ): Double {
        if (ChartData.normalizeFlightType(entry.flightType) == "Ground Transfer") return 0.0
        val from = lookup(entry.fromAirport)
        val to = lookup(entry.toAirport)
        val distanceKm = entry.distanceKm?.takeIf { it > 0 }?.toDouble()
            ?: if (from != null && to != null) GeoMath.distanceKm(from, to) else null
        val minutes = entry.flightMinutes?.takeIf { it > 0 }
            ?: distanceKm?.let { GeoMath.flightMinutes(it) }
            ?: return 0.0
        val altitudeKm = cruiseAltitudeKm(distanceKm ?: 0.0)
        val latitude = if (from != null && to != null) {
            meanLatitude(from, to)
        } else {
            FALLBACK_LATITUDE_DEG
        }
        return doseRateUSvPerH(altitudeKm, latitude) * minutes / 60.0
    }

    /** Gesamtdosis in µSv über alle Einträge. */
    fun totalUSv(
        entries: List<LogbookEntry>,
        lookup: (String) -> AirportData.GeoLocation?
    ): Double = entries.sumOf { doseUSv(it, lookup) }

    /** µSv als mSv formatiert, zwei Nachkommastellen ("0,42"). */
    fun msvText(uSv: Double, locale: Locale = Locale.GERMANY): String =
        String.format(locale, "%,.2f", uSv / 1000.0)
}
