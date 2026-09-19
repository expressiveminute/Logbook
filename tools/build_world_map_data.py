#!/usr/bin/env python3
"""Build offline world map data assets for the Logbook app.

Generates:
  - app/src/main/assets/cities.json
  - app/src/main/assets/countries.json
  - app/src/main/assets/country_borders.bin

Data sources (public domain):
  - Natural Earth 1:10m populated places
  - Natural Earth 1:10m admin 0 countries
  - Natural Earth 1:10m admin 0 boundary lines land
  - CLDR German territory names

Usage:
    python3 tools/build_world_map_data.py           # auto-downloads sources
    python3 tools/build_world_map_data.py --help
"""

import argparse
import json
import math
import os
import struct
import sys
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(BASE, "../app/src/main/assets")
TMP = os.path.join(BASE, "../tmp_ne")

URLS = {
    "populated_places": (
        "https://raw.githubusercontent.com/martynafford/natural-earth-geojson/"
        "master/10m/cultural/ne_10m_populated_places.json"
    ),
    "admin_0_countries": (
        "https://raw.githubusercontent.com/martynafford/natural-earth-geojson/"
        "master/10m/cultural/ne_10m_admin_0_countries.json"
    ),
    "boundary_lines_land": (
        "https://raw.githubusercontent.com/martynafford/natural-earth-geojson/"
        "master/10m/cultural/ne_10m_admin_0_boundary_lines_land.json"
    ),
    "cldr_de": (
        "https://raw.githubusercontent.com/unicode-org/cldr-json/main/"
        "cldr-json/cldr-localenames-full/main/de/territories.json"
    ),
}


def ensure_source(key):
    os.makedirs(TMP, exist_ok=True)
    path = os.path.join(TMP, f"{key}.json")
    if not os.path.exists(path):
        print(f"Downloading {key} ...")
        urllib.request.urlretrieve(URLS[key], path)
    with open(path) as f:
        return json.load(f)


def simplify_ring(points, eps):
    """Ramer-Douglas-Peucker simplification in degrees."""
    if len(points) <= 4 or eps <= 0:
        return points

    def sqdist(u, v):
        dx = u[0] - v[0]
        dy = u[1] - v[1]
        return dx * dx + dy * dy

    def point_seg_dist(p, a, b):
        ax, ay = a
        bx, by = b
        px, py = p
        dx = bx - ax
        dy = by - ay
        length2 = dx * dx + dy * dy
        if length2 == 0:
            return sqdist(p, a)
        t = ((px - ax) * dx + (py - ay) * dy) / length2
        t = max(0.0, min(1.0, t))
        cx = ax + t * dx
        cy = ay + t * dy
        return (px - cx) ** 2 + (py - cy) ** 2

    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    eps2 = eps * eps
    while stack:
        a, b = stack.pop()
        if b <= a + 1:
            continue
        max_d = -1.0
        max_i = -1
        for i in range(a + 1, b):
            d = point_seg_dist(points[i], points[a], points[b])
            if d > max_d:
                max_d = d
                max_i = i
        if max_d > eps2:
            keep[max_i] = True
            stack.append((a, max_i))
            stack.append((max_i, b))
    return [p for p, k in zip(points, keep) if k]


def ring_bbox(points):
    lons = [p[0] for p in points]
    lats = [p[1] for p in points]
    return min(lons), min(lats), max(lons), max(lats)


def polygon_centroid(geometry):
    """Area-weighted centroid over all rings of a polygon/multipolygon.

    Returns [lon, lat] in degrees or None when no usable ring exists.
    """
    rings = []
    if geometry["type"] == "Polygon":
        rings = geometry["coordinates"]
    elif geometry["type"] == "MultiPolygon":
        for poly in geometry["coordinates"]:
            rings.extend(poly)
    total_area = 0.0
    tx = 0.0
    ty = 0.0
    for ring in rings:
        if len(ring) < 3:
            continue
        a = 0.0
        cx = 0.0
        cy = 0.0
        for i in range(len(ring)):
            x0, y0 = ring[i]
            x1, y1 = ring[(i + 1) % len(ring)]
            cross = x0 * y1 - x1 * y0
            a += cross
            cx += (x0 + x1) * cross
            cy += (y0 + y1) * cross
        a *= 0.5
        if abs(a) < 1e-12:
            continue
        absa = abs(a)
        total_area += absa
        tx += (cx / (6.0 * a)) * absa
        ty += (cy / (6.0 * a)) * absa
    if total_area == 0:
        return None
    cx = tx / total_area
    cy = ty / total_area
    if cx > 180:
        cx -= 360
    if cx < -180:
        cx += 360
    return [cx, cy]


def physical_centroid(geometry):
    """Centroid of a country's largest polygon only.

    Using the biggest landmass avoids labels being pulled towards small
    far-away territories (archipelagos, overseas colonies) and keeps the
    label close to the geographic middle of the main body of the country.
    """
    polys = []
    if geometry["type"] == "Polygon":
        polys = [geometry["coordinates"]]
    elif geometry["type"] == "MultiPolygon":
        polys = geometry["coordinates"]
    best = None
    best_area = 0.0
    for poly in polys:
        area = 0.0
        for ring in poly:
            if len(ring) < 3:
                continue
            a = 0.0
            for i in range(len(ring)):
                x0, y0 = ring[i]
                x1, y1 = ring[(i + 1) % len(ring)]
                a += x0 * y1 - x1 * y0
            area += abs(a) * 0.5
        if area > best_area:
            best_area = area
            best = poly
    if best is None:
        return None
    return polygon_centroid({"type": "Polygon", "coordinates": best})


def geometry_bbox(geometry):
    """Overall lon/lat bounds [minLon, minLat, maxLon, maxLat] of a geometry."""
    rings = []
    if geometry["type"] == "Polygon":
        rings = geometry["coordinates"]
    elif geometry["type"] == "MultiPolygon":
        for poly in geometry["coordinates"]:
            rings.extend(poly)
    lons = []
    lats = []
    for ring in rings:
        for lon, lat in ring:
            lons.append(lon)
            lats.append(lat)
    if not lons:
        return None
    return [min(lons), min(lats), max(lons), max(lats)]


def is_latin_script(text):
    """True when a string only uses Latin script characters.

    Used to make sure the native-language city name is always rendered in
    Latin script (transliterated), as required on the map.
    """
    if not text:
        return False
    return all(
        ord(ch) < 0x0250 or 0x1E00 <= ord(ch) <= 0x1EFF
        for ch in text
        if not ch.isspace()
    )


def build_cities(pp_data, existing_cities_path=None):
    """Build cities.json from populated places.

    Rank by estimated population (POP_MAX), which is then used as a
    progressive-disclosure level on the map:
      rank 0: >= 3,000,000   (megacities, visible from low zoom)
      rank 1: >= 1,000,000
      rank 2: >=   300,000
      rank 3: >=   100,000   (only near high zoom)

    Each city stores its names in the three display languages:
      key        = native/local name in Latin script (transliterated)
      val[4]     = German name (NAME_DE, fallback native)
      val[5]     = English name (NAME_EN, fallback native)

    The native name prefers Natural Earth's parallel name (NAMEPAR), which
    is the Latin script transliteration of the local name (e.g. Moskva,
    Athenai), and falls back to the regular NAME field otherwise.
    """
    def pop_to_rank(pop):
        if pop >= 3_000_000:
            return 0
        if pop >= 1_000_000:
            return 1
        if pop >= 300_000:
            return 2
        if pop >= 100_000:
            return 3
        return None  # too small to include

    cities = {}

    for f in pp_data["features"]:
        p = f["properties"]
        coords = f["geometry"]["coordinates"]

        # Native/local name in Latin script.
        native = (p.get("NAMEPAR") or "").strip()
        if native and not is_latin_script(native):
            native = ""
        if not native:
            native = (p.get("NAME") or "").strip()
        if not native:
            native = (p.get("NAMEASCII") or "").strip()
        if not native:
            continue
        if not is_latin_script(native):
            native = (p.get("NAMEASCII") or "").strip()
        if not native:
            continue

        cap = 1 if p.get("ADM0CAP") else 0
        rank = pop_to_rank(p.get("POP_MAX") or 0)
        if rank is None:
            continue
        de_name = (p.get("NAME_DE") or "").strip() or native
        en_name = (p.get("NAME_EN") or "").strip() or native
        lon, lat = coords[0], coords[1]
        if native in cities:
            # Same native name for several NE features: keep the entry, but
            # promote rank so at least one version stays visible.
            if cities[native][2] > rank:
                cities[native][2] = rank
            if cap:
                cities[native][3] = cap
            continue
        cities[native] = [round(lat, 4), round(lon, 4), rank, cap, de_name, en_name]

    if existing_cities_path and os.path.exists(existing_cities_path):
        # Migrate previously curated cities: map them onto the freshly built
        # NE entries by coordinates, so that a deliberately curated city is
        # kept in the data even if Natural Earth dropped it. Ranks come from
        # the NE population brackets above, never from the old file (which
        # accumulated rank-0 values across earlier generator runs).
        with open(existing_cities_path) as f:
            old = json.load(f)
        added = 0
        for name, val in old.items():
            olat = float(val[0])
            olon = float(val[1])
            ocap = 1 if len(val) > 3 and val[3] else 0
            match = min(
                cities.items(),
                key=lambda kv: abs(kv[1][0] - olat) + abs(kv[1][1] - olon),
                default=None,
            )
            if match and abs(match[1][0] - olat) < 0.75 and abs(match[1][1] - olon) < 0.75:
                if ocap:
                    match[1][3] = ocap
                continue
            if name not in cities:
                cities[name] = [olat, olon, 3, ocap, name, name]
                added += 1
        if added:
            print(f"  curated migration: added {added}")

    print(f"  cities.json: {len(cities)} cities "
          f"(rank->count: {dict(sorted((r, sum(1 for v in cities.values() if v[2] == r)) for r in range(4)))})")
    return cities


def build_countries(countries_data, pp_data, cldr_data):
    """Build countries.json from admin 0 countries + CLDR German names."""
    # Build CLDR map: ISO_A2 -> German name
    cldr_map = {}
    if cldr_data:
        try:
            territories = cldr_data["main"]["de"]["localeDisplayNames"]["territories"]
            for key, name in territories.items():
                # CLDR has 2-letter ISO codes as keys (like "DE", "FR")
                if len(key) == 2 and key.isalpha() and key.isupper():
                    cldr_map[key] = name
        except (KeyError, TypeError):
            pass

    # Some sovereign states have no valid ISO_A2 in the dataset;
    # map ADM0_A3 -> ISO_A2 for those.
    A3_TO_A2 = {
        "FRA": "FR",
        "NOR": "NO",
    }

    countries = []
    for f in countries_data["features"]:
        p = f["properties"]
        iso_a2 = p.get("ISO_A2") or ""
        if iso_a2 in ("-99", "-9", ""):
            iso_a2 = A3_TO_A2.get(p.get("ADM0_A3") or "", "")
        name = p.get("NAME") or p.get("NAME_LONG") or ""
        labelrank = p.get("LABELRANK") or 6
        if not name:
            continue
        # Prefer German name
        de_name = cldr_map.get(iso_a2)
        if de_name:
            name = de_name
        # Label position: geographic center of the main landmass, not the
        # capital, so the name sits inside the country.
        pos = physical_centroid(f["geometry"])
        if not pos:
            pos = polygon_centroid(f["geometry"])
        bbox = geometry_bbox(f["geometry"])
        if not pos or not bbox:
            continue
        lon, lat = pos[0], pos[1]
        # Map LABELRANK (1-10, lower=more important) to tier 0-3
        if labelrank <= 2:
            tier = 0
        elif labelrank <= 4:
            tier = 1
        elif labelrank <= 6:
            tier = 2
        else:
            tier = 3
        countries.append({
            "n": name,
            "lat": round(lat, 4),
            "lon": round(lon, 4),
            "r": tier,
            "bx": [round(bbox[0], 4), round(bbox[1], 4),
                   round(bbox[2], 4), round(bbox[3], 4)],
        })

    countries.sort(key=lambda c: (c["r"], c["n"]))
    print(f"  countries.json: {len(countries)} countries")
    return countries


def build_borders(borders_data):
    """Build country_borders.bin from boundary lines.

    Format (magic "CBND", version 1):
      header:  magic(4) version(u8) levelCount(u8)
      per level: minZoom(d) maxZoom(d) lineCount(u32) totalPoints(u32)
                 then per line: bbox(4 x f32) pointCount(u32) points(n x 2 x f32)
      sentinel: f32 -90.0
    """
    # (min_zoom, max_zoom, simplification_epsilon_degrees)
    LEVELS = [
        (1.0, 5.0, 0.02),
        (5.0, 7.0, 0.008),
        (7.0, 10.0, 0.003),
    ]
    MAGIC = b"CBND"
    VERSION = 1

    raw_lines = []
    for f in borders_data["features"]:
        geom = f["geometry"]
        if geom["type"] == "LineString":
            lines = [geom["coordinates"]]
        elif geom["type"] == "MultiLineString":
            lines = geom["coordinates"]
        else:
            continue
        for line in lines:
            if len(line) >= 2:
                raw_lines.append([(float(x), float(y)) for x, y in line])

    out_path = os.path.join(ASSETS, "country_borders.bin")
    total_size = 0
    with open(out_path, "wb") as fh:
        fh.write(MAGIC)
        fh.write(struct.pack("<B", VERSION))
        fh.write(struct.pack("<B", len(LEVELS)))
        for level_idx, (min_zoom, max_zoom, eps) in enumerate(LEVELS):
            lines = []
            total_points = 0
            for line in raw_lines:
                simplified = simplify_ring(line, eps)
                if len(simplified) < 2:
                    continue
                lines.append(simplified)
                total_points += len(simplified)

            fh.write(struct.pack("<d", min_zoom))
            fh.write(struct.pack("<d", max_zoom))
            fh.write(struct.pack("<I", len(lines)))
            fh.write(struct.pack("<I", total_points))
            level_size = 0
            for line in lines:
                lons = [p[0] for p in line]
                lats = [p[1] for p in line]
                fh.write(struct.pack("<ffff",
                    min(lons), min(lats), max(lons), max(lats)))
                fh.write(struct.pack("<I", len(line)))
                for lon, lat in line:
                    fh.write(struct.pack("<ff", lon, lat))
                level_size += 16 + 4 + len(line) * 8
            total_size += level_size
            print(f"  borders level {level_idx}: zoom {min_zoom}-{max_zoom} "
                  f"(eps {eps}) lines={len(lines)} points={total_points} "
                  f"size={level_size / 1024:.0f} KB")
        fh.write(struct.pack("<f", -90.0))

    print(f"  country_borders.bin: {total_size / 1024:.0f} KB total")
    return out_path


def main():
    parser = argparse.ArgumentParser(description="Build world map data assets")
    args = parser.parse_args()

    print("Building world map data assets ...")
    os.makedirs(ASSETS, exist_ok=True)

    # 1. Download / load sources
    print("\nLoading sources ...")
    pp_data = ensure_source("populated_places")
    countries_data = ensure_source("admin_0_countries")
    borders_data = ensure_source("boundary_lines_land")
    try:
        cldr_data = ensure_source("cldr_de")
    except Exception:
        cldr_data = None
        print("  Warning: CLDR de not loaded, using English names")

    # 2. Cities
    print("\nGenerating cities.json ...")
    existing_cities = os.path.join(ASSETS, "cities.json")
    cities = build_cities(pp_data, existing_cities_path=existing_cities)
    with open(os.path.join(ASSETS, "cities.json"), "w") as f:
        json.dump(cities, f, ensure_ascii=False, separators=(",", ":"))

    # 3. Countries
    print("\nGenerating countries.json ...")
    countries = build_countries(countries_data, pp_data, cldr_data)
    with open(os.path.join(ASSETS, "countries.json"), "w") as f:
        json.dump(countries, f, ensure_ascii=False, separators=(",", ":"))

    # 4. Borders
    print("\nGenerating country_borders.bin ...")
    build_borders(borders_data)

    print("\nDone.")


if __name__ == "__main__":
    main()
