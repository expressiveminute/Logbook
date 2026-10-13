#!/usr/bin/env python3
"""Build `cities_near.json`: Ankerorte im Umkreis der Flughäfen FRA und MUC.

Wozu
----
Auf der Seite "CO₂-Ausgleich" wird die nötige Pflanzfläche (der Wald)
maßstabsgetreu gezeichnet. Bei kleinen Flächen ist der gezeigte Kartenausschnitt
sehr eng und ohne Bezugspunkte; man erkennt kaum, wo die Fläche liegt. Damit es
Ankerpunkte gibt, blendet diese Karte Städte im Umkreis von 100 km um die beiden
Flughäfen Frankfurt (FRA) und München (MUC) ein. Nur dieser eine Kartenausschnitt
nutzt das Asset - die Weltkarte und die Streckenkarten bleiben unberührt.

Quelle
------
GeoNames `cities5000` (Orte ab rund 5000 Einwohnern):
https://download.geonames.org/export/dump/cities5000.zip
Lizenz: Creative Commons Attribution 4.0. Stadtteile (feature code PPLX, z. B.
"Gallus" oder "Niederrad") werden verworfen, damit nur eigenständige Orte
übrig bleiben.

Deutsche und englische Namen
----------------------------
`cities5000` führt nur einen Namen, der mal deutsch ("Frankfurt am Main"), mal
englisch ist ("Munich"). Für die großen Städte liefert `cities.json` bereits
die deutsche und die englische Schreibweise; stimmt eine Stadt (unter
[MATCH_KM] km) damit überein, wird sie übernommen. Sonst gilt der
GeoNames-Name für beide Sprachen.

Usage:
    python3 tools/build_cities_near.py
    python3 tools/build_cities_near.py --cities /tmp/cities5000.txt \
        --out app/src/main/assets/cities_near.json
"""

import argparse
import json
import math
import os
import sys
import unicodedata
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CITIES_JSON = os.path.join(HERE, "app", "src", "main", "assets", "cities.json")
DEFAULT_OUT = os.path.join(HERE, "app", "src", "main", "assets", "cities_near.json")

# Quelle (eine Datei im ZIP). Kann per --cities als bereits entpackte .txt
# ersetzt werden, damit der Lauf ohne Netz gelingt.
SOURCE_URL = "https://download.geonames.org/export/dump/cities5000.zip"
SOURCE_MEMBER = "cities5000.txt"

# Die beiden Flughäfen, um die herum Ankerorte gebraucht werden.
AIRPORTS = {
    "FRA": (50.0379, 8.5622),
    "MUC": (48.3538, 11.7861),
}

# Orte innerhalb dieses Radius um einen der Flughäfen kommen ins Asset.
RADIUS_KM = 100.0

# Ab hier gilt ein Ort als dieselbe Stadt wie ein Eintrag in cities.json und
# übernimmt dessen deutsche/englische Namen.
MATCH_KM = 3.0

# Zwei gleichnamige Einträge unter dieser Entfernung gelten als derselbe Ort.
MERGE_KM = 5.0

# Eigenständige Orte. PPLX sind Stadtteile (Gallus, Niederrad), die als
# eigene Ankerpunkte irreführend wären; der Rest sind Sitz einer Verwaltung
# oder ein gewöhnlicher Ort.
KEEP_CODES = {"PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLC", "PPLL", "PPLR", "PPLS"}

EARTH_RADIUS_KM = 6371.0


def haversine_km(lat1, lon1, lat2, lon2):
    """Entfernung zweier Punkte in Kilometern."""
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def normalize(text):
    """Vergleichsform eines Ortsnamens: ohne Akzente, klein, nur Wörter."""
    decomposed = unicodedata.normalize("NFKD", text)
    without_marks = "".join(c for c in decomposed if not unicodedata.combining(c))
    return without_marks.lower().replace("(", " ").replace(")", " ").split()


def names_compatible(geonames_name, candidate):
    """Passen zwei Schreibweisen desselben Ortes zusammen?

    True, wenn eine Wortmenge die andere enthält ("frankfurt am main" und
    "frankfurt", "munchen" und "munchen"). So übernimmt München den
    cities.json-Namen, ein benachbarter Stadtteil wie "Neckarau" aber nicht
    den Namen der Großstadt.
    """
    a = set(normalize(geonames_name))
    b = set(normalize(candidate))
    if not a or not b:
        return False
    return a <= b or b <= a


def load_name_index(path):
    """Orte aus cities.json als Liste (lat, lon, key, de, en) für den Namensabgleich."""
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    index = []
    for name, values in data.items():
        if len(values) < 2:
            continue
        de = values[4] if len(values) > 4 else name
        en = values[5] if len(values) > 5 else name
        index.append((values[0], values[1], name, de, en))
    return index


def best_names(lat, lon, geonames_name, index):
    """Deutschen und englischen Namen aus cities.json, sonst (None, None).

    Verlangt Nähe *und* einen passenden Namen, damit ein Nachbarort nicht den
    Namen der nächstgelegenen Großstadt erbt.
    """
    best = None
    best_km = MATCH_KM
    for other_lat, other_lon, key, de, en in index:
        if not any(names_compatible(geonames_name, candidate) for candidate in (key, de, en)):
            continue
        km = haversine_km(lat, lon, other_lat, other_lon)
        if km < best_km:
            best_km = km
            best = (de, en)
    return best if best else (None, None)


def read_source(cities_path):
    """Zeilen der GeoNames-Datei als Text liefern; notfalls das ZIP laden."""
    if cities_path:
        with open(cities_path, encoding="utf-8") as handle:
            return handle.read().splitlines()
    data = urllib.request.urlopen(SOURCE_URL, timeout=180).read()
    with zipfile.ZipFile(__import__("io").BytesIO(data)) as archive:
        with archive.open(SOURCE_MEMBER) as handle:
            return handle.read().decode("utf-8").splitlines()


def parse_rows(lines):
    """GeoNames-Zeilen zu (name, fcode, lat, lon, population) auswerten."""
    rows = []
    for line in lines:
        fields = line.split("\t")
        if len(fields) < 15:
            continue
        try:
            lat = float(fields[4])
            lon = float(fields[5])
            population = int(fields[14])
        except ValueError:
            continue
        rows.append((fields[1], fields[7], lat, lon, population))
    return rows


def build(rows, name_index):
    """Orte im Umkreis der Flughäfen sammeln, entdoppeln und nach Größe sortieren."""
    chosen = []
    for name, fcode, lat, lon, population in rows:
        if fcode not in KEEP_CODES:
            continue
        if not any(haversine_km(lat, lon, alat, alon) <= RADIUS_KM
                   for alat, alon in AIRPORTS.values()):
            continue
        de, en = best_names(lat, lon, name, name_index)
        de = de or name
        en = en or name

        # Denselben Ort (gleicher Name, wenige Kilometer auseinander) nur
        # einmal führen - GeoNames hat gelegentlich Doppeleinträge.
        entry = [round(lat, 5), round(lon, 5), population, de, en]
        duplicate = None
        for other in chosen:
            if names_compatible(de, other[3]) and \
                    haversine_km(lat, lon, other[0], other[1]) < MERGE_KM:
                duplicate = other
                break
        if duplicate is None:
            chosen.append(entry)
        elif population > duplicate[2]:
            duplicate[:] = entry

    chosen.sort(key=lambda item: (-item[2], item[3]))
    return chosen


def to_json(result):
    """Kompakte Ausgabe: der englische Name entfällt, wenn er gleich ist."""
    compact = []
    for lat, lon, population, de, en in result:
        row = [lat, lon, population, de]
        if en != de:
            row.append(en)
        compact.append(row)
    return json.dumps(compact, ensure_ascii=False, separators=(",", ":"))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cities", help="bereits entpackte cities5000.txt (sonst Download)")
    parser.add_argument("--names", default=DEFAULT_CITIES_JSON,
                        help="cities.json für deutsche/englische Namen")
    parser.add_argument("--out", default=DEFAULT_OUT, help="Zieldatei")
    args = parser.parse_args()

    lines = read_source(args.cities)
    rows = parse_rows(lines)
    name_index = load_name_index(args.names)
    result = build(rows, name_index)

    if not result:
        print("Keine Orte gefunden - Abbruch.", file=sys.stderr)
        return 1

    text = to_json(result)
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(text)

    size_kb = len(text.encode("utf-8")) / 1024.0
    print(f"{len(result)} Orte geschrieben nach {args.out} ({size_kb:.0f} KB)")
    for lat, lon, population, de, en in result[:8]:
        print(f"    {de:32s} {en:24s} {population:>10,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
