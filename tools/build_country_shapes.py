#!/usr/bin/env python3
"""Build the offline country shape asset for the Logbook app.

Reads the cached Natural Earth admin-0 country polygons and produces a compact
binary file that assigns a country outline to every ISO-2 code, so the
Rubbelkarte can highlight the countries the user has already flown to.

Data source: Natural Earth (public domain)
https://www.naturalearthdata.com/downloads/110m-cultural-vectors/

German country names come from CLDR (Unicode, public domain)
https://cldr.unicode.org/

Usage:
    python3 tools/build_country_shapes.py
    python3 tools/build_country_shapes.py --countries tmp_ne/admin_0_countries.json \
        --cldr tmp_ne/cldr_de.json --out app/src/main/assets/country_shapes.bin

Binary format (little endian), see CountryShapes.kt:
    int32   magic "CSHP"
    uint8   version
    uint8   reserved
    uint16  countryCount
    per country:
        char[2] iso2
        uint32  firstRingIndex
        uint32  ringCount
        float32 labelLat, labelLon
        float32 minLon, minLat, maxLon, maxLat
        uint8   nameDeLength, then UTF-8 name
        uint8   nameEnLength, then UTF-8 name
    uint32  totalRingCount
    per ring:
        uint32  pointCount
        uint32  coordOffset (in coord pairs)
    per point:
        int16   lon in 1/180 degree
        int16   lat in 1/180 degree
    float32 sentinel -90.0

Koordinaten liegen als int16 vor, damit das Asset klein bleibt. 180 Einheiten
pro Grad passen gerade noch in einen signed 16-Bit-Wert (180 Grad * 180 = 32400)
und entsprechen rund 110 m Auflösung - auf einer telefonbreiten Weltkarte
faellt das nicht auf.
"""

import argparse
import json
import os
import struct
import sys

MAGIC = 0x50485343  # Bytes "C","S","H","P" as little endian int
VERSION = 1

# Einheiten pro Grad. 180 * 180 = 32400 passt in einen signed 16-Bit-Wert.
UNITS_PER_DEGREE = 180

# Ramer-Douglas-Peucker tolerance in degrees. 0.04 deg is far below one pixel
# on a phone-width world map, Antarctica is a featureless bulk and can be much
# coarser.
EPS_DEFAULT = 0.04
EPS_ANTARCTICA = 0.4

# Toleranz fuer Laender unter dieser Ausdehnung (Grad). Laenger ist 0.04 Grad
# nicht sinnvoll: der Umriss eines Kleinstaates waere dann grober als das Land.
EPS_SMALL = 0.01
SMALL_COUNTRY_DEG = 6.0

# Antarctica and the far south are cut away in the Rubbelkarte anyway, so the
# southern edge is trimmed to keep the file small.
LAT_MIN = -85.0

# Natural Earth marks a few undisputed countries as "-99" because of ISO
# disputes (ADM0_A3 codes FRA and NOR). Flights to Paris or Oslo must still
# light up the map, so those two are mapped by hand. Everything else with
# "-99" is a tiny disputed territory without an ISO-3166 code and is dropped.
ISO2_OVERRIDES = {
    "FRA": "FR",
    "NOR": "NO",
}


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
    """Ramer-Douglas-Peucker simplification, endpoints always kept."""
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


def ring_area(points):
    total = 0.0
    n = len(points)
    for i in range(n):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % n]
        total += x1 * y2 - x2 * y1
    return abs(total) * 0.5


def ring_centroid(points):
    n = len(points)
    sx = sy = 0.0
    for x, y in points:
        sx += x
        sy += y
    return sx / n, sy / n


def label_anchor(rings):
    """Anchor for the country name: centroid of the largest ring.

    Good enough for a poster map; the label collision helper in the app keeps
    the rest tidy. Returns latitude first, matching the binary layout.
    """
    biggest = max(rings, key=ring_area, default=None)
    if biggest is None or len(biggest) == 0:
        return 0.0, 0.0
    lon, lat = ring_centroid(biggest)
    return lat, lon


def load_german_names(path):
    with open(path, encoding="utf-8") as handle:
        raw = json.load(handle)
    return raw["main"]["de"]["localeDisplayNames"]["territories"]


def to_units(degrees):
    value = int(round(degrees * UNITS_PER_DEGREE))
    if not -32768 <= value <= 32767:
        raise SystemExit("Koordinate ausserhalb des int16-Bereichs: %s" % degrees)
    return value


def main():
    parser = argparse.ArgumentParser()
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument(
        "--countries", default=os.path.join(here, "tmp_ne", "admin_0_countries.json")
    )
    parser.add_argument("--cldr", default=os.path.join(here, "tmp_ne", "cldr_de.json"))
    parser.add_argument(
        "--out", default=os.path.join(here, "app", "src", "main", "assets", "country_shapes.bin")
    )
    args = parser.parse_args()

    with open(args.countries, encoding="utf-8") as handle:
        countries = json.load(handle)["features"]
    german = load_german_names(args.cldr)

    entries = []
    for feature in countries:
        props = feature["properties"]
        iso2 = props.get("ISO_A2") or ""
        if iso2 == "-99":
            iso2 = ISO2_OVERRIDES.get(props.get("ADM0_A3") or "", "")
        if len(iso2) != 2:
            continue
        numeric = props.get("ISO_N3") or ""
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

        # Die Toleranz richtet sich nach der Groesse des Landes: 0.04 Grad sind
        # fuer Russland grosszuegig, wuerden Dubai oder Liechtenstein aber zu
        # einem unbrauchbaren Strich zusammenziehen. Deshalb wird zuerst die
        # Ausdehnung bestimmt und die Toleranz darauf abgestimmt.
        lons = [p[0] for ring in raw_rings for p in ring]
        lats = [p[1] for ring in raw_rings for p in ring]
        bbox = (min(lons), min(lats), max(lons), max(lats))
        diagonal = max(bbox[2] - bbox[0], bbox[3] - bbox[1])
        if iso2 == "AQ":
            eps = EPS_ANTARCTICA
        elif diagonal < SMALL_COUNTRY_DEG:
            eps = min(EPS_SMALL, diagonal / 200.0)
        else:
            eps = EPS_DEFAULT
        # Nie groesser als ein Viertel des Landes, sonst faellt der Ring auf
        # zwei Punkte zusammen.
        eps = min(eps, diagonal / 4.0)

        rings = []
        for points in raw_rings:
            simple = simplify(points, eps)
            # Fuehrt die Vereinfachung zu einem entarteten Ring, bleibt das
            # Original erhalten - lieber ein paar Punkte zu viel als ein Land,
            # das auf der Karte fehlt.
            rings.append(simple if len(simple) >= 3 else points)
        label_lat, label_lon = label_anchor(rings)
        # CLDR nennt die Territorien hier ueber Alpha-2, zusaetzlich gibt es
        # einige numerische Kontinente ("015" = Nordafrika).
        name_de = german.get(iso2) or german.get(numeric) or props.get("NAME") or iso2
        name_en = props.get("NAME") or iso2
        entries.append(
            {
                "iso2": iso2,
                "rings": rings,
                "label": (label_lat, label_lon),
                "bbox": bbox,
                "name_de": name_de,
                "name_en": name_en,
            }
        )

    entries.sort(key=lambda e: e["iso2"])

    out = bytearray()
    out += struct.pack("<iBBH", MAGIC, VERSION, 0, len(entries))

    ring_table_size = 0
    all_rings = []
    for entry in entries:
        all_rings.extend(entry["rings"])
    ring_table_size = len(all_rings)

    ring_cursor = 0
    coord_cursor = 0
    ring_rows = []
    for entry in entries:
        entry["first_ring"] = ring_cursor
        for ring in entry["rings"]:
            ring_rows.append((len(ring), coord_cursor))
            coord_cursor += len(ring)
            ring_cursor += 1

    for entry in entries:
        de = entry["name_de"].encode("utf-8")
        en = entry["name_en"].encode("utf-8")
        if len(de) > 255 or len(en) > 255:
            raise SystemExit("Laendername zu lang: %s" % entry["iso2"])
        out += entry["iso2"].encode("ascii")
        out += struct.pack("<II", entry["first_ring"], len(entry["rings"]))
        out += struct.pack("<ff", entry["label"][0], entry["label"][1])
        out += struct.pack("<ffff", *entry["bbox"])
        out += struct.pack("<B", len(de)) + de
        out += struct.pack("<B", len(en)) + en

    out += struct.pack("<I", ring_table_size)
    for count, offset in ring_rows:
        out += struct.pack("<II", count, offset)

    for ring in all_rings:
        for lon, lat in ring:
            out += struct.pack(
                "<hh",
                to_units(lon),
                to_units(lat),
            )
    out += struct.pack("<f", -90.0)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as handle:
        handle.write(out)

    points = sum(len(ring) for ring in all_rings)
    print(
        "%s: %d Laender, %d Ringe, %d Punkte, %.2f MB"
        % (os.path.relpath(args.out, os.getcwd()), len(entries), ring_table_size, points, len(out) / 1048576.0)
    )


if __name__ == "__main__":
    sys.exit(main())
