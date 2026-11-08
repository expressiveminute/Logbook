#!/usr/bin/env python3
"""Build the offline country shape asset for the Logbook app.

Reads the cached Natural Earth admin-0 country polygons and produces a compact
binary file that assigns a country outline to every ISO-2 code, so the
Rubbelkarte can highlight the countries the user has already flown to.

Data source: Natural Earth 1:10m admin-0 countries (public domain), offizielles
GeoJSON aus dem natural-earth-vector-Repo (nvkelso):
https://github.com/nvkelso/natural-earth-vector
Das ist die feinste verfügbare Auflösung für Länderumrisse (10m-Küstenlinie
inklusive). German country names come from CLDR (Unicode, public domain)
https://cldr.unicode.org/

Usage:
    python3 tools/build_country_shapes.py
    python3 tools/build_country_shapes.py --countries tmp_ne/ne_10m_admin_0_countries.geojson \
        --cldr tmp_ne/cldr_de.json --out app/src/main/assets/country_shapes.bin

Binary format (little endian), see CountryShapes.kt:
    int32   magic "CSHP"
    uint8   version (2)
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
        int32   lon in 1/1440 degree
        int32   lat in 1/1440 degree
    float32 sentinel -90.0

Koordinaten liegen als int32 in Einheiten von 1/1440 Grad (~77 m) vor. Das
reicht bis zum staerksten Zoom der Kugel (~50 m/Pixel) und keeps die Kuesten
und Grenzen glatt; 1440 * 180 = 259200 passt bequem in einen signed 32-Bit-Wert.
Die Vereinfachung arbeitet mit 0.004 Grad (~450 m): grob genug, dass das Asset
klein bleibt, fein genug, dass benachbarte Laender an einer gemeinsamen Grenze
nicht sichtbar auseinanderlaufen ("doppelte" Grenzlinien).
"""

import argparse
import json
import os
import struct
import sys

MAGIC = 0x50485343  # Bytes "C","S","H","P" as little endian int
VERSION = 2

# Einheiten pro Grad. 1440 * 180 = 259200 passt in einen signed 32-Bit-Wert
# und ergibt rund 77 m Aufloesung.
UNITS_PER_DEGREE = 1440

# Ramer-Douglas-Peucker-Toleranz in Grad. 0.004 Grad (~450 m) ist auf der
# Weltkugel selbst im staerksten Zoom (~50 m/Pixel) kaum sichtbar, haelt die
# Kuesten aber deutlich genauer als die frueheren 0.04 Grad (~4,4 km).
# Antarktis bleibt eine strukturlose Masse und darf grober sein.
EPS_DEFAULT = 0.004
EPS_ANTARCTICA = 0.05

# Toleranz fuer Laender unter dieser Ausdehnung (Grad). Laenger ist 0.004 Grad
# nicht sinnvoll: der Umriss eines Kleinstaates waere dann grober als das Land.
EPS_SMALL = 0.001
SMALL_COUNTRY_DEG = 6.0

# Antarctica and the far south are cut away in the Rubbelkarte anyway, so the
# southern edge is trimmed to keep the file small.
LAT_MIN = -85.0

# Natural Earth marks a few undisputed countries as "-99" because of ISO
# disputes (ADM0_A3 codes FRA, NOR and KOS). Flights to Paris, Oslo or Pristina
# must still light up the map, so those are mapped by hand. Everything else
# with "-99" is a tiny disputed territory without an ISO-3166 code and is
# dropped.
ISO2_OVERRIDES = {
    "FRA": "FR",
    "NOR": "NO",
    "KOS": "XK",  # Kosovo, ISO-3166 user-assigned "XK"
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


def _seg_intersect(p1, p2, p3, p4):
    """Strikter Schnitt: beide Segmente müssen einander kreuzen (Vorzeichen
    echt verschieden) - bloße Berührung an einem gemeinsamen Eckpunkt zählt
    nicht (die 10m-Quelle hat viele doppelte aufeinanderfolgende Punkte)."""
    def ccw(a, b, c):
        return (c[1] - a[1]) * (b[0] - a[0]) - (b[1] - a[1]) * (c[0] - a[0])
    d1 = ccw(p3, p4, p1)
    d2 = ccw(p3, p4, p2)
    d3 = ccw(p1, p2, p3)
    d4 = ccw(p1, p2, p4)
    return (d1 * d2 < 0) and (d3 * d4 < 0)


def _dateline_segment(a, b):
    """Segment überspannt die Datumslinie (> 180° Längengrad) - in lon/lat sieht
    das wie ein Schnitt quer über die Karte aus, auf der Kugel ist es harmlos."""
    return abs(a[0] - b[0]) > 180.0


def has_self_intersection(ring):
    """Prüft per Grid-Beschleunigung, ob sich der Ring selbst schneidet.

    Douglas-Peucker kann an dichten Küsten Schlaufen (Figuren-acht) erzeugen -
    die zeichnen sich als Doppellinie und als Fehlfläche. Solche Ringe werden
    in [simplify_safe] mit feinerer Toleranz neu vereinfacht.
    """
    n = len(ring)
    if n < 4:
        return False
    segs = [(ring[i], ring[(i + 1) % n]) for i in range(n)]
    cell = 2.0
    grid = {}
    for i, (a, b) in enumerate(segs):
        if _dateline_segment(a, b):
            continue
        x0 = int(min(a[0], b[0]) // cell)
        x1 = int(max(a[0], b[0]) // cell)
        y0 = int(min(a[1], b[1]) // cell)
        y1 = int(max(a[1], b[1]) // cell)
        for gx in range(x0, x1 + 1):
            for gy in range(y0, y1 + 1):
                grid.setdefault((gx, gy), []).append(i)
    for i, (a, b) in enumerate(segs):
        if _dateline_segment(a, b):
            continue
        x0 = int(min(a[0], b[0]) // cell)
        x1 = int(max(a[0], b[0]) // cell)
        y0 = int(min(a[1], b[1]) // cell)
        y1 = int(max(a[1], b[1]) // cell)
        cand = set()
        for gx in range(x0, x1 + 1):
            for gy in range(y0, y1 + 1):
                cand.update(grid.get((gx, gy), ()))
        for j in cand:
            if j <= i + 1 or (i == 0 and j == n - 1):
                continue
            c, d = segs[j]
            if _dateline_segment(c, d):
                continue
            if _seg_intersect(a, b, c, d):
                return True
    return False


def _first_intersection(ring):
    """Erster Selbstschnitt eines Rings als (i, j, punkt) oder None.

    Segmente s_k = (ring[k], ring[(k+1) % n]); i und j sind Segmentindizes.
    """
    n = len(ring)
    if n < 4:
        return None
    segs = [(ring[i], ring[(i + 1) % n]) for i in range(n)]
    cell = 2.0
    grid = {}
    for i, (a, b) in enumerate(segs):
        if _dateline_segment(a, b):
            continue
        for gx in range(int(min(a[0], b[0]) // cell), int(max(a[0], b[0]) // cell) + 1):
            for gy in range(int(min(a[1], b[1]) // cell), int(max(a[1], b[1]) // cell) + 1):
                grid.setdefault((gx, gy), []).append(i)
    for i, (a, b) in enumerate(segs):
        if _dateline_segment(a, b):
            continue
        cand = set()
        for gx in range(int(min(a[0], b[0]) // cell), int(max(a[0], b[0]) // cell) + 1):
            for gy in range(int(min(a[1], b[1]) // cell), int(max(a[1], b[1]) // cell) + 1):
                cand.update(grid.get((gx, gy), ()))
        for j in cand:
            if j <= i + 1 or (i == 0 and j == n - 1):
                continue
            c, d = segs[j]
            if _dateline_segment(c, d):
                continue
            if _seg_intersect(a, b, c, d):
                x1, y1 = a; x2, y2 = b; x3, y3 = c; x4, y4 = d
                den = (x2 - x1) * (y4 - y3) - (y2 - y1) * (x4 - x3)
                if abs(den) < 1e-15:
                    return None
                t = ((x3 - x1) * (y4 - y3) - (y3 - y1) * (x4 - x3)) / den
                return (i, j, (x1 + t * (x2 - x1), y1 + t * (y2 - y1)))
    return None


def cut_loops(ring, depth=0):
    """Spaltet einen selbstschneidenden Ring an den Schnittpunkten in einfache
    Teilringe (eine Figuren-acht wird zu zwei Schleifen).

    Die 10m-Quelle enthält selbst an großen Ländern einzelne Schlaufen (z. B.
    Japan, Bangladesch); gezeichnet wären das Doppellinien und Fehlflächen.
    Ringe unter 8 Punkten bleiben unangetastet - dort ist eine Schlaufe eine
    degenerierte Spitze ohne sichtbare Fläche.
    """
    n = len(ring)
    if n < 4 or depth > 60:
        return [ring]
    hit = _first_intersection(ring)
    if hit is None:
        return [ring]
    i, j, p = hit
    # Schleife A: p -> ring[i+1..j] -> p ; Schleife B: p -> ring[j+1..n-1, 0..i] -> p
    loop_a = [p] + [ring[k % n] for k in range(i + 1, j + 1)] + [p]
    loop_b = [p] + [ring[k % n] for k in range(j + 1, n + i + 1)] + [p]
    out = []
    for loop in (loop_a, loop_b):
        # aufeinanderfolgende Duplikate entfernen
        clean = [loop[0]]
        for q in loop[1:]:
            if q != clean[-1]:
                clean.append(q)
        if len(clean) >= 2 and clean[0] == clean[-1]:
            clean = clean[:-1]
        if len(clean) >= 3:
            out.extend(cut_loops(clean, depth + 1))
    return out if out else [ring]


def simplify_safe(points, eps):
    """Wie [simplify], aber ohne Selbstschnitte; liefert eine Liste von Ringen.

    Erzeugt die Vereinfachung Schlaufen, wird die Toleranz halbiert und erneut
    versucht; bleibt der Ring (die 10m-Quelle hat selbst einige) selbst-
    schneidend, wird er an den Schnittpunkten in einfache Teilringe gespalten
    ([cut_loops]).
    """
    candidate = simplify(points, eps)
    if len(candidate) < 3:
        candidate = points
    e = eps
    while len(candidate) >= 3 and has_self_intersection(candidate):
        if e <= 1e-6:
            break
        e /= 2.0
        candidate = simplify(points, e)
    if len(candidate) < 3:
        candidate = points
    if has_self_intersection(candidate):
        return cut_loops(candidate)
    return [candidate]


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


def pip_in_rings(x, y, rings):
    """Even-odd point-in-polygon über alle Ringe eines Landes."""
    inside = False
    for ring in rings:
        n = len(ring)
        j = n - 1
        for i in range(n):
            xi, yi = ring[i]
            xj, yj = ring[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                inside = not inside
            j = i
    return inside


def label_anchor(rings):
    """Anchor for the country name: roughly the "pole of inaccessibility".

    The plain centroid of the largest ring lands in the sea for concave
    countries (Norway, Thailand, Croatia...), which would float the label off
    the coast. A coarse grid over the biggest ring's bbox picks the interior
    point with the largest distance to the outline instead. Returns latitude
    first, matching the binary layout.
    """
    biggest = max(rings, key=ring_area, default=None)
    if biggest is None or len(biggest) == 0:
        return 0.0, 0.0
    lons = [p[0] for p in biggest]
    lats = [p[1] for p in biggest]
    x0, x1 = min(lons), max(lons)
    y0, y1 = min(lats), max(lats)
    # Abstands-Näherung über dezimierte Ringe
    coarse = [r[::max(1, len(r) // 200)] for r in rings]
    best = None
    bestd = -1.0
    n = 24
    for i in range(1, n):
        for j in range(1, n):
            x = x0 + (x1 - x0) * i / n
            y = y0 + (y1 - y0) * j / n
            if not pip_in_rings(x, y, rings):
                continue
            d = min((x - px) ** 2 + (y - py) ** 2 for ring in coarse for px, py in ring)
            if d > bestd:
                bestd = d
                best = (x, y)
    if best is not None:
        return best[1], best[0]
    lon, lat = ring_centroid(biggest)
    return lat, lon


def quantize(points):
    """Rundet auf das 1/1440-Grad-Speicherraster des Assets."""
    return [
        (round(lon * UNITS_PER_DEGREE) / UNITS_PER_DEGREE,
         round(lat * UNITS_PER_DEGREE) / UNITS_PER_DEGREE)
        for lon, lat in points
    ]


def load_german_names(path):
    with open(path, encoding="utf-8") as handle:
        raw = json.load(handle)
    return raw["main"]["de"]["localeDisplayNames"]["territories"]


def to_units(degrees):
    value = int(round(degrees * UNITS_PER_DEGREE))
    if not -(2 ** 31) <= value < 2 ** 31:
        raise SystemExit("Koordinate ausserhalb des int32-Bereichs: %s" % degrees)
    return value


def compute_shared_keys(raw_entries):
    """Vertices, die in mindestens zwei Laendern vorkommen (gemeinsame Grenze).

    Natural Earth speichert gemeinsame Grenzen bit-identisch in beiden
    Ländern/Staaten. Die Dezimierung je Land darf diese Punkte nicht
    unterschiedlich entfernen - sonst klaffen zwischen den Laenderflaechen
    Luecken und die Grenzlinie wird doppelt mit Versatz gezeichnet. Der
    Runtime-Decimate haelt dieselben Punkte per sharedMask fest (siehe
    CountryShapes.kt). raw_entries: Tupel (kennung, ..., raw_rings); die
    Kennung ist beim Laender-Asset der ISO2, beim Staaten-Asset der Code -
    sie muss pro Flächeneinheit eindeutig sein, sonst kollabiert die Zählung.
    """
    users = {}
    for entry in raw_entries:
        entity_id = entry[0]
        raw_rings = entry[-1]
        local = set()
        for ring in raw_rings:
            for lon, lat in ring:
                local.add((to_units(lon), to_units(lat)))
        for key in local:
            users.setdefault(key, set()).add(entity_id)
    return {key for key, user_set in users.items() if len(user_set) >= 2}


def main():
    parser = argparse.ArgumentParser()
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument(
        "--countries", default=os.path.join(here, "tmp_ne", "ne_10m_admin_0_countries.geojson")
    )
    parser.add_argument("--cldr", default=os.path.join(here, "tmp_ne", "cldr_de.json"))
    parser.add_argument(
        "--out", default=os.path.join(here, "app", "src", "main", "assets", "country_shapes.bin")
    )
    args = parser.parse_args()

    with open(args.countries, encoding="utf-8") as handle:
        countries = json.load(handle)["features"]
    german = load_german_names(args.cldr)

    # Erste Runde: Roh-Ringe je Land sammeln, dann die gemeinsamen Grenz-Vertices
    # bestimmen (siehe compute_shared_keys).
    raw_entries = []
    for feature in countries:
        props = feature["properties"]
        iso2 = props.get("ISO_A2") or ""
        if iso2 == "-99":
            iso2 = ISO2_OVERRIDES.get(props.get("ADM0_A3") or "", "")
        if len(iso2) != 2:
            continue
        raw_rings = []
        for raw in iter_rings(feature["geometry"]):
            # Keine Rundung auf 3 Dezimalstellen: die 1/1440-Grad-Skala des
            # Assets ist feiner als 0.001 Grad und wuerde sonst beschnitten.
            points = [
                (float(lon), float(lat))
                for lon, lat in raw
                if float(lat) >= LAT_MIN
            ]
            if len(points) >= 3:
                raw_rings.append(points)
        if not raw_rings:
            continue
        raw_entries.append((iso2, props, raw_rings))

    shared_keys = compute_shared_keys(raw_entries)
    print("%d gemeinsame Grenz-Vertices in >=2 Laendern" % len(shared_keys))

    entries = []
    for iso2, props, raw_rings in raw_entries:
        numeric = props.get("ISO_N3") or ""

        # Die Toleranz richtet sich nach der Groesse des Landes: 0.004 Grad sind
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
            # Selbstschnittfreie Vereinfachung: Schnitte im Ring zeichneten
            # sich als Doppellinien und Fehlflächen (z. B. Korsika, Ägypten).
            # simplify_safe liefert eine Liste (ggf. gespaltene Teilringe).
            simples = simplify_safe(points, eps)
            if not simples:
                simples = [points]
            # Originale Vertices auf das Speicherraster (Schluessel bleiben exakt).
            okeys = [(to_units(lon), to_units(lat)) for lon, lat in points]
            # Vereinigung der von der Vereinfachung behaltenen Punkte ueber alle
            # (ggf. gespaltenen) Teilringe.
            skeys = set()
            for simple in simples:
                # Fuehrt die Vereinfachung zu einem entarteten Ring, bleibt das
                # Original erhalten - lieber ein paar Punkte zu viel als ein
                # Land, das auf der Karte fehlt.
                src = simple if len(simple) >= 3 else points
                skeys.update((to_units(lon), to_units(lat)) for lon, lat in src)
            # Gemeinsame Grenz-Vertices werden NICHT dezimiert: nur so bleibt
            # die Grenze in beiden Laendern bit-identisch und es klaffen keine
            # Luecken zwischen den Laenderflaechen.
            merged_keys = [k for k in okeys if k in skeys or k in shared_keys]
            if len(merged_keys) < 3:
                merged_keys = okeys
            q = [(x / UNITS_PER_DEGREE, y / UNITS_PER_DEGREE) for x, y in merged_keys]
            # Die Schluessel sind exakt auf das Speicherraster; erneut pruefen,
            # ob der Ring sich selbst schneidet (die Quelle hat einzelne
            # Schlaufen, z. B. Aleuten). Gegebenenfalls wird der Ring gespalten.
            if has_self_intersection(q):
                cut = False
                for loop in cut_loops(q):
                    loop = quantize(loop)
                    if len(loop) >= 3:
                        rings.append(loop)
                        cut = True
                if not cut:
                    rings.append(q)
            else:
                rings.append(q)
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
                "<ii",
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
