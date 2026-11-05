#!/usr/bin/env python3
"""Baut die Assets für Bundesstaaten (USA) und Bundesländer (Deutschland).

Zwei Dateien entstehen:

1. `app/src/main/assets/state_shapes.bin`
   Umrisse aller US-Bundesstaaten (inkl. District of Columbia) und aller
   deutschen Bundesländer, dazu je Einheit der ISO-3166-2-Code ("US-CA",
   "DE-BY"), Name (deutsch/englisch), Hauptstadt mit Koordinaten und ein
   Beschriftungsanker. Die Rubbelkarte zeichnet daraus die dünnen
   Staatengrenzen und färbt angeflogene Staaten ein.

2. `app/src/main/assets/airports/airports_regions.json`
   IATA-Code -> ISO-3166-2-Region ("US-CA", "DE-BY"), damit ein Flug einem
   Bundesstaat zugeordnet werden kann.

Quellen (alle Public Domain / CC0 / CC BY-SA, siehe Kommentare):
- Natural Earth Admin 1 (Staaten/Provinzen, Umrisse + Namen + Anker):
  https://www.naturalearthdata.com/downloads/10m-cultural-vectors/
  (Public Domain), gespiegelt als GeoJSON im natural-earth-vector-Repo.
- GeoNames cities5000 (Hauptstädte der first-order-Verwaltungseinheiten,
  feature code PPLA; Public Domain/CC BY):
  https://download.geonames.org/export/dump/cities5000.zip
  Deutsche Ortsnamen kommen aus dem bereits lokal vorliegenden
  populated_places-Dump (GeoNames, NAME_DE je geonameid).
- Wikidata (deutsche Namen der US-Bundesstaaten, CC0):
  SPARQL-Endpunkt, Labels der "state of the United States" (Q35657).
- OurAirports airports.csv (iso_region je Flughafen, Public Domain):
  bereits lokal in tmp_ne/airports.csv.

Aufruf:
    python3 tools/build_states.py
    python3 tools/build_states.py --admin1 tmp_ne/ne_10m_admin_1_states_provinces.geojson \
        --cities tmp_ne/cities5000.txt --airports tmp_ne/airports.csv \
        --out app/src/main/assets/state_shapes.bin \
        --regions-out app/src/main/assets/airports/airports_regions.json

Binärformat von state_shapes.bin (little endian), siehe StateShapes.kt:
    int32   magic "STSH"
    uint8   version (1)
    uint8   reserved
    uint16  stateCount
    je Staat:
        char[5] code ("US-CA")
        char[2] iso2 des Landes ("US")
        float32 capLat, capLon        Hauptstadt
        float32 labelLat, labelLon    Beschriftungsanker
        float32 minLon, minLat, maxLon, maxLat
        uint8 len + UTF-8 nameDe
        uint8 len + UTF-8 nameEn
        uint8 len + UTF-8 capDe
        uint8 len + UTF-8 capEn
        uint32  firstRing
        uint32  ringCount
    uint32  ringTableSize
    je Ring: uint32 pointCount, uint32 coordOffset (in Koordinatenpaaren)
    je Punkt: int16 lon, int16 lat in 1/180 Grad
    float32 sentinel -90.0
"""

import argparse
import csv
import io
import json
import os
import re
import struct
import sys
import urllib.request
import zipfile

MAGIC = 0x48535453  # Bytes "S","T","S","H" als little-endian int
VERSION = 1

UNITS_PER_DEGREE = 180  # 180 * 180 = 32400 passt in int16, wie bei country_shapes.bin

LAT_MIN = -85.0

# Ramer-Douglas-Peucker-Toleranzen in Grad, wie in build_country_shapes.py.
EPS_DEFAULT = 0.08  # etwas grober als die Länder: Staatengrenzen sind dünne Linien
EPS_SMALL = 0.02
SMALL_DEG = 6.0

NE_ADMIN1_URL = (
    "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/"
    "geojson/ne_10m_admin_1_states_provinces.geojson"
)
CITIES5000_URL = "https://download.geonames.org/export/dump/cities5000.zip"
WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"

# Natural Earth nennt die Länder im Feld "admin" mit vollem Namen.
ADMIN_TO_ISO2 = {
    "United States of America": "US",
    "Germany": "DE",
}
WANTED_ISO2 = {"US", "DE"}

CODE_RE = re.compile(r"^(US|DE)-[A-Z0-9]{1,3}$")


def download(url, dest):
    if os.path.exists(dest):
        return
    print("lade %s" % url)
    req = urllib.request.Request(url, headers={"User-Agent": "logbook-tools/1.0"})
    data = urllib.request.urlopen(req, timeout=300).read()
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if url.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            name = zf.namelist()[0]
            with zf.open(name) as src, open(dest, "wb") as out:
                out.write(src.read())
    else:
        with open(dest, "wb") as out:
            out.write(data)


def sqdist(a, b):
    dx = a[0] - b[0]
    dy = a[1] - b[1]
    return dx * dx + dy * dy


def point_seg_dist(p, a, b):
    px, py = p
    ax, ay = a
    bx, by = b
    dx = bx - ax
    dy = by - ay
    if dx == 0 and dy == 0:
        return (px - ax) ** 2 + (py - ay) ** 2
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return (px - (ax + t * dx)) ** 2 + (py - (ay + t * dy)) ** 2


def simplify(points, eps):
    """Ramer-Douglas-Peucker, Endpunkte bleiben immer erhalten."""
    if len(points) < 3 or eps <= 0:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        if last <= first + 1:
            continue
        worst = -1.0
        worst_index = -1
        for i in range(first + 1, last):
            d = point_seg_dist(points[i], points[first], points[last])
            if d > worst:
                worst = d
                worst_index = i
        if worst > eps * eps:
            keep[worst_index] = True
            stack.append((first, worst_index))
            stack.append((worst_index, last))
    return [p for p, k in zip(points, keep) if k]


def iter_rings(geometry):
    if geometry["type"] == "Polygon":
        for ring in geometry["coordinates"]:
            yield ring
    elif geometry["type"] == "MultiPolygon":
        for polygon in geometry["coordinates"]:
            for ring in polygon:
                yield ring


def wikidata_de_labels_us_states(cache_path):
    """Deutsche Namen der US-Bundesstaaten von Wikidata (CC0), nach englischem Namen.

    Das Ergebnis wird in einer Cache-Datei gehalten (der SPARQL-Endpunkt
    drosselt hart); schlägt die Abfrage fehl, liefert die Funktion ein
    leeres Dict - dann steht der englische Name auch in der deutschen App.
    """
    if cache_path and os.path.exists(cache_path):
        with open(cache_path, encoding="utf-8") as handle:
            return json.load(handle)
    query = (
        "SELECT ?item ?en ?de WHERE {"
        " ?item wdt:P31/wdt:P279* wd:Q35657 ."
        " OPTIONAL { ?item rdfs:label ?en . FILTER(LANG(?en) = 'en') }"
        " OPTIONAL { ?item rdfs:label ?de . FILTER(LANG(?de) = 'de') }"
        "}"
    )
    try:
        req = urllib.request.Request(
            WIKIDATA_SPARQL + "?format=json&query=" + urllib.request.quote(query),
            headers={"User-Agent": "logbook-tools/1.0", "Accept": "application/sparql-results+json"},
        )
        data = json.loads(urllib.request.urlopen(req, timeout=60).read())
    except Exception as exc:  # offline ist ok, nur die deutschen Namen fehlen dann
        print("Wikidata nicht erreichbar (%s), nutze englische Namen" % exc)
        return {}
    out = {}
    for b in data["results"]["bindings"]:
        en = b.get("en", {}).get("value")
        if en:
            out[en] = b.get("de", {}).get("value") or en
    if cache_path:
        os.makedirs(os.path.dirname(cache_path), exist_ok=True)
        with open(cache_path, "w", encoding="utf-8") as handle:
            json.dump(out, handle, ensure_ascii=False, indent=1)
    return out


def geonames_admin1_map(features):
    """GeoNames-admin1-Code ("DE.11", "US.CA") -> ISO-3166-2 ("DE-BB", "US-CA").

    Natural Earth führt je Einheit den GeoNames-Code mit (`gn_a1_code`);
    Deutschland nutzt dort numerische Codes ("11"), die USA die ISO-Buchstaben.
    """
    mapping = {}
    for feature in features:
        props = feature["properties"]
        gn = props.get("gn_a1_code") or ""
        iso = (props.get("iso_3166_2") or "").upper()
        if "." in gn and CODE_RE.match(iso):
            mapping[gn.upper()] = iso
    return mapping


def load_capitals(cities_path, populated_path, admin1_map):
    """Hauptstädte der first-order-Einheiten (GeoNames PPLA/PPLC) als code -> (lat, lon, name, geonameid)."""
    capitals = {}
    with open(cities_path, encoding="utf-8") as handle:
        for row in csv.reader(handle, delimiter="\t"):
            # PPLA = Hauptstadt einer first-order-Einheit, PPLC = Landeshauptstadt
            # (Berlin und Washington stehen als PPLC in der Datei).
            if len(row) < 15 or row[7] not in ("PPLA", "PPLC"):
                continue
            country = row[8]
            if country not in WANTED_ISO2:
                continue
            code = admin1_map.get("%s.%s" % (country, row[10].upper()))
            if not code:
                continue
            try:
                pop = int(row[14])
                lat = float(row[4])
                lon = float(row[5])
            except ValueError:
                continue
            prev = capitals.get(code)
            if prev is None or pop > prev[3]:
                capitals[code] = (lat, lon, row[1], pop, row[0])
    # Deutsche Ortsnamen über die geonameid aus dem populated_places-Dump.
    de_names = {}
    if populated_path and os.path.exists(populated_path):
        try:
            with open(populated_path, encoding="utf-8") as handle:
                for feature in json.load(handle)["features"]:
                    props = feature["properties"]
                    gid = props.get("GEONAMEID")
                    name_de = props.get("NAME_DE")
                    if gid and name_de:
                        de_names[str(gid)] = name_de
        except Exception as exc:
            print("populated_places nicht lesbar (%s)" % exc)
    result = {}
    for code, (lat, lon, name, pop, gid) in capitals.items():
        result[code] = (lat, lon, de_names.get(gid, name), name)
    return result


# OurAirports weicht bei einzelnen Regionen vom ISO-3166-2 ab (Brandenburg
# heißt dort "DE-BR" statt "DE-BB"); "XX"/"U-A" sind unbekannt und fallen weg.
REGION_OVERRIDES = {
    "DE-BR": "DE-BB",
}


def load_regions(airports_csv, valid_codes):
    """IATA -> ISO-3166-2-Region aus der OurAirports-Datei (iso_region-Spalte).

    Nur Regionen, die es als Staat im Asset gibt, werden übernommen -
    "DE-XX"/"US-U-A" (unbekannt) und abweichende Schreibweisen ohne Gegenstück
    landen nicht in der Datei.
    """
    regions = {}
    with open(airports_csv, encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            country = (row.get("iso_country") or "").strip().upper()
            iata = (row.get("iata_code") or "").strip().upper()
            region = (row.get("iso_region") or "").strip().upper()
            region = REGION_OVERRIDES.get(region, region)
            if country in WANTED_ISO2 and iata and region in valid_codes:
                regions[iata] = region
    return dict(sorted(regions.items()))


def to_units(degrees):
    value = int(round(degrees * UNITS_PER_DEGREE))
    if not -32768 <= value <= 32767:
        raise SystemExit("Koordinate ausserhalb des int16-Bereichs: %s" % degrees)
    return value


def main():
    parser = argparse.ArgumentParser()
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    tmp = os.path.join(here, "tmp_ne")
    parser.add_argument("--admin1", default=os.path.join(tmp, "ne_10m_admin_1_states_provinces.geojson"))
    parser.add_argument("--cities", default=os.path.join(tmp, "cities5000.txt"))
    parser.add_argument("--populated", default=os.path.join(tmp, "populated_places.json"))
    parser.add_argument("--airports", default=os.path.join(tmp, "airports.csv"))
    parser.add_argument("--wikidata-de", default=os.path.join(tmp, "wikidata_us_states_de.json"))
    parser.add_argument("--out", default=os.path.join(here, "app", "src", "main", "assets", "state_shapes.bin"))
    parser.add_argument(
        "--regions-out",
        default=os.path.join(here, "app", "src", "main", "assets", "airports", "airports_regions.json"),
    )
    parser.add_argument("--no-download", action="store_true", help="nur lokale Dateien lesen")
    args = parser.parse_args()

    if not args.no_download:
        download(NE_ADMIN1_URL, args.admin1)
        download(CITIES5000_URL, args.cities)

    with open(args.admin1, encoding="utf-8") as handle:
        features = json.load(handle)["features"]
    capitals = load_capitals(args.cities, args.populated, geonames_admin1_map(features))
    de_us = wikidata_de_labels_us_states(args.wikidata_de)

    entries = []
    for feature in features:
        props = feature["properties"]
        iso2 = props.get("iso_a2") or ""
        if iso2 == "-99" or len(iso2) != 2:
            iso2 = ADMIN_TO_ISO2.get(props.get("admin") or "", "")
        if iso2 not in WANTED_ISO2:
            continue
        code = (props.get("iso_3166_2") or "").upper()
        if not CODE_RE.match(code) or not code.startswith(iso2 + "-"):
            continue

        raw_rings = []
        for raw in iter_rings(feature["geometry"]):
            points = [
                (round(float(lon), 3), round(float(lat), 3))
                for lon, lat in raw
                if float(lat) >= LAT_MIN
            ]
            if len(points) >= 3:
                raw_rings.append(points)
        if not raw_rings:
            continue

        lons = [p[0] for ring in raw_rings for p in ring]
        lats = [p[1] for ring in raw_rings for p in ring]
        bbox = (min(lons), min(lats), max(lons), max(lats))
        diagonal = max(bbox[2] - bbox[0], bbox[3] - bbox[1])
        if diagonal < SMALL_DEG:
            eps = min(EPS_SMALL, diagonal / 200.0)
        else:
            eps = EPS_DEFAULT
        eps = min(eps, diagonal / 4.0)

        rings = []
        for points in raw_rings:
            simple = simplify(points, eps)
            rings.append(simple if len(simple) >= 3 else points)

        name_en = props.get("name_en") or props.get("name") or code
        name_de = props.get("name_de") or ""
        if not name_de or name_de == name_en:
            # US-Bundesstaaten haben bei Natural Earth keinen deutschen Namen;
            # die holen wir von Wikidata, sonst bleibt es englisch.
            name_de = de_us.get(name_en, name_en)

        cap = capitals.get(code)
        if cap:
            cap_lat, cap_lon, cap_de, cap_en = cap
        else:
            cap_lat, cap_lon = float(props.get("latitude") or 0.0), float(props.get("longitude") or 0.0)
            cap_de = cap_en = ""

        entries.append(
            {
                "code": code,
                "iso2": iso2,
                "cap": (cap_lat, cap_lon),
                "label": (float(props.get("latitude") or 0.0), float(props.get("longitude") or 0.0)),
                "bbox": bbox,
                "name_de": name_de,
                "name_en": name_en,
                "cap_de": cap_de,
                "cap_en": cap_en,
                "rings": rings,
            }
        )

    entries.sort(key=lambda e: e["code"])
    if not entries:
        raise SystemExit("keine Staaten gefunden - stimmen die Eingabedateien?")

    out = bytearray()
    out += struct.pack("<iBBH", MAGIC, VERSION, 0, len(entries))

    all_rings = []
    for entry in entries:
        all_rings.extend(entry["rings"])
    ring_rows = []
    coord_cursor = 0
    ring_cursor = 0
    for entry in entries:
        entry["first_ring"] = ring_cursor
        for ring in entry["rings"]:
            ring_rows.append((len(ring), coord_cursor))
            coord_cursor += len(ring)
            ring_cursor += 1

    for entry in entries:
        de = entry["name_de"].encode("utf-8")
        en = entry["name_en"].encode("utf-8")
        cde = entry["cap_de"].encode("utf-8")
        cen = entry["cap_en"].encode("utf-8")
        for label, raw in (("name_de", de), ("name_en", en), ("cap_de", cde), ("cap_en", cen)):
            if len(raw) > 255:
                raise SystemExit("Name zu lang (%s): %s" % (label, entry["code"]))
        out += entry["code"].ljust(5).encode("ascii")
        out += entry["iso2"].encode("ascii")
        out += struct.pack("<ff", entry["cap"][0], entry["cap"][1])
        out += struct.pack("<ff", entry["label"][0], entry["label"][1])
        out += struct.pack("<ffff", *entry["bbox"])
        out += struct.pack("<B", len(de)) + de
        out += struct.pack("<B", len(en)) + en
        out += struct.pack("<B", len(cde)) + cde
        out += struct.pack("<B", len(cen)) + cen
        out += struct.pack("<II", entry["first_ring"], len(entry["rings"]))

    out += struct.pack("<I", len(all_rings))
    for count, offset in ring_rows:
        out += struct.pack("<II", count, offset)
    for ring in all_rings:
        for lon, lat in ring:
            out += struct.pack("<hh", to_units(lon), to_units(lat))
    out += struct.pack("<f", -90.0)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as handle:
        handle.write(out)

    points = sum(len(ring) for ring in all_rings)
    us = sum(1 for e in entries if e["iso2"] == "US")
    de = sum(1 for e in entries if e["iso2"] == "DE")
    no_cap = [e["code"] for e in entries if not e["cap_de"]]
    print(
        "%s: %d Einheiten (%d US, %d DE), %d Ringe, %d Punkte, %.2f MB, ohne Hauptstadt: %s"
        % (
            os.path.relpath(args.out, os.getcwd()),
            len(entries),
            us,
            de,
            len(all_rings),
            points,
            len(out) / 1048576.0,
            ",".join(no_cap) or "-",
        )
    )

    regions = load_regions(args.airports, {e["code"] for e in entries})
    os.makedirs(os.path.dirname(args.regions_out), exist_ok=True)
    with open(args.regions_out, "w", encoding="utf-8") as handle:
        json.dump(regions, handle, ensure_ascii=False)
    print(
        "%s: %d Flughafen-Regionen"
        % (os.path.relpath(args.regions_out, os.getcwd()), len(regions))
    )


if __name__ == "__main__":
    sys.exit(main())
