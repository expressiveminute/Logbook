#!/usr/bin/env python3
"""Build the offline country-facts asset for the Logbook app.

Produces `app/src/main/assets/country_facts.json`: general facts about
countries that do not depend on the user's logbook - official languages
(P37), capital(s) (P36), currency(ies) (P38), public holidays (P832, with
month-day when the statement carries a point-in-time qualifier P585) and
the number of first-level administrative divisions (P150, e.g. the
Bundeslaender). The "time in countries" detail page shows them; for the US
and Germany the app overrides the division count with the states from
`state_shapes.bin`.

Data source: Wikidata (CC0) via the SPARQL endpoint, keyed by ISO 3166-1
alpha-2 (P297), labels in German and English as explicit rdfs:label. The
public holiday dates come from P837 ("day in year for periodic occurrence")
on the holiday item, whose en label is e.g. "December 25" -> "12-25".
"""

import json
import re
import time
import urllib.parse
import urllib.request

SPARQL_ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "LogbookCountryFactsBuilder/1.0 (offline asset builder)"
REQUEST_TIMEOUT = 90
RETRIES = 5

OUTPUT = "app/src/main/assets/country_facts.json"

MONTHS_EN = {
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6,
    "july": 7, "august": 8, "september": 9, "october": 10, "november": 11,
    "december": 12,
}

QUERY = """
SELECT ?iso2 ?langDe ?langEn ?capDe ?capEn ?curDe ?curEn
       ?holiday ?holDe ?holEn ?holidayDate ?doyEn ?adminCount
WHERE {
  ?country wdt:P297 ?iso2 .
  OPTIONAL {
    ?country wdt:P37 ?lang .
    OPTIONAL { ?lang rdfs:label ?langDe . FILTER(LANG(?langDe) = "de") }
    OPTIONAL { ?lang rdfs:label ?langEn . FILTER(LANG(?langEn) = "en") }
  }
  OPTIONAL {
    ?country wdt:P36 ?capital .
    OPTIONAL { ?capital rdfs:label ?capDe . FILTER(LANG(?capDe) = "de") }
    OPTIONAL { ?capital rdfs:label ?capEn . FILTER(LANG(?capEn) = "en") }
  }
  OPTIONAL {
    ?country wdt:P38 ?currency .
    OPTIONAL { ?currency rdfs:label ?curDe . FILTER(LANG(?curDe) = "de") }
    OPTIONAL { ?currency rdfs:label ?curEn . FILTER(LANG(?curEn) = "en") }
  }
  OPTIONAL {
    ?country p:P832 ?hs .
    ?hs ps:P832 ?holiday .
    OPTIONAL { ?holiday rdfs:label ?holDe . FILTER(LANG(?holDe) = "de") }
    OPTIONAL { ?holiday rdfs:label ?holEn . FILTER(LANG(?holEn) = "en") }
    OPTIONAL { ?hs pq:P585 ?holidayDate . }
    OPTIONAL {
      ?holiday wdt:P837 ?doy .
      OPTIONAL { ?doy rdfs:label ?doyEn . FILTER(LANG(?doyEn) = "en") }
    }
  }
  OPTIONAL {
    { SELECT ?country (COUNT(DISTINCT ?div) AS ?adminCount)
      WHERE { ?country wdt:P150 ?div . } GROUP BY ?country }
  }
}
"""


def http_get_json(url, params, retries=RETRIES, base_wait=2.0):
    """GET mit User-Agent, Wiederholung bei Rate-Limit/Fehler und Pause."""
    query = urllib.parse.urlencode(params)
    last_error = None
    for attempt in range(retries):
        request = urllib.request.Request(
            url + "?" + query, headers={"User-Agent": USER_AGENT}
        )
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - jede Stoerung wird wiederholt
            last_error = error
            wait = base_wait * (attempt + 1)
            print("  Wiederholung nach Fehler (%s), warte %.0fs" % (error, wait))
            time.sleep(wait)
    raise RuntimeError("GET fehlgeschlagen: %s (%s)" % (url, last_error))


def month_day(value):
    """Wikidata-Zeitwert wie '1990-10-03T00:00:00Z' -> '10-03' (oder None)."""
    if not value or len(value) < 10:
        return None
    month, day = value[5:7], value[8:10]
    if month.isdigit() and day.isdigit():
        return "%s-%s" % (month, day)
    return None


def parse_doy_label(value):
    """en-Label eines day-in-year-Items wie 'December 25' -> '12-25'."""
    if not value:
        return None
    m = re.match(r"(?i)([a-z]+)\s+(\d{1,2})\.?$", value.strip())
    if m and m.group(1).lower() in MONTHS_EN:
        return "%02d-%02d" % (MONTHS_EN[m.group(1).lower()], int(m.group(2)))
    return None


def add_pair(bucket, row, de_var, en_var):
    """Traegt das de- und en-Label einer Variablen in den Set-Bucket ein."""
    for var, lang in ((de_var, "de"), (en_var, "en")):
        label = row.get(var)
        if label:
            bucket.setdefault(lang, set()).add(label["value"])


def build():
    print("SPARQL: Laender-Fakten (P37/P36/P38/P832/P150) ...")
    data = http_get_json(SPARQL_ENDPOINT, {"format": "json", "query": QUERY})
    rows = data["results"]["bindings"]
    print("  %d Rohzeilen" % len(rows))

    facts = {}
    for row in rows:
        iso2 = row["iso2"]["value"].strip().upper()
        if len(iso2) != 2 or not iso2.isalpha():
            continue
        fact = facts.setdefault(
            iso2,
            {
                "languages": {"de": set(), "en": set()},
                "capital": {"de": set(), "en": set()},
                "currency": {"de": set(), "en": set()},
                "holidays": {},
                "adminCount": None,
            },
        )
        add_pair(fact["languages"], row, "langDe", "langEn")
        add_pair(fact["capital"], row, "capDe", "capEn")
        add_pair(fact["currency"], row, "curDe", "curEn")
        holiday = row.get("holiday")
        if holiday:
            qid = holiday["value"].rsplit("/", 1)[-1]
            entry = fact["holidays"].setdefault(
                qid, {"name": {"de": None, "en": None}, "date": None}
            )
            if row.get("holDe") and not entry["name"]["de"]:
                entry["name"]["de"] = row["holDe"]["value"]
            if row.get("holEn") and not entry["name"]["en"]:
                entry["name"]["en"] = row["holEn"]["value"]
            # Datum: P585-Statement, sonst P837 (day in year, en-Label "December 25").
            date = row.get("holidayDate")
            if date and not entry["date"]:
                entry["date"] = month_day(date["value"])
            if not entry["date"] and row.get("doyEn"):
                entry["date"] = parse_doy_label(row["doyEn"]["value"])
        if row.get("adminCount"):
            try:
                fact["adminCount"] = int(row["adminCount"]["value"])
            except ValueError:
                pass

    out = {}
    for iso2 in sorted(facts):
        fact = facts[iso2]
        entry = {}
        for key in ("languages", "capital", "currency"):
            value = {lang: sorted(names) for lang, names in fact[key].items() if names}
            if key == "currency" and "en" in value:
                # en-Währungslabels kommen oft klein ("euro") -> gross schreiben.
                value["en"] = [n[:1].upper() + n[1:] for n in value["en"]]
            if value:
                entry[key] = value
        holidays = []
        for qid, h in fact["holidays"].items():
            name = h["name"]
            if not (name["de"] or name["en"]):
                continue
            item = {"name": {k: v for k, v in name.items() if v}}
            if h["date"]:
                item["date"] = h["date"]
            holidays.append(item)
        holidays.sort(key=lambda h: (h.get("date") or "99-99", h["name"].get("de") or h["name"].get("en") or ""))
        if holidays:
            entry["holidays"] = holidays
        if fact["adminCount"]:
            entry["adminDivisions"] = fact["adminCount"]
        if entry:
            out[iso2] = entry

    with open(OUTPUT, "w", encoding="utf-8") as handle:
        json.dump(out, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    print("geschrieben: %s (%d Laender, %.1f KB)" % (OUTPUT, len(out), len(json.dumps(out)) / 1024))


if __name__ == "__main__":
    build()
