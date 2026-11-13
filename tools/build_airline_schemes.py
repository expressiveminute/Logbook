#!/usr/bin/env python3
"""Erzeugt die Airline-Farbschemas (Theme-Overlays + Kachelfarben).

Aufruf: python3 tools/build_airline_schemes.py

Geschrieben werden:
- app/src/main/res/values/airline_schemes.xml
    Markenfarben (Primär/Sekundär/Akzent), Kachelfarben (Dashboard) und die
    12 Material-3-Farbrollen fuer den Tag-Modus, plus je Airline ein
    Theme-Overlay (Theme.Logbook.AirlineXX).
- app/src/main/res/values-night/airline_schemes.xml
    Dieselben 12 Material-3-Farbrollen fuer den Nacht-Modus (dunkle
    Container-Töne). Marken- und Kachelfarben gelten in beiden Modi.

Die Dateien tragen den Hinweis "Nicht von Hand aendern" - nach jeder
Aenderung an AIRLINES oder der Paletten-Rechnung dieses Skript neu laufen
lassen.

Pro Airline gelten drei Markenfarben (Primär P, Sekundär S, Akzent A):
- Dashboard-Kacheln (alle Positionen): Hintergrund = P, Schrift/Icon = S
  (auf Kontrast gegen P korrigiert, falls S zu hell/dunkel waere).
- Zeichnungen innerhalb der Kacheln und Kachel-Detailseiten (Balken,
  Kompass, Berge, Globuslinien): Attribut tileChartColor = S, gegen die
  Seitenoberflaeche kontrastkorrigiert (Tag/Nacht getrennt generiert).
- Theme colorPrimary-Familie (Akzentverhebungen): abgeleitet von A.
- Theme colorSecondary-Familie: abgeleitet von P.
- Theme colorTertiary-Familie: entsättigte Variante von P.
"""

import colorsys
import os

# key, IATA-Code, Anzeigename, Primär, Sekundär, Akzent
AIRLINES = [
    ("lh", "LH", "Lufthansa",         "#05164D", "#FFFFFF", "#FFAD00"),
    ("lx", "LX", "Swiss",             "#CC0000", "#FFFFFF", "#B9969D"),
    ("wk", "WK", "Edelweiss",         "#001973", "#D70032", "#FFC82D"),
    ("os", "OS", "Austrian Airlines", "#D81E05", "#FFFFFF", "#000000"),
    ("ew", "EW", "Eurowings",         "#871C54", "#FFFFFF", "#00A6CF"),
    ("vl", "VL", "Lufthansa City",    "#05164D", "#FFFFFF", "#6E8BB7"),
    ("4y", "4Y", "Discover",          "#1E73AF", "#FFFFFF", "#FFCD00"),
    ("en", "EN", "Air Dolomiti",      "#0093A8", "#FFFFFF", "#002E54"),
    ("az", "AZ", "ITA Airways",       "#01683C", "#FFFFFF", "#BF1D0C"),
    ("sn", "SN", "Brussels Airlines", "#051446", "#D6E2F5", "#E5002B"),
    ("tp", "TP", "TAP Air Portugal",  "#BFD730", "#FFFFFF", "#EE1C24"),
]

BLACK = (0, 0, 0)
WHITE = (255, 255, 255)

HEADER = (
    "Nicht von Hand aendern - wird von tools/build_airline_schemes.py erzeugt.\n"
    "Nach Aenderung an den Airline-Farben das Skript neu laufen lassen."
)


def hex_to_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def rgb_to_hex(rgb):
    return "#FF%02X%02X%02X" % tuple(max(0, min(255, round(c))) for c in rgb)


def rel_luminance(rgb):
    def f(c):
        c /= 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = rgb
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)


def contrast(a, b):
    la, lb = rel_luminance(a), rel_luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def tone(rgb, lightness):
    """Hue/Sat beibehalten, Helligkeit (HLS-L) setzen - wie die M3-Tonal-Palette."""
    h, _l, s = colorsys.rgb_to_hls(*(c / 255.0 for c in rgb))
    return tuple(c * 255.0 for c in colorsys.hls_to_rgb(h, lightness, s))


def lightness_of(rgb):
    return colorsys.rgb_to_hls(*(c / 255.0 for c in rgb))[1]


def desaturate(rgb, factor):
    h, l, s = colorsys.rgb_to_hls(*(c / 255.0 for c in rgb))
    return tuple(c * 255.0 for c in colorsys.hls_to_rgb(h, l, s * factor))


def on_color(bg):
    """Schwarz oder Weiss - whichever mehr Kontrast zum Hintergrund hat."""
    return BLACK if contrast(bg, BLACK) >= contrast(bg, WHITE) else WHITE


def ensure_contrast(fg, bg, min_ratio=4.5):
    """Helligkeit von fg so weit Richtung Schwarz/Weiss verschieben, bis der
    Kontrast zum Hintergrund mindestens min_ratio erreicht (Lesbarkeit der
    Kachelschrift, falls die Sekundärfarbe zur Primärfarbe zu blass ist)."""
    if contrast(fg, bg) >= min_ratio:
        return fg
    toward = BLACK if contrast(BLACK, bg) > contrast(WHITE, bg) else WHITE
    for i in range(1, 41):
        candidate = mix(fg, toward, i / 40.0)
        if contrast(candidate, bg) >= min_ratio:
            return candidate
    return toward


# Material-3-Farbrollen, die ein Theme-Overlay setzen muss.
M3_ROLES = [
    "primary", "on_primary", "primary_container", "on_primary_container",
    "secondary", "on_secondary", "secondary_container", "on_secondary_container",
    "tertiary", "on_tertiary", "tertiary_container", "on_tertiary_container",
]


def day_roles(primary, accent):
    """Tag-Modus: Akzent A als colorPrimary (Markenfarbe), Primär P als
    colorSecondary; Container sind helle Töne (L 0.92), deren Text dunkel."""
    tertiary_base = desaturate(primary, 0.35)
    secondary = primary if contrast(primary, WHITE) >= 3.0 else tone(primary, 0.40)
    return {
        "primary": accent,
        "on_primary": on_color(accent),
        "primary_container": tone(accent, 0.92),
        "on_primary_container": tone(accent, 0.12),
        "secondary": secondary,
        "on_secondary": on_color(secondary),
        "secondary_container": tone(primary, 0.92),
        "on_secondary_container": tone(primary, 0.12),
        "tertiary": tone(tertiary_base, 0.55),
        "on_tertiary": on_color(tone(tertiary_base, 0.55)),
        "tertiary_container": tone(tertiary_base, 0.92),
        "on_tertiary_container": tone(tertiary_base, 0.12),
    }


def night_roles(primary, accent):
    """Nacht-Modus: helle Töne (L 0.80) als Flächen, dunkle Container
    (L 0.30) mit hellem Text (L 0.90). Sehr dunkle Akzente (z. B. Schwarz)
    werden aufgehellt, sonst wären gefüllte Flächen unsichtbar."""
    tertiary_base = desaturate(primary, 0.35)
    primary_night = accent if lightness_of(accent) >= 0.75 else tone(accent, 0.80)
    return {
        "primary": primary_night,
        "on_primary": on_color(primary_night),
        "primary_container": tone(accent, 0.30),
        "on_primary_container": tone(accent, 0.90),
        "secondary": tone(primary, 0.80),
        "on_secondary": tone(primary, 0.15),
        "secondary_container": tone(primary, 0.30),
        "on_secondary_container": tone(primary, 0.90),
        "tertiary": tone(tertiary_base, 0.80),
        "on_tertiary": tone(tertiary_base, 0.15),
        "tertiary_container": tone(tertiary_base, 0.30),
        "on_tertiary_container": tone(tertiary_base, 0.90),
    }


def style_name(key):
    return "Theme.Logbook.Airline" + key.upper()


def color_name(key, suffix):
    return "airline_%s_%s" % (key, suffix)


def build_values_xml():
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>",
             "    <!-- %s -->" % HEADER, ""]
    for key, code, name, p_hex, s_hex, a_hex in AIRLINES:
        primary, secondary, accent = hex_to_rgb(p_hex), hex_to_rgb(s_hex), hex_to_rgb(a_hex)
        tile_text = ensure_contrast(secondary, primary)
        # Zeichnungen in Kacheln/Detailseiten (tileChartColor): Sekundärfarbe,
        # gegen die helle Oberfläche im Tag-Modus kontrastkorrigiert.
        tile_chart = ensure_contrast(secondary, (250, 250, 250))
        lines.append("    <!-- %s (%s) -->" % (name, code))
        lines.append('    <color name="%s">%s</color>' % (color_name(key, "brand_primary"), rgb_to_hex(primary)))
        lines.append('    <color name="%s">%s</color>' % (color_name(key, "brand_secondary"), rgb_to_hex(secondary)))
        lines.append('    <color name="%s">%s</color>' % (color_name(key, "brand_accent"), rgb_to_hex(accent)))
        lines.append("    <!-- Dashboard-Kacheln: Primär als Grundfarbe, Sekundär als Schrift -->")
        lines.append('    <color name="%s">%s</color>' % (color_name(key, "tile_bg"), rgb_to_hex(primary)))
        lines.append('    <color name="%s">%s</color>' % (color_name(key, "tile_text"), rgb_to_hex(tile_text)))
        dim = tuple(max(0, min(255, round(c))) for c in tile_text)
        lines.append('    <color name="%s">#B3%02X%02X%02X</color>' % ((color_name(key, "tile_text_dim"),) + dim))
        lines.append('    <color name="%s">%s</color>' % (color_name(key, "tile_chart"), rgb_to_hex(tile_chart)))
        roles = day_roles(primary, accent)
        for role in M3_ROLES:
            lines.append('    <color name="%s">%s</color>' % (color_name(key, role), rgb_to_hex(roles[role])))
        lines.append("")
    for key, code, name, p_hex, s_hex, a_hex in AIRLINES:
        lines.append('    <style name="%s" parent="Theme.Logbook">' % style_name(key))
        for role in M3_ROLES:
            attr = "color" + role.title().replace("_", "")
            lines.append('        <item name="%s">@color/%s</item>' % (attr, color_name(key, role)))
        # Zeichnungen innerhalb von Kacheln (Balken, Kompass, Globuslinien)
        # folgen nicht dem Akzent, sondern der Sekundärfarbe der Airline.
        lines.append('        <item name="tileChartColor">@color/%s</item>' % color_name(key, "tile_chart"))
        lines.append('        <item name="tileContentColor">@color/%s</item>' % color_name(key, "tile_text"))
        lines.append('        <item name="tileContentSecondaryColor">@color/%s</item>' % color_name(key, "tile_text_dim"))
        lines.append("    </style>")
        lines.append("")
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def build_night_xml():
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>",
             "    <!-- %s -->" % HEADER,
             "    <!-- Nur die Material-3-Rollen und tileChartColor weichen im",
             "         Nacht-Modus ab; Marken- und Kachelfarben gelten in beiden Modi. -->", ""]
    for key, code, name, p_hex, s_hex, a_hex in AIRLINES:
        primary, secondary, accent = hex_to_rgb(p_hex), hex_to_rgb(s_hex), hex_to_rgb(a_hex)
        roles = night_roles(primary, accent)
        # tileChartColor im Nacht-Modus: Sekundär gegen dunkle Oberfläche.
        tile_chart = ensure_contrast(secondary, (28, 27, 31))
        lines.append("    <!-- %s (%s) -->" % (name, code))
        for role in M3_ROLES:
            lines.append('    <color name="%s">%s</color>' % (color_name(key, role), rgb_to_hex(roles[role])))
        lines.append('    <color name="%s">%s</color>' % (color_name(key, "tile_chart"), rgb_to_hex(tile_chart)))
        lines.append("")
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def main():
    root = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
    values = os.path.normpath(os.path.join(root, "values", "airline_schemes.xml"))
    night = os.path.normpath(os.path.join(root, "values-night", "airline_schemes.xml"))
    with open(values, "w", encoding="utf-8") as f:
        f.write(build_values_xml())
    with open(night, "w", encoding="utf-8") as f:
        f.write(build_night_xml())
    print("geschrieben: %s" % values)
    print("geschrieben: %s" % night)


if __name__ == "__main__":
    main()
