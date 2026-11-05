package com.highfly.logbook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Allgemeine Länder-Fakten (Amtssprachen, Hauptstadt, Währung, Anzahl
 * Verwaltungsgliederungen) - unabhängig vom Logbuch. Grundlage ist das Asset
 * `assets/country_facts.json`, erzeugt von `tools/build_country_facts.py` aus
 * Wikidata (CC0), Schlüssel ist der ISO-2-Code. Geladen wird die Datei
 * einmalig; geprüft von [CountryFactsAssetTest]. Für USA/Deutschland liest
 * die App die Anzahl der Bundesstaaten/-länder stattdessen aus
 * `state_shapes.bin` ([StateShapes]).
 */
object CountryFacts {

    class Fact(
        val languagesDe: List<String>,
        val languagesEn: List<String>,
        val capitalsDe: List<String>,
        val capitalsEn: List<String>,
        val currenciesDe: List<String>,
        val currenciesEn: List<String>,
        val adminDivisions: Int?
    )

    @Volatile
    private var cache: Map<String, Fact>? = null

    /** Alle Länder-Fakten, Schlüssel ISO-2 (groß). */
    fun load(context: Context): Map<String, Fact> {
        cache?.let { return it }
        val map = HashMap<String, Fact>()
        try {
            val text = context.assets.open("country_facts.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            for (key in root.keys()) {
                val obj = root.getJSONObject(key)
                map[key.trim().uppercase()] = Fact(
                    languagesDe = obj.langList("languages", "de"),
                    languagesEn = obj.langList("languages", "en"),
                    capitalsDe = obj.langList("capital", "de"),
                    capitalsEn = obj.langList("capital", "en"),
                    currenciesDe = obj.langList("currency", "de"),
                    currenciesEn = obj.langList("currency", "en"),
                    adminDivisions = if (obj.has("adminDivisions")) obj.optInt("adminDivisions") else null
                )
            }
        } catch (_: Exception) {
            // Asset fehlt oder ist kaputt: leere Menge, die Seite zeigt keine Fakten.
        }
        cache = map
        return map
    }

    /** Fakten zu einem Land (ISO-2), null wenn unbekannt. */
    fun fact(context: Context, iso2: String): Fact? =
        load(context)[iso2.trim().uppercase()]

    private fun JSONObject.langList(field: String, lang: String): List<String> =
        optJSONObject(field)?.optJSONArray(lang).toStringList()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
    }
}
