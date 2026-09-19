#!/usr/bin/env python3
"""Build the offline world land LOD binary asset for the Logbook app.

Reads Natural Earth 1:10m land polygons (GeoJSON) and produces a compact
binary file with multiple levels of detail (LOD), so the world map can be
rendered fully offline without any network access.

Data source: Natural Earth (public domain)
https://www.naturalearthdata.com/downloads/10m-physical-vectors/10m-land

Usage:
    python3 tools/build_world_land_lod.py          # auto-downloads source
    python3 tools/build_world_land_lod.py --source ne_10m_land.json
"""

import argparse
import json
import os
import struct
import sys
import urllib.request

SOURCE_URL = (
    "https://raw.githubusercontent.com/martynafford/natural-earth-geojson/"
    "master/10m/physical/ne_10m_land.json"
)

# (min_zoom, max_zoom, simplification_epsilon_degrees)
LEVELS = [
    (1.0, 2.5, 0.10),
    (2.5, 5.0, 0.02),
    (5.0, 7.0, 0.004),
    (7.0, 10.0, 0.001),
]

MAGIC = b"LBLD"
VERSION = 1


def iter_rings(geometry):
    if geometry["type"] == "Polygon":
        for ring in geometry["coordinates"]:
            yield ring
    elif geometry["type"] == "MultiPolygon":
        for polygon in geometry["coordinates"]:
            for ring in polygon:
                yield ring


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


def download_source(dest):
    print(f"Downloading Natural Earth 1:10m land GeoJSON ...")
    urllib.request.urlretrieve(SOURCE_URL, dest)
    print(f"Downloaded to {dest}")


def build(data, out_path):
    total_size = 0
    with open(out_path, "wb") as fh:
        fh.write(MAGIC)
        fh.write(struct.pack("<B", VERSION))
        fh.write(struct.pack("<B", len(LEVELS)))
        for level_idx, (min_zoom, max_zoom, eps) in enumerate(LEVELS):
            rings = []
            total_points = 0
            for feature in data["features"]:
                for ring in iter_rings(feature["geometry"]):
                    if len(ring) > 1 and ring[0] == ring[-1]:
                        ring = ring[:-1]
                    if len(ring) < 3:
                        continue
                    simplified = [
                        (float(x), float(y)) for x, y in simplify_ring(ring, eps)
                    ]
                    if len(simplified) < 3:
                        continue
                    rings.append(simplified)
                    total_points += len(simplified)

            fh.write(struct.pack("<d", min_zoom))
            fh.write(struct.pack("<d", max_zoom))
            fh.write(struct.pack("<I", len(rings)))
            fh.write(struct.pack("<I", total_points))
            level_size = 0
            for ring in rings:
                lons = [p[0] for p in ring]
                lats = [p[1] for p in ring]
                fh.write(struct.pack("<ffff", min(lons), min(lats), max(lons), max(lats)))
                fh.write(struct.pack("<I", len(ring)))
                for lon, lat in ring:
                    fh.write(struct.pack("<ff", lon, lat))
                level_size += 16 + 4 + len(ring) * 8
            total_size += level_size
            print(f"  level {level_idx}: zoom {min_zoom}-{max_zoom} "
                  f"(eps {eps}) rings={len(rings)} points={total_points} "
                  f"size={level_size / 1024:.0f} KB")
        fh.write(struct.pack("<f", -90.0))  # sentinel to catch offset bugs
    print(f"Wrote {out_path}")
    print(f"  total binary size: {total_size / 1024:.0f} KB")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default=None,
                        help="Path to ne_10m_land.json (fetched if omitted)")
    parser.add_argument("--out", default=None,
                        help="Output binary path")
    args = parser.parse_args()

    if args.source and os.path.exists(args.source):
        src = args.source
    else:
        tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../tmp_ne_10m_land.json")
        src = args.source or tmp
        if not os.path.exists(src):
            download_source(src)

    with open(src) as f:
        data = json.load(f)

    out = args.out or os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "../app/src/main/assets/world_land_lod.bin",
    )
    build(data, out)


if __name__ == "__main__":
    main()