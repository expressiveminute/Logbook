#!/usr/bin/env python3
"""Erzeugt die Launcher-Logos aus EINER Geometrie: PNG-Vorschau und die
Android-VectorDrawables (Vordergrund + Monochrom) samt adaptiven Icons.

Aufruf:  python3 tools/logo_gen.py
Vorschau-PNGs landen in /tmp/opencode/logo.

Alle Koordinaten im 108dp-Foreground-Raster. Sichtbarer Kreis: r=36 um (54,54),
sichere Zone r=33. Alles Wichtige bleibt in r<=34.
"""
import math
import os
from PIL import Image, ImageDraw

# ------------------------------------------------------------------ Farben
NAVY = "#0A2E5C"
GOLD = "#E8C898"
GOLD_DARK = "#CEAC7A"
TURQ = "#C9E6FF"
WHITE = "#FFFFFF"
AREA = "#41556B"          # Flaechenfuellung unter der Kurskurve
BLACK = "#000000"         # Monochrom (der Farbwert wird vom System ersetzt)
MONO_BG = "#EADDFF"       # nur fuer die Vorschau des eingefaerbten Icons

RGB = {
    NAVY: (10, 46, 92), GOLD: (232, 200, 152), GOLD_DARK: (206, 172, 122),
    TURQ: (201, 230, 255), WHITE: (255, 255, 255), AREA: (65, 85, 107),
    BLACK: (0, 0, 0), MONO_BG: (234, 221, 255),
}
PREVIEW_DIR = "/tmp/opencode/logo"


# ------------------------------------------------------------------ Formen
def bezier(p0, p1, p2, p3, steps=72):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        pts.append((
            u**3 * p0[0] + 3 * u**2 * t * p1[0] + 3 * u * t**2 * p2[0] + t**3 * p3[0],
            u**3 * p0[1] + 3 * u**2 * t * p1[1] + 3 * u * t**2 * p2[1] + t**3 * p3[1],
        ))
    return pts


def dart(cx, cy, size, angle_deg):
    """Papierflieger, Spitze zeigt in Richtung angle_deg (0 = nach rechts)."""
    base = [(1.0, 0.0), (-0.55, -0.72), (-0.18, 0.0), (-0.55, 0.72)]
    a = math.radians(angle_deg)
    ca, sa = math.cos(a), math.sin(a)
    return [(cx + (x * ca - y * sa) * size, cy + (x * sa + y * ca) * size)
            for (x, y) in base]


def P(points, color):                 # gefuellte Flaeche
    return ("polyfill", points, color)


def L(a, b, w, color):                # Linie
    return ("line", [a, b], w, color)


def C(points, w, color):              # Kurve / Polylinie
    return ("curve", points, w, color)


def E(cx, cy, rx, ry, w, color):      # Ellipse als Strich
    return ("ellipse", (cx, cy, rx, ry), w, color)


def Dot(cx, cy, r, color):            # gefuellter Punkt
    return ("dot", (cx, cy, r), color)


def scale_shapes(shapes, s, ox=54, oy=54):
    """Alles um (ox,oy) skalieren - Punkte und Strichstaerken."""
    out = []
    for sh in shapes:
        kind = sh[0]
        if kind == "polyfill":
            out.append(("polyfill", [(ox + (x - ox) * s, oy + (y - oy) * s)
                                     for x, y in sh[1]], sh[2]))
        elif kind == "line":
            out.append(("line", [(ox + (x - ox) * s, oy + (y - oy) * s)
                                 for x, y in sh[1]], sh[2] * s, sh[3]))
        elif kind == "curve":
            out.append(("curve", [(ox + (x - ox) * s, oy + (y - oy) * s)
                                  for x, y in sh[1]], sh[2] * s, sh[3]))
        elif kind == "ellipse":
            cx, cy, rx, ry = sh[1]
            out.append(("ellipse", (ox + (cx - ox) * s, oy + (cy - oy) * s,
                                    rx * s, ry * s), sh[2], sh[3]))
        elif kind == "dot":
            cx, cy, r = sh[1]
            out.append(("dot", (ox + (cx - ox) * s, oy + (cy - oy) * s, r * s), sh[2]))
    return out


def clip_polygon(poly, clip):
    """Sutherland-Hodgman: [poly] auf die konvexe Flaeche [clip] beschneiden."""
    def inside(p, a, b):
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0

    def intersect(c, d, a, b):
        den = (d[0] - c[0]) * (b[1] - a[1]) - (d[1] - c[1]) * (b[0] - a[0])
        if den == 0:
            return c
        t = ((a[0] - c[0]) * (b[1] - a[1]) - (a[1] - c[1]) * (b[0] - a[0])) / den
        return (c[0] + t * (d[0] - c[0]), c[1] + t * (d[1] - c[1]))

    out = poly
    for i in range(len(clip)):
        a, b = clip[i], clip[(i + 1) % len(clip)]
        if not out:
            break
        inp, out = out, []
        for j in range(len(inp)):
            c, d = inp[j], inp[(j + 1) % len(inp)]
            ic, id_ = inside(c, a, b), inside(d, a, b)
            if ic:
                out.append(c)
            if ic != id_:
                out.append(intersect(c, d, a, b))
    return out


def side_plane(cx, cy, length, ang_deg):
    """Flugzeug von der Seite, Nase zeigt in Richtung ang_deg (0 = rechts).
    Liefert zwei gefuellte Polygone: Rumpf samt Leitwerk und Fluegel."""
    a = math.radians(ang_deg)
    ca, sa = math.cos(a), math.sin(a)

    def w(p):
        x, y = p[0] * length, p[1] * length
        return (cx + x * ca - y * sa, cy + x * sa + y * ca)

    fuselage = [
        (0.50, -0.01), (0.44, -0.075), (0.10, -0.10), (-0.14, -0.105),
        (-0.30, -0.34), (-0.40, -0.34), (-0.44, -0.10), (-0.55, -0.02),
        (-0.44, 0.05), (-0.30, 0.075), (0.20, 0.10), (0.40, 0.07), (0.47, 0.03),
    ]
    wing = [(0.14, 0.06), (-0.10, 0.30), (-0.20, 0.30), (-0.04, 0.08)]
    return [w(p) for p in fuselage], [w(p) for p in wing]


# ------------------------------------------------------------------ Designs
CURVE7 = bezier((34, 78), (48, 80), (52, 50), (66, 42))

# Buch um soviel nach oben (Mitte) verschieben; Tal unten liegt danach hier:
BOOK_SHIFT = -16
VALLEY = 67 + BOOK_SHIFT                        # tiefster Punkt der Buchkante
# Kante des offenen Buches: senkrecht zum Buchstehen nach aussen ansteigend
VALLEY_LINE = [(-20, -20), (128, -20),
               (128, VALLEY - (128 - 56) / 3), (56, VALLEY), (52, VALLEY),
               (-20, VALLEY - (52 + 20) / 3)]
# Gesamte Szene etwas vergroessert, damit das Icon die Maske gut fuellt.
SCENE = 1.2


def _logbuch(color_book_l, color_book_r, color_line, color_plane):
    """Buch in der Mitte, Flugzeug steigt steil aus den Seiten heraus -
    Geometrie im Entwurfsraum."""
    top, bot = 60 + BOOK_SHIFT, 84 + BOOK_SHIFT
    mid_t, mid_b = 67 + BOOK_SHIFT, 84 + BOOK_SHIFT
    # Steiler Startwinkel (-33 Grad): das Heck versinkt nur noch im
    # Seitenrand, Nase und Rumpf fliegen frei ueber dem Buch.
    fuselage, wing = side_plane(58.0, 43.4, 34.0, -33)
    shapes = [
        # Flugzeug zuerst: die Buchkante schneidet den Heckteil ab.
        P(clip_polygon(fuselage, VALLEY_LINE), color_plane),
        P(clip_polygon(wing, VALLEY_LINE), color_plane),
        P([(31, top), (52, mid_t), (52, bot), (31, 78 + BOOK_SHIFT)], color_book_l),
        P([(77, top), (56, mid_t), (56, bot), (77, 78 + BOOK_SHIFT)], color_book_r),
    ]
    if color_line is not None:                  # nur Farbvariante: Falz/Linien
        shapes += [
            L((54, mid_t), (54, bot), 1.4, color_line),
            L((35, top + 8), (49, top + 13), 0.9, color_line),
            L((35, top + 13), (49, top + 18), 0.9, color_line),
            L((59, top + 13), (73, top + 8), 0.9, color_line),
            L((59, top + 18), (73, top + 13), 0.9, color_line),
        ]
    return scale_shapes(shapes, SCENE)


LOGBUCH = _logbuch(GOLD, GOLD_DARK, NAVY, WHITE)

ORBIT = [
    E(54, 54, 19.0, 19.0, 2.0, TURQ),                        # Globus
    E(54, 54, 8.55, 19.0, 2.0, TURQ),                        # Laengengrade
    E(54, 54, 15.58, 19.0, 2.0, TURQ),
    L((35, 54), (73, 54), 2.0, TURQ),                        # Aequator
    E(54, 54, 27.0, 27.0, 0.7, GOLD),                        # Orbit
    P(dart(36.65, 33.32, 7.0, -40), WHITE),                  # Flugzeug
]

STEIGFLUG = [
    L((34, 76), (46, 76), 1.6, GOLD),                        # Rasterlinien
    L((34, 70), (46, 70), 1.6, GOLD),
    L((34, 64), (46, 64), 1.6, GOLD),
    P(CURVE7 + [(66, 80), (34, 80)], AREA),                  # Flaeche
    C(CURVE7, 2.4, WHITE),                                   # Steigflugkurve
    P(dart(69, 37, 7.0, -42), WHITE),                        # Flugzeug
]

LOGBUCH_MONO = _logbuch(BLACK, BLACK, None, BLACK)
ORBIT_MONO = [
    E(54, 54, 19.0, 19.0, 2.0, BLACK),
    E(54, 54, 8.55, 19.0, 2.0, BLACK),
    E(54, 54, 15.58, 19.0, 2.0, BLACK),
    L((35, 54), (73, 54), 2.0, BLACK),
    E(54, 54, 27.0, 27.0, 0.7, BLACK),
    P(dart(36.65, 33.32, 7.0, -40), BLACK),
]
STEIGFLUG_MONO = [
    L((34, 76), (46, 76), 1.6, BLACK),
    L((34, 70), (46, 70), 1.6, BLACK),
    L((34, 64), (46, 64), 1.6, BLACK),
    C(CURVE7, 2.4, BLACK),
    P(dart(69, 37, 7.0, -42), BLACK),
]

DESIGNS = {
    "logbuch": (LOGBUCH, LOGBUCH_MONO),
    "orbit": (ORBIT, ORBIT_MONO),
    "steigflug": (STEIGFLUG, STEIGFLUG_MONO),
}

# Welcher Entwurf ist der aktive (ueber AndroidManifest -> @mipmap/ic_launcher)?
DEFAULT = "logbuch"


# ------------------------------------------------------------------ PNG
def render_png(shapes, path, bg=NAVY):
    S = 8
    if bg is None:                       # transparenter Grund (rundes Icon)
        img = Image.new("RGBA", (108 * S, 108 * S), (0, 0, 0, 0))
    else:
        img = Image.new("RGB", (108 * S, 108 * S), RGB[bg])
    d = ImageDraw.Draw(img)

    def sp(pts):
        return [(x * S, y * S) for (x, y) in pts]

    for sh in shapes:
        kind = sh[0]
        if kind == "polyfill":
            pts, col = sh[1], sh[2]
            d.polygon(sp(pts), fill=RGB[col])
        elif kind == "line":
            (a, b), w, col = sh[1], sh[2], sh[3]
            d.line(sp([a, b]), fill=RGB[col], width=max(1, int(w * S)))
        elif kind == "curve":
            pts, w, col = sh[1], sh[2], sh[3]
            d.line(sp(pts), fill=RGB[col], width=max(1, int(w * S)), joint="curve")
        elif kind == "ellipse":
            (cx, cy, rx, ry), w, col = sh[1], sh[2], sh[3]
            d.ellipse([(cx - rx) * S, (cy - ry) * S, (cx + rx) * S, (cy + ry) * S],
                      outline=RGB[col], width=max(1, int(w * S)))
        elif kind == "dot":
            (cx, cy, r), col = sh[1], sh[2]
            d.ellipse([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S],
                      fill=RGB[col])
    img = img.resize((108, 108), Image.LANCZOS)
    img.save(path)


# ------------------------------------------------------------------ Vector XML
def f(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def path_poly(pts):
    return "M " + " L ".join(f"{f(x)},{f(y)}" for (x, y) in pts) + " Z"


def path_line(pts):
    return "M " + " L ".join(f"{f(x)},{f(y)}" for (x, y) in pts)


def path_ellipse(cx, cy, rx, ry):
    return (f"M {f(cx - rx)},{f(cy)} a {f(rx)},{f(ry)} 0 1 0 {f(2 * rx)},0 "
            f"a {f(rx)},{f(ry)} 0 1 0 {f(-2 * rx)},0 Z")


def to_vector(shapes, title):
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<!-- {title} - erzeugt von tools/logo_gen.py. Nicht von Hand aendern. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
    ]
    for sh in shapes:
        kind = sh[0]
        if kind == "polyfill":
            pts, col = sh[1], sh[2]
            out.append(f'    <path\n        android:fillColor="{col}"\n'
                       f'        android:pathData="{path_poly(pts)}" />')
        elif kind == "line":
            (a, b), w, col = sh[1], sh[2], sh[3]
            out.append(f'    <path\n        android:strokeColor="{col}"\n'
                       f'        android:strokeWidth="{f(w)}"\n'
                       f'        android:strokeLineCap="round"\n'
                       f'        android:strokeLineJoin="round"\n'
                       f'        android:pathData="{path_line([a, b])}" />')
        elif kind == "curve":
            pts, w, col = sh[1], sh[2], sh[3]
            out.append(f'    <path\n        android:strokeColor="{col}"\n'
                       f'        android:strokeWidth="{f(w)}"\n'
                       f'        android:strokeLineCap="round"\n'
                       f'        android:strokeLineJoin="round"\n'
                       f'        android:pathData="{path_line(pts)}" />')
        elif kind == "ellipse":
            (cx, cy, rx, ry), w, col = sh[1], sh[2], sh[3]
            out.append(f'    <path\n        android:strokeColor="{col}"\n'
                       f'        android:strokeWidth="{f(w)}"\n'
                       f'        android:pathData="{path_ellipse(cx, cy, rx, ry)}" />')
        elif kind == "dot":
            (cx, cy, r), col = sh[1], sh[2]
            out.append(f'    <path\n        android:fillColor="{col}"\n'
                       f'        android:pathData="{path_ellipse(cx, cy, r, r)}" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"


ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground_{name}" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome_{name}" />
</adaptive-icon>
"""


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app/src/main/res")
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    for name, (fg, mono) in DESIGNS.items():
        render_png(fg, f"{PREVIEW_DIR}/{name}_108.png")
        render_png(mono, f"{PREVIEW_DIR}/{name}_mono.png", bg=MONO_BG)
        with open(f"{res}/drawable/ic_launcher_foreground_{name}.xml", "w") as fh:
            fh.write(to_vector(fg, f"Launcher-Vordergrund {name}"))
        with open(f"{res}/drawable/ic_launcher_monochrome_{name}.xml", "w") as fh:
            fh.write(to_vector(mono, f"Launcher-Monochrom {name}"))
        with open(f"{res}/mipmap-anydpi/ic_launcher_{name}.xml", "w") as fh:
            fh.write(ADAPTIVE.format(name=name))
    # Aktives Icon: die beiden Dateien, auf die das Manifest zeigt.
    for active in ("ic_launcher", "ic_launcher_round"):
        with open(f"{res}/mipmap-anydpi/{active}.xml", "w") as fh:
            fh.write(ADAPTIVE.format(name=DEFAULT))
    # Rundes Icon fuer die Einstellungen (ueber der Version). Der sichtbare
    # Kreis des adaptiven Icons (r=36) wird auf den Vollkreis (r=54) gezogen,
    # damit es dort genau so aussieht wie auf dem Startbildschirm.
    active_fg, _ = DESIGNS[DEFAULT]
    round_shapes = [Dot(54, 54, 54, NAVY)] + scale_shapes(active_fg, 1.5)
    with open(f"{res}/drawable/ic_logo_round.xml", "w") as fh:
        fh.write(to_vector(round_shapes, "App-Logo rund"))
    render_png(round_shapes, f"{PREVIEW_DIR}/{DEFAULT}_round.png", bg=None)
    print("Logos erzeugt in", res)


if __name__ == "__main__":
    main()
