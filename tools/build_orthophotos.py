#!/usr/bin/env python3
"""Build `orthophotos/<CODE>.webp` + `<CODE>.json` für die CO₂-Karte.

Wozu
----
Die Karte der Seite "CO₂-Ausgleich" zeigt die nötige Pflanzfläche als grünen
Kreis in echtem Maßstab. Auf der ansonsten offline gezeichneten Karte fehlt
der Umgebung jedes Bild; der Kreis lässt sich dadurch kaum einordnen. Dieses
Werkzeug lädt für die beiden Flughäfen Frankfurt (FRA) und München (MUC) je
einen quadratischen Luftbild-Ausschnitt der amtlichen Orthophotos (DOP20) und
legt ihn als kleines WebP samt Randkoordinaten unter `assets/orthophotos/` ab.
Nur diese eine Karte liest das Asset.

Quellen und Lizenzen
--------------------
* Hessen (FRA): WMS "WMS HE DOP", Layer `he_dop_rgb`.
  https://www.gds-srv.hessen.de/cgi-bin/lika-services/ogc-free-images.ows
  Datenlizenz Deutschland – Zero – Version 2.0 (dl-de/zero-2.0), keine
  Namensnennung erforderlich.
* Bayern (MUC): WMS "DOP20", Layer `by_dop20c`.
  https://geoservices.bayern.de/od/wms/dop/v1/dop20
  Creative Commons Namensnennung 4.0 (CC BY 4.0); Namensnennung erfolgt in der
  App (© Bayerische Vermessungsverwaltung).

Beide Dienste sind ohne Schlüssel nutzbar. Sie drosseln jedoch schnelle
Abfragefolgen (HTTP 404/502); das Werkzeug wartet daher zwischen den Kacheln
und wiederholt fehlgeschlagene Abrufe mit wachsendem Abstand. Der hessische
Dienst liefert außerdem oberhalb von rund 1024 Pixeln pro Anfrage einen 502 –
deshalb wird der Ausschnitt dort in Kacheln zerlegt und danach zusammengesetzt.

Ausgabe
-------
* `orthophotos/<CODE>.webp` – Luftbild, WebP (verlustbehaftet).
* `orthophotos/<CODE>.json` – `min_lat/min_lon/max_lat/max_lon` (WGS84),
  `pixels` (Kantenlänge) und `box_km`. Zur Laufzeit wird das Bild über diese
  Randkoordinaten in die (Web-Mercator-)Karte gelegt.

Usage:
    python3 tools/build_orthophotos.py
    python3 tools/build_orthophotos.py --only FRA --box-km 16 --pixels 3072
    python3 tools/build_orthophotos.py --list
"""

import argparse
import io
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request

try:
    from PIL import Image
except ImportError:  # pragma: no cover - reine Nutzerführung
    sys.exit("Pillow fehlt: python3 -m pip install pillow")

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(HERE, "app", "src", "main", "assets", "orthophotos")

# Erdradius der Web-Mercator-Projektion (EPSG:3857).
MERCATOR_R = 6378137.0

# Die beiden unterstützten Flughäfen mit Quelle. FRA liegt in Hessen, MUC in
# Bayern; jedes Bundesland deckt seinen Flughafen ab.
SERVICES = {
    "hessen": {
        "url": "https://www.gds-srv.hessen.de/cgi-bin/lika-services/ogc-free-images.ows",
        "layer": "he_dop_rgb",
        # Der Dienst bricht oberhalb von ~1024 px je Anfrage ab (502).
        "max_tile_px": 1024,
    },
    "bayern": {
        "url": "https://geoservices.bayern.de/od/wms/dop/v1/dop20",
        "layer": "by_dop20c",
        # Laut Dienstbeschreibung bis 6000x6000 px je Anfrage.
        "max_tile_px": 2048,
    },
}

AIRPORTS = {
    "FRA": {"lat": 50.0379, "lon": 8.5622, "service": "hessen"},
    "MUC": {"lat": 48.3538, "lon": 11.7861, "service": "bayern"},
}

DEFAULT_BOX_KM = 12.0
DEFAULT_PIXELS = 2048
DEFAULT_QUALITY = 80

# Abstand zwischen zwei Abrufen und Verhalten bei Fehlern (Drosselung).
PAUSE_S = 1.5
RETRIES = 6
TIMEOUT_S = 180
USER_AGENT = "Logbook/1.0 (offline Karte; +https://github.com/highfly/logbook)"


def mercator(lat, lon):
    """WGS84 -> Web-Mercator-Meter (EPSG:3857)."""
    x = math.radians(lon) * MERCATOR_R
    y = math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)) * MERCATOR_R
    return x, y


def inverse_mercator(x, y):
    """Web-Mercator-Meter (EPSG:3857) -> WGS84 (lat, lon)."""
    lat = math.degrees(2 * math.atan(math.exp(y / MERCATOR_R)) - math.pi / 2)
    lon = math.degrees(x / MERCATOR_R)
    return lat, lon


def fetch(url, layer, min_x, min_y, max_x, max_y, pixels):
    """Eine GetMap-Kachel laden; bei Drosselung geduldig erneut versuchen."""
    query = (
        "SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
        f"&LAYERS={layer}&STYLES=&SRS=EPSG:3857"
        f"&BBOX={min_x},{min_y},{max_x},{max_y}"
        f"&WIDTH={pixels}&HEIGHT={pixels}&FORMAT=image/jpeg"
    )
    request = urllib.request.Request(url + ("&" if "?" in url else "?") + query)
    request.add_header("User-Agent", USER_AGENT)
    last = None
    for attempt in range(RETRIES):
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
                body = response.read()
            if body[:3] != b"\xff\xd8\xff":
                raise RuntimeError("Antwort ist kein JPEG")
            image = Image.open(io.BytesIO(body)).convert("RGB")
            if image.size != (pixels, pixels):
                raise RuntimeError(f"unerwartete Bildgröße {image.size}")
            return image
        except Exception as error:  # noqa: BLE001 - bewusst breit (Netz/Format)
            last = error
            time.sleep(PAUSE_S * (attempt + 1) * 1.5)
    raise RuntimeError(f"Kachel fehlgeschlagen: {last}")


def build(code, config, service, box_km, pixels, quality, out_dir):
    """Luftbild eines Flughafens laden, zusammensetzen und speichern."""
    lat, lon = config["lat"], config["lon"]
    url, layer = service["url"], service["layer"]
    max_tile = service["max_tile_px"]

    # Kachelraster so wählen, dass jede Anfrage im erlaubten Bereich bleibt.
    tiles = max(1, math.ceil(pixels / max_tile))
    tile_px = pixels // tiles

    # Quadrat in Mercator-Metern: Am Boden sollen es box_km Kantenlänge sein.
    # Mercator streckt um 1/cos(lat), daher der Faktor.
    center_x, center_y = mercator(lat, lon)
    half = (box_km * 1000.0 / 2.0) / math.cos(math.radians(lat))
    min_x, min_y = center_x - half, center_y - half
    max_x, max_y = center_x + half, center_y + half

    canvas = Image.new("RGB", (tiles * tile_px, tiles * tile_px))
    for row in range(tiles):
        # WMS liefert für BBOX mit größerem y (Norden) die obere Bildzeile.
        # row 0 soll daher die nördlichste Kachel sein, row (tiles-1) die südlichste.
        y_row = tiles - 1 - row
        for col in range(tiles):
            x0 = min_x + (max_x - min_x) * col / tiles
            x1 = min_x + (max_x - min_x) * (col + 1) / tiles
            y0 = min_y + (max_y - min_y) * y_row / tiles
            y1 = min_y + (max_y - min_y) * (y_row + 1) / tiles
            tile = fetch(url, layer, x0, y0, x1, y1, tile_px)
            canvas.paste(tile, (col * tile_px, row * tile_px))
            print(f"    {code}: Kachel {row + 1}/{tiles} x {col + 1}/{tiles} geladen")
            time.sleep(PAUSE_S)

    if canvas.size != (pixels, pixels):
        canvas = canvas.resize((pixels, pixels), Image.LANCZOS)

    # Sicherung gegen die weißen "Kein-Daten"-Kacheln des hessischen Dienstes.
    colors = canvas.getcolors(maxcolors=2)
    if colors is not None and len(colors) == 1 and colors[0][1] == (255, 255, 255):
        raise RuntimeError("nur weiße Fläche geliefert (falscher Maßstab?)")

    os.makedirs(out_dir, exist_ok=True)
    image_path = os.path.join(out_dir, f"{code}.webp")
    canvas.save(image_path, "WEBP", quality=quality, method=6)

    min_lat, min_lon = inverse_mercator(min_x, min_y)
    max_lat, max_lon = inverse_mercator(max_x, max_y)
    meta = {
        "code": code,
        "pixels": pixels,
        "box_km": round(box_km, 3),
        "min_lat": round(min_lat, 7),
        "min_lon": round(min_lon, 7),
        "max_lat": round(max_lat, 7),
        "max_lon": round(max_lon, 7),
    }
    with open(os.path.join(out_dir, f"{code}.json"), "w", encoding="utf-8") as handle:
        json.dump(meta, handle, ensure_ascii=False, separators=(",", ":"))

    size_kb = os.path.getsize(image_path) / 1024.0
    m_per_px = box_km * 1000.0 / pixels
    print(
        f"  {code}: {pixels}x{pixels} px, {m_per_px:.2f} m/px, "
        f"{tiles}x{tiles} Anfragen -> {image_path} ({size_kb:.0f} KB)"
    )
    return size_kb


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--box-km", type=float, default=DEFAULT_BOX_KM,
                        help=f"Kantenlänge des Ausschnitts am Boden (Standard {DEFAULT_BOX_KM})")
    parser.add_argument("--pixels", type=int, default=DEFAULT_PIXELS,
                        help=f"Kantenlänge des Bildes (Standard {DEFAULT_PIXELS})")
    parser.add_argument("--quality", type=int, default=DEFAULT_QUALITY,
                        help=f"WebP-Qualität (Standard {DEFAULT_QUALITY})")
    parser.add_argument("--only", action="append", choices=sorted(AIRPORTS),
                        help="nur diesen Flughafen bauen (mehrfach möglich)")
    parser.add_argument("--out", default=DEFAULT_OUT, help="Zielordner der Assets")
    parser.add_argument("--list", action="store_true", help="nur die Flughäfen zeigen")
    args = parser.parse_args()

    if args.list:
        for code, config in sorted(AIRPORTS.items()):
            print(f"{code}: {config['lat']}, {config['lon']} ({config['service']})")
        return 0

    codes = args.only or sorted(AIRPORTS)
    total = 0.0
    for code in codes:
        config = AIRPORTS[code]
        service = SERVICES[config["service"]]
        print(f"{code} ({service['layer']}) ...")
        total += build(code, config, service, args.box_km, args.pixels,
                       args.quality, args.out)

    print(f"Fertig: {len(codes)} Ausschnitt(e), {total:.0f} KB gesamt.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
