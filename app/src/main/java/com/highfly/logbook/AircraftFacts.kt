package com.highfly.logbook

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Allgemeine Stammdaten der Flugzeugtypen aus
 * `assets/aircraft/aircraft_facts.json` (erzeugt von
 * `tools/build_aircraft_facts.py` aus der englischen Wikipedia): Hersteller,
 * Baubeginn, Erstauslieferung (Datum und Erstkunde) und der Link zur
 * englischen Wikipedia-Seite. Diese Daten hängen nicht vom Logbuch des
 * Nutzers ab; auf der Detailseite eines Typs (Hangar) erscheinen sie über
 * der Streckenkarte.
 *
 * Geladen wird einmalig, der Bestand ändert sich nicht. Schlüssel ist der
 * kanonische Typ-Code ("A320", "B737", "Concorde") - derselbe Code, den
 * [Catalog.resolve] aus dem eingetragenen Typ ermittelt und unter dem das
 * Foto in `assets/aircraft/photos/` liegt.
 */
object AircraftFacts {

    private const val TAG = "AircraftFacts"
    private const val ASSET = "aircraft/aircraft_facts.json"

    /**
     * @param name Anzeigename ("Airbus A320")
     * @param manufacturer Hersteller ("Airbus")
     * @param productionStart Baubeginn als Jahr
     * @param firstDelivery Erstauslieferung als "YYYY-MM-DD", "YYYY-MM" oder "YYYY"
     * @param launchCustomer Erstkunde ("Air France")
     * @param wiki Link zur englischen Wikipedia-Seite des Typs
     */
    data class Facts(
        val name: String,
        val manufacturer: String?,
        val productionStart: Int?,
        val firstDelivery: String?,
        val launchCustomer: String?,
        val wiki: String?
    )

    /** Stammdaten je kanonischem Typ-Code und Alias-Code je Typ-Code. */
    data class Catalog(
        val types: Map<String, Facts>,
        val aliases: Map<String, String>
    ) {

        /**
         * Kanonische Codes in Grossbuchstaben samt Originalschreibweise,
         * längste zuerst - für die Präfix-Suche. Die Schlüsselschreibweise
         * ist gemischt ("Saab340"), die Normalisierung arbeitet aber mit
         * Grossbuchstaben.
         */
        private val codesByLength: List<Pair<String, String>> by lazy {
            types.keys.map { it.uppercase() to it }
                .sortedByDescending { it.first.length }
        }

        /** Aliase, längstes zuerst - für die Präfix-Suche. */
        private val aliasesByLength: List<Map.Entry<String, String>> by lazy {
            aliases.entries.sortedByDescending { it.key.length }
        }

        /**
         * Kanonischer Typ-Code zum eingetragenen Typ, oder null, wenn der
         * Typ unbekannt ist. Wie bei [AircraftSeats.Catalog.seatsFor] werden
         * Familien ("A320"), Varianten ("A350-900", "A321neo", "CRJ900"),
         * Hersteller davor ("Airbus A320", "Boeing 737-800",
         * "McDonnell Douglas MD-82") und die Alias-Codes ("320", "738",
         * "737 MAX", "B738") erkannt. Die Schreibweise des Ergebnisses
         * entspricht dem Schlüssel im Asset ("Saab340").
         *
         * Reihenfolge: Alias exakt vor Code-Präfix - sonst würde "A319neo"
         * auf den Code "A319" treffen statt auf den Alias "A319NEO" ->
         * "A320neo" (A319neo gehört zur A320neo-Familie, nicht zur A319).
         */
        fun resolve(type: String?): String? {
            val norm = AircraftFacts.normalize(type)
            if (norm.isEmpty()) return null
            // Alias exakt, längstes zuerst: "A319NEO" -> A320neo (bevor der
            // Code "A319" als Präfix von "A319NEO" zuschlägt).
            aliases[norm]?.let { return it }
            // Kanonischer Code als Präfix, längste zuerst: "A350-900" → A350.
            codesByLength.firstOrNull { norm.startsWith(it.first) }?.let {
                return it.second
            }
            // Alias als Präfix, längstes zuerst: "737 MAX" → B737MAX.
            aliasesByLength.firstOrNull { norm.startsWith(it.key) }?.let {
                return it.value
            }
            // Alias auf dem Ziffernteil: "B738" → "738" → B737.
            val digits = norm.dropWhile { it.isLetter() }
            if (digits.isNotEmpty() && digits != norm) {
                aliases[digits]?.let { return it }
                aliasesByLength.firstOrNull { digits.startsWith(it.key) }?.let {
                    return it.value
                }
            }
            return null
        }

        /** Stammdaten zum eingetragenen Typ; null, wenn der Typ unbekannt ist. */
        fun factsFor(type: String?): Facts? = resolve(type)?.let { types[it] }
    }

    /**
     * Herstellerwörter, die vor einem Typ stehen dürfen ("Airbus A320",
     * "McDonnell Douglas MD-82", "de Havilland Canada Dash 8"). Wie bei
     * [AircraftSeats], ergänzt um die Hersteller der klassischen Typen.
     * Saab, Fokker und ATR fehlen bewusst - ihre Aliase decken die
     * Schreibweisen ab ("SAAB340", "FOKKER70", "ATR72").
     */
    private val MANUFACTURER_WORDS = listOf(
        "AIRBUS", "BOEING", "BOMBARDIER", "CANADAIR", "EMBRAER",
        "DEHAVILLAND", "REGIONAL", "JET",
        "CANADA", "MCDONNELL", "DOUGLAS", "LOCKHEED", "TUPOLEV", "ILYUSHIN",
        "YAKOVLEV", "SUKHOI", "BRITISH", "AEROSPACE", "SUD", "AVIATION",
        "HAWKER", "SIDDELEY"
    )

    /**
     * Normalisiert einen Typ: Grossbuchstaben, nur Buchstaben und Ziffern,
     * führende Herstellerwörter entfernt ("Boeing 737-800" → "737800").
     */
    private fun normalize(type: String?): String {
        var norm = type?.trim()?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()
        var stripped = true
        while (stripped) {
            stripped = false
            for (word in MANUFACTURER_WORDS) {
                if (norm.startsWith(word)) {
                    norm = norm.removePrefix(word)
                    stripped = true
                    break
                }
            }
        }
        return norm
    }

    @Volatile
    private var cached: Catalog? = null

    /** Lädt das Asset einmalig und teilt es zwischen allen Aufrufern. */
    fun load(context: Context): Catalog {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                val text = context.assets.open(ASSET)
                    .bufferedReader()
                    .use { it.readText() }
                val root = JSONObject(text)
                val typesJson = root.getJSONObject("types")
                val types = HashMap<String, Facts>(typesJson.length())
                val typeKeys = typesJson.keys()
                while (typeKeys.hasNext()) {
                    val code = typeKeys.next()
                    val fields = typesJson.getJSONObject(code)
                    types[code] = Facts(
                        name = fields.optString("name", code),
                        manufacturer = fields.optString("manufacturer")
                            .takeIf(String::isNotBlank),
                        productionStart = fields.optInt("productionStart")
                            .takeIf { it > 0 },
                        firstDelivery = fields.optString("firstDelivery")
                            .takeIf(String::isNotBlank),
                        launchCustomer = fields.optString("launchCustomer")
                            .takeIf(String::isNotBlank),
                        wiki = fields.optString("wiki").takeIf(String::isNotBlank)
                    )
                }
                val aliases = HashMap<String, String>()
                root.optJSONObject("aliases")?.let { aliasesJson ->
                    val aliasKeys = aliasesJson.keys()
                    while (aliasKeys.hasNext()) {
                        val code = aliasKeys.next()
                        aliases[code] = aliasesJson.getString(code)
                    }
                }
                Catalog(types, aliases)
            } catch (e: Exception) {
                Log.e(TAG, "Flugzeug-Stammdaten konnten nicht geladen werden", e)
                Catalog(emptyMap(), emptyMap())
            }
            cached = parsed
            return parsed
        }
    }

    /** Stammdaten zum eingetragenen Typ; null, wenn der Typ unbekannt ist. */
    fun get(context: Context, type: String?): Facts? = load(context).factsFor(type)
}
