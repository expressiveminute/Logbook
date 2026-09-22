#!/usr/bin/env python3
"""Generiert aus world_land.json ein Kontinenten-Pfad-Snippet fuer das
App-Launcher-Icon (Globus, equirectangulare Projektion in einem Kreis)."""
import json
import math
import sys

SRC = 'app/src/main/assets/world_land.json'
CX, CY, R = 54.0, 52.0, 33.0
TOL = 0.4  # px, Douglas-Peucker

d = json.load(open(SRC))

def dp(points, tol):
    if len(points) < 3:
        return points
    def dist(p, a, b):
        (ax, ay), (bx, by) = a, b
        dx, dy = bx - ax, by - ay
        if dx == dy == 0:
            return math.hypot(p[0] - ax, p[1] - ay)
        t = ((p[0] - ax) * dx + (p[1] - ay) * dy) / (dx * dx + dy * dy)
        t = max(0.0, min(1.0, t))
        qx, qy = ax + t * dx, ay + t * dy
        return math.hypot(p[0] - qx, p[1] - qy)
    dmax, idx = 0.0, 0
    for i in range(1, len(points) - 1):
        dcur = dist(points[i], points[0], points[-1])
        if dcur > dmax:
            dmax, idx = dcur, i
    if dmax > tol:
        left = dp(points[:idx + 1], tol)
        right = dp(points[idx:], tol)
        return left[:-1] + right
    return [points[0], points[-1]]

def project(lon, lat):
    x = CX + lon / 180.0 * R
    y = CY - lat / 90.0 * R
    return (round(x, 2), round(y, 2))

segments = []
for feat in d['features']:
    coords = feat['geometry']['coordinates']  # Polygon: [rings]
    for ring in coords:
        proj = [project(lon, lat) for lon, lat in ring]
        ring = dp(proj, TOL)
        if len(ring) < 3:
            continue
        cmds = "M" + "L".join([f"{x},{y}" for x, y in ring])
        cmds += "Z"
        segments.append(cmds)

merged = " ".join(segments)
print(f"// {len(segments)} Ringe, ungezaelt Bytes: {len(merged)}")
print(merged)