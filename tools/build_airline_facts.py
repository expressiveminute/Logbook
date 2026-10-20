#!/usr/bin/env python3
"""Build the offline airline-facts asset for the Logbook app.

Produces `app/src/main/assets/airlines/airline_facts.json`: general facts
about each airline that do not depend on the user's logbook - founding year,
headquarters, hubs, fleet size, alliance and callsign. The airline detail
page shows them above the route map.

Data sources:
  * Wikidata (CC0) via the SPARQL endpoint: inception (P571, bei mehreren
    Angaben das frueheste Jahr), dissolution (P576, ebenfalls das frueheste
    Jahr - das Insolvenzjahr einer pleitegegangenen Airline), headquarters
    (P159, German and English label), hub airports (P113, reduced to their
    IATA code via P238), alliance (P114) and callsign (P432). Keyed by the
    IATA airline designator (P229), which is also the app's canonical key.
    Aus dem englischen Sitelink des ausgewaehlten Eintrags entsteht zudem
    der Wikipedia-Link der Detailseite (`wiki`).
  * Wikipedia (CC BY-SA 4.0) for the fleet size, which Wikidata does not
    carry: the `Flugzeuge` (de) resp. `fleet_size` (en) parameter of the
    airline infobox, read from the wikitext of the de/en article (sitelinks
    come from the same SPARQL query). Only the number is kept.

Airlines without any of these facts are left out; fields without a value are
omitted. The fleet size is the first number of the infobox value after
stripping references, templates and links - for a range only the lower end
survives, and a value that only links to a fleet list page yields nothing.

Several Wikidata items can share one IATA designator (subsidiaries like
"Lufthansa Regional" or "QantasLink", predecessors like BOAC, legal successors
like "British Airways Limited"). Per code exactly one item is used: the one
whose English or German label equals the catalog name, otherwise the item
with the most facts filled in (ties: more hubs, then sitelinks). All data -
including the sitelinks for the fleet size - comes from that item alone.

Usage:
    python3 tools/build_airline_facts.py
    python3 tools/build_airline_facts.py --catalog app/src/main/assets/airlines/airlines.json \
        --out app/src/main/assets/airlines/airline_facts.json

JSON format (IATA code -> facts, only known fields, sorted by key):
    {
        "AB": {
            "alliance": "Oneworld",
            "callsign": "AIR BERLIN",
            "dissolved": 2017,
            "fleet": 132,
            "founded": 1978,
            "hq": "Charlottenburg-Nord",
            "hq_de": "Charlottenburg-Nord",
            "hubs": ["DUS", "PMI", "TXL"],
            "wiki": "https://en.wikipedia.org/wiki/Air_Berlin"
        },
        ...
    }
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

SPARQL_ENDPOINT = "https://query.wikidata.org/sparql"
WIKI_API = "https://{wiki}.wikipedia.org/w/api.php"
USER_AGENT = "LogbookAirlineFacts/1.0 (https://github.com/expressiveminute/Logbook)"

# IATA-Codes pro SPARQL-Abfrage und Artikeltitel pro Wikipedia-Abruf.
SPARQL_BATCH = 200
WIKI_BATCH = 50
REQUEST_TIMEOUT = 60
RETRIES = 4

# SPARQL-Abfrage: Stammdaten plus Artikel-Labels und de/en-Sitelinks (fuer
# die Auswahl des richtigen Eintrags und die Flottengroesse).
SPARQL_TEMPLATE = """
SELECT ?iata ?item ?labelEn ?labelDe ?founded ?dissolved ?hqDe ?hqEn ?hubIata ?alliance ?callsign ?slDe ?slEn WHERE {
  VALUES ?iata { %(values)s }
  ?item wdt:P229 ?iata .
  OPTIONAL { ?item rdfs:label ?labelEn . FILTER(LANG(?labelEn) = "en") }
  OPTIONAL { ?item rdfs:label ?labelDe . FILTER(LANG(?labelDe) = "de") }
  OPTIONAL { ?item wdt:P571 ?f . BIND(YEAR(?f) AS ?founded) }
  OPTIONAL { ?item wdt:P576 ?d . BIND(YEAR(?d) AS ?dissolved) }
  OPTIONAL {
    ?item wdt:P159 ?hq .
    OPTIONAL { ?hq rdfs:label ?hqDe . FILTER(LANG(?hqDe) = "de") }
    OPTIONAL { ?hq rdfs:label ?hqEn . FILTER(LANG(?hqEn) = "en") }
  }
  OPTIONAL { ?item wdt:P113 ?hub . OPTIONAL { ?hub wdt:P238 ?hubIata } }
  OPTIONAL {
    ?item wdt:P114 ?allianceItem .
    ?allianceItem rdfs:label ?alliance . FILTER(LANG(?alliance) = "en")
  }
  OPTIONAL { ?item wdt:P432 ?callsign }
  OPTIONAL { ?slDe schema:about ?item ; schema:isPartOf <https://de.wikipedia.org/> . }
  OPTIONAL { ?slEn schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> . }
}
"""


def http_get_json(url, params):
    """GET mit User-Agent, Wiederholung bei Rate-Limit/Fehler und Pause."""
    query = urllib.parse.urlencode(params)
    last_error = None
    for attempt in range(RETRIES):
        request = urllib.request.Request(
            url + "?" + query, headers={"User-Agent": USER_AGENT}
        )
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - jede Störung wird wiederholt
            last_error = error
            wait = 2.0 * (attempt + 1)
            print("  Wiederholung nach Fehler (%s), warte %.0fs" % (error, wait))
            time.sleep(wait)
    raise RuntimeError("GET fehlgeschlagen: %s (%s)" % (url, last_error))


def load_catalog(catalog_path):
    """IATA-Code und Name aller Airlines des Katalogs, nach Code sortiert."""
    with open(catalog_path, encoding="utf-8") as handle:
        airlines = json.load(handle)["airlines"]
    by_code = {}
    for item in airlines:
        code = item["iata"].strip().upper()
        if code and code not in by_code:
            by_code[code] = item.get("name") or ""
    return sorted(by_code.items())


def fetch_wikidata_facts(catalog):
    """Stammdaten aus Wikidata, aggregiert je IATA-Code.

    Pro Code wird genau ein Wikidata-Eintrag ausgewählt (siehe choose_item),
    denn derselbe IATA-Code taucht auch bei Tochtergesellschaften,
    Vorgängern und Rechtsnachfolgern auf.
    """
    codes = [code for code, _ in catalog]
    names = dict(catalog)
    per_item = {}
    for start in range(0, len(codes), SPARQL_BATCH):
        batch = codes[start : start + SPARQL_BATCH]
        values = " ".join('"%s"' % code for code in batch)
        query = SPARQL_TEMPLATE % {"values": values}
        print("SPARQL: %d Codes (%d-%d von %d)" % (len(batch), start + 1, start + len(batch), len(codes)))
        data = http_get_json(SPARQL_ENDPOINT, {"format": "json", "query": query})
        for row in data["results"]["bindings"]:
            iata = row["iata"]["value"]
            qid = row["item"]["value"].rsplit("/", 1)[-1]
            entry = per_item.setdefault(
                (iata, qid), {"hubs": set(), "titles_de": [], "titles_en": []}
            )
            if "labelEn" in row:
                entry["label_en"] = row["labelEn"]["value"].strip()
            if "labelDe" in row:
                entry["label_de"] = row["labelDe"]["value"].strip()
            if "founded" in row:
                # Mehrere Angaben (z. B. Gründung 1926 und Neugründung 1953
                # bei Lufthansa): das früheste Jahr ist die Gründung.
                year = int(row["founded"]["value"])
                if "founded" not in entry or year < entry["founded"]:
                    entry["founded"] = year
            if "dissolved" in row:
                # Ebenfalls das frueheste Jahr (Insolvenz vs. Wiederaufnahme).
                year = int(row["dissolved"]["value"])
                if "dissolved" not in entry or year < entry["dissolved"]:
                    entry["dissolved"] = year
            for field, key in (("hqDe", "hq_de"), ("hqEn", "hq"), ("alliance", "alliance"), ("callsign", "callsign")):
                if key not in entry and field in row:
                    entry[key] = row[field]["value"].strip()
            if "hubIata" in row:
                entry["hubs"].add(row["hubIata"]["value"].strip().upper())
            for field, key in (("slDe", "titles_de"), ("slEn", "titles_en")):
                if field in row:
                    title = sitelink_title(row[field]["value"])
                    if title and title not in entry[key]:
                        entry[key].append(title)
        time.sleep(0.3)

    grouped = {}
    for (iata, qid), entry in per_item.items():
        grouped.setdefault(iata, []).append((qid, entry))
    return {
        iata: choose_item(entries, names.get(iata, ""))
        for iata, entries in grouped.items()
    }


def choose_item(entries, catalog_name):
    """Der passendste Wikidata-Eintrag zu einem IATA-Code.

    Mehrere Eintraege teilen sich einen Code (Tochtergesellschaften wie
    "Lufthansa Regional" oder "QantasLink", Vorgänger wie BOAC, Rechtsnachfolger
    wie "British Airways Limited"). Gewählt wird der Eintrag, dessen englischer
    oder deutscher Name dem Katalognamen entspricht; sonst der mit den meisten
    belegten Feldern (bei Gleichstand mehr Hubs, dann niedrigere QID).
    """
    def score(item):
        qid, entry = item
        name = catalog_name.strip().lower()
        label_match = name and (
            entry.get("label_en", "").lower() == name
            or entry.get("label_de", "").lower() == name
        )
        filled = sum(
            [
                "founded" in entry,
                bool(entry.get("hq")),
                len(entry["hubs"]) > 0,
                bool(entry.get("alliance")),
                bool(entry.get("callsign")),
                bool(entry["titles_de"] or entry["titles_en"]),
            ]
        )
        qid_number = int(qid[1:]) if qid[1:].isdigit() else 0
        return (1 if label_match else 0, filled, len(entry["hubs"]), -qid_number)

    return max(entries, key=score)[1]


def sitelink_title(url):
    """Artikeltitel aus einer Sitelink-URL, z. B. .../wiki/Lufthansa."""
    title = url.split("/wiki/", 1)[-1] if "/wiki/" in url else ""
    title = title.split("#", 1)[0]
    return urllib.parse.unquote(title).replace("_", " ").strip()


def wiki_url(title):
    """Vollstaendige URL des englischsprachigen Wikipedia-Artikels."""
    return "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))


def fetch_wikitexts(wiki, titles):
    """Wikitext mehrerer Artikel in einem API-Aufruf (max. 50 Titel)."""
    texts = {}
    for start in range(0, len(titles), WIKI_BATCH):
        batch = titles[start : start + WIKI_BATCH]
        data = http_get_json(
            WIKI_API.format(wiki=wiki),
            {
                "action": "query",
                "prop": "revisions",
                "rvprop": "content",
                "rvslots": "main",
                "format": "json",
                "formatversion": "2",
                "titles": "|".join(batch),
            },
        )
        for page in data.get("query", {}).get("pages", []):
            revisions = page.get("revisions")
            if revisions:
                texts[page["title"]] = revisions[0].get("slots", {}).get("main", {}).get("content", "")
        time.sleep(0.3)
    return texts


# Infobox-Parameter der Flottengroesse: die deutsche Vorlage nutzt
# "Flugzeuge" (manchmal "Flottenstaerke"), die englische "fleet_size".
FLEET_PARAM = re.compile(
    r"(?m)^\s*\|\s*(?:Flottenst\u00e4rke|Flugzeuge|fleet_size)\s*=\s*(.*)$"
)


def fleet_from_wikitext(wikitext):
    """Erste Zahl des Flottengroessen-Parameters, ohne Refs/Templates/Links."""
    match = FLEET_PARAM.search(wikitext)
    if not match:
        return None
    raw = match.group(1)
    # Parameterwert kann ueber wenige Zeilen laufen (geschachtelte Vorlagen):
    # bis zur naechsten Parameterzeile oder zum Infobox-Ende, gedeckelt.
    rest = wikitext[match.end() :]
    for line in rest.splitlines()[:10]:
        if re.match(r"\s*\|", line) or line.strip().startswith("}}"):
            break
        raw += " " + line.strip()
    raw = re.sub(r"<ref[^>]*>.*?</ref>", " ", raw, flags=re.S)
    raw = re.sub(r"<ref[^>]*/>", " ", raw)
    raw = re.sub(r"<!--.*?-->", " ", raw, flags=re.S)
    for _ in range(20):  # innerste Vorlage zuerst aufloesen, gedeckelt
        stripped = re.sub(r"\{\{[^{}]*\}\}", " ", raw)
        if stripped == raw:
            break
        raw = stripped
    raw = re.sub(r"\[\[([^\]|]*\|)?([^\]|]*)\]\]", r"\2", raw)
    number = re.search(r"\d{1,3}(?:[.,]\d{3})+|\d+", raw)
    if not number:
        return None
    return int(number.group(0).replace(".", "").replace(",", ""))


def fetch_fleet_sizes(facts):
    """Flottengroesse aus der Wikipedia-Infobox, deutsche Seite bevorzugt."""
    titles_de = sorted({t for entry in facts.values() for t in entry["titles_de"]})
    titles_en = sorted({t for entry in facts.values() for t in entry["titles_en"]})
    texts_de = fetch_wikitexts("de", titles_de) if titles_de else {}
    texts_en = fetch_wikitexts("en", titles_en) if titles_en else {}
    # Titel -> IATA-Code; bei gemeinsamem Artikeltitel gewinnt der erste Code.
    by_title_de = {}
    by_title_en = {}
    for iata in sorted(facts):
        for title in facts[iata]["titles_de"]:
            by_title_de.setdefault(title, iata)
        for title in facts[iata]["titles_en"]:
            by_title_en.setdefault(title, iata)
    for title, text in texts_de.items():
        fleet = fleet_from_wikitext(text)
        iata = by_title_de.get(title)
        if fleet and iata and "fleet" not in facts[iata]:
            facts[iata]["fleet"] = fleet
    for title, text in texts_en.items():
        fleet = fleet_from_wikitext(text)
        iata = by_title_en.get(title)
        if fleet and iata and "fleet" not in facts[iata]:
            facts[iata]["fleet"] = fleet
    return len(texts_de) + len(texts_en)


def main():
    parser = argparse.ArgumentParser()
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument(
        "--catalog",
        default=os.path.join(here, "app", "src", "main", "assets", "airlines", "airlines.json"),
    )
    parser.add_argument(
        "--out",
        default=os.path.join(here, "app", "src", "main", "assets", "airlines", "airline_facts.json"),
    )
    args = parser.parse_args()

    catalog = load_catalog(args.catalog)
    print("Katalog: %d IATA-Codes" % len(catalog))

    facts = fetch_wikidata_facts(catalog)
    pages = fetch_fleet_sizes(facts)

    payload = {}
    for iata in sorted(facts):
        entry = facts[iata]
        record = {}
        if "founded" in entry:
            record["founded"] = entry["founded"]
        if entry.get("hq"):
            record["hq"] = entry["hq"]
        if entry.get("hq_de"):
            record["hq_de"] = entry["hq_de"]
        if entry["hubs"]:
            record["hubs"] = sorted(entry["hubs"])
        if "fleet" in entry:
            record["fleet"] = entry["fleet"]
        if entry.get("alliance"):
            record["alliance"] = entry["alliance"]
        if entry.get("callsign"):
            record["callsign"] = entry["callsign"]
        if "dissolved" in entry:
            record["dissolved"] = entry["dissolved"]
        if entry["titles_en"]:
            # Link auf die englische Wikipedia-Seite der Airline.
            record["wiki"] = wiki_url(entry["titles_en"][0])
        if record:
            payload[iata] = record

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        handle.write("\n")

    with_fleet = sum(1 for record in payload.values() if "fleet" in record)
    with_dissolved = sum(1 for record in payload.values() if "dissolved" in record)
    with_wiki = sum(1 for record in payload.values() if "wiki" in record)
    print(
        "%s: %d Airlines, %d mit Flottengroesse, %d insolvent, %d mit Wiki-Link "
        "(%d Wiki-Abrufe), %.2f MB"
        % (
            os.path.relpath(args.out, os.getcwd()),
            len(payload),
            with_fleet,
            with_dissolved,
            with_wiki,
            pages,
            os.path.getsize(args.out) / 1048576.0,
        )
    )
    for example in ("LH", "BA", "EK"):
        if example in payload:
            print("  %s = %s" % (example, json.dumps(payload[example], ensure_ascii=False)))


if __name__ == "__main__":
    sys.exit(main())
