package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Allgemeine Stammdaten der Fluggesellschaften aus
 * `assets/airlines/airline_facts.json` (erzeugt von
 * `tools/build_airline_facts.py` aus Wikidata und Wikipedia): Airlinegruppe,
 * Gründung, Besitzform, Insolvenzjahr, Sitz, Hubs, Flottengröße, Allianz,
 * Rufzeichen und der Link zur englischen Wikipedia-Seite. Diese Daten hängen
 * nicht vom Logbuch des Nutzers ab und stehen auch für Airlines ohne eigenen
 * Flug; auf der Detailseite erscheinen sie über der Streckenkarte.
 *
 * Geladen wird einmalig, der Bestand ändert sich nicht. Schlüssel ist der
 * IATA-Code - derselbe kanonische Schlüssel wie im [AirlineCatalog].
 */
object AirlineFacts {

    private const val TAG = "AirlineFacts"
    private const val ASSET = "airlines/airline_facts.json"

    /** Besitzform "Staatlich" (im Asset der Schlüssel `state`). */
    const val OWNERSHIP_STATE = "state"

    /** Besitzform "Aktiengesellschaft" (im Asset der Schlüssel `public`). */
    const val OWNERSHIP_PUBLIC = "public"

    /** Besitzform "Privat" (im Asset der Schlüssel `private`). */
    const val OWNERSHIP_PRIVATE = "private"

    /**
     * @param hq Sitz in englischer Schreibweise (z. B. "Cologne")
     * @param hqDe Sitz auf Deutsch, wenn abweichend (z. B. "Köln"), sonst null
     * @param hubs Hub-Flughäfen als IATA-Codes
     * @param fleet Flottengröße als Zahl, wenn bekannt
     * @param dissolved Jahr der Insolvenz/Auflösung, wenn die Airline pleite ist
     * @param wiki Link zur englischen Wikipedia-Seite der Airline
     * @param group Airlinegruppe (Name des Mutterkonzerns, z. B. "Lufthansa Group")
     * @param ownership Besitzform als Schlüssel: [OWNERSHIP_STATE],
     *   [OWNERSHIP_PUBLIC] oder [OWNERSHIP_PRIVATE]
     */
    data class Facts(
        val founded: Int?,
        val hq: String?,
        val hqDe: String?,
        val hubs: List<String>,
        val fleet: Int?,
        val alliance: String?,
        val callsign: String?,
        val dissolved: Int? = null,
        val wiki: String? = null,
        val group: String? = null,
        val ownership: String? = null
    ) {
        /** Anzeigename des Sitzes: deutsch, wenn hinterlegt, sonst englisch. */
        val displayHq: String?
            get() = hqDe ?: hq
    }

    @Volatile
    private var cached: Map<String, Facts>? = null

    /** Lädt das Asset einmalig und teilt es zwischen allen Aufrufern. */
    fun load(context: Context): Map<String, Facts> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                val text = context.assets.open(ASSET)
                    .bufferedReader()
                    .use { it.readText() }
                val root = JSONObject(text)
                val result = HashMap<String, Facts>(root.length())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val iata = keys.next()
                    val fields = root.getJSONObject(iata)
                    result[iata] = Facts(
                        founded = fields.optInt("founded").takeIf { it > 0 },
                        hq = fields.optString("hq").takeIf(String::isNotBlank),
                        hqDe = fields.optString("hq_de").takeIf(String::isNotBlank),
                        hubs = fields.optJSONArray("hubs")?.let { array ->
                            (0 until array.length()).mapNotNull { index ->
                                array.optString(index).takeIf(String::isNotBlank)
                            }
                        } ?: emptyList(),
                        fleet = fields.optInt("fleet").takeIf { it > 0 },
                        alliance = fields.optString("alliance").takeIf(String::isNotBlank),
                        callsign = fields.optString("callsign").takeIf(String::isNotBlank),
                        dissolved = fields.optInt("dissolved").takeIf { it > 0 },
                        wiki = fields.optString("wiki").takeIf(String::isNotBlank),
                        group = fields.optString("group").takeIf(String::isNotBlank),
                        ownership = fields.optString("ownership").takeIf(String::isNotBlank)
                    )
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Airline-Stammdaten konnten nicht geladen werden", e)
                emptyMap()
            }
            cached = parsed
            return parsed
        }
    }

    /** Stammdaten zum IATA-Code; `null`, wenn die Airline keine hat. */
    fun get(context: Context, iata: String): Facts? =
        load(context)[iata.trim().uppercase()]

    /**
     * String-Resource der Besitzform für die Detailseite: aus dem Schlüssel
     * im Asset ("state", "public", "private") wird die übersetzte Beschriftung
     * ("Staatlich", "Aktiengesellschaft", "Privat"). Ein unbekannter oder
     * fehlender Schlüssel ergibt null - die Zeile bleibt dann weg.
     */
    fun ownershipLabelRes(ownership: String?): Int? = when (ownership) {
        OWNERSHIP_STATE -> R.string.airline_ownership_state
        OWNERSHIP_PUBLIC -> R.string.airline_ownership_public
        OWNERSHIP_PRIVATE -> R.string.airline_ownership_private
        else -> null
    }

    /**
     * Rufzeichen zur Anzeige unter den Codes: aus den gespeicherten
     * Grossbuchstaben ("SPEEDBIRD", "ALL NIPPON") wird je Wort ein grosser
     * Anfangsbuchstabe ("Speedbird", "All Nippon").
     */
    fun displayCallsign(callsign: String?): String? {
        if (callsign.isNullOrBlank()) return null
        return callsign.trim().split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }

    /**
     * Anzeigename des Wikipedia-Artikels aus der URL:
     * ".../wiki/British_Airways" wird zu "British Airways". Percent-Codes
     * werden als UTF-8 dekodiert, Unterstriche zu Leerzeichen.
     */
    fun wikiTitle(url: String): String {
        val segment = url.substringAfterLast('/')
        val bytes = ArrayList<Byte>(segment.length)
        var index = 0
        while (index < segment.length) {
            val c = segment[index]
            // Ungueltige Percent-Codes bleiben als Text stehen, statt zu
            // fehlschlagen.
            val hex = if (c == '%' && index + 2 < segment.length) {
                segment.substring(index + 1, index + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                bytes.add(hex.toByte())
                index += 3
            } else {
                bytes.add(c.code.toByte())
                index += 1
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).replace('_', ' ')
    }
}
