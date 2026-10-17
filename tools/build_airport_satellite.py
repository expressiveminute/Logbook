#!/usr/bin/env python3
"""Erzeugt die mitgelieferten Satellitenbilder der Top-100-Flughäfen.

Die App selbst hat keine Internetanbindung; die Bilder werden deshalb einmalig
hier erzeugt und als WebP samt Randkoordinaten unter
`app/src/main/assets/airport_satellite/` abgelegt. Aufruf z. B.:

    python3 tools/build_airport_satellite.py                # alle 100
    python3 tools/build_airport_satellite.py --only MUC,FRA  # nur einzelne
    python3 tools/build_airport_satellite.py --force        # alles neu laden

Quelle sind die Sentinel-2-Cloudless-Kacheln von EOX IT Services GmbH. Es
werden bewusst nur die **2016er** und **2017er** Ausgaben verwendet: Nur diese
stehen unter CC BY 4.0 (frei, nur Namensnennung). Die Jahre ab 2018 stehen
unter CC BY-NC-SA 4.0 (nicht-kommerziell + Weitergabe unter gleicher Lizenz)
und kommen daher nicht in Frage.

Keine der beiden Ausgaben deckt alle Ziele ab: Die 2017er-Kacheln sind an rund
45 der 100 Flughäfen weiß (u. a. weite Teile Asiens, des inneren Nordamerikas
und Afrikas), die 2016er-Ausgabe füllt diese Lücken jedoch vollständig. Das
Skript nimmt daher die 2017er-Ausgabe und weicht nur dort auf 2016 aus, wo das
Ergebnis einfarbig weiß bleibt. Die Namensnennung zeigt die App als
Quellennachweis unter dem Bild (`satellite_credit_cloudless`).

Die Top-100-Liste stammt aus Wikipedia, „List of busiest airports by passenger
traffic“ (Vereinigung der ACI-Ranglisten 2019 und 2024, ergänzt um weitere
Großflughäfen). Die Koordinaten kommen aus dem mitgelieferten OurAirports-
Bestand `app/src/main/assets/airports/airports.json`; sehr neue Flughäfen, die
dort noch fehlen, stehen in FALLBACK_COORDS.
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

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AIRPORTS_JSON = os.path.join(ROOT, "app/src/main/assets/airports/airports.json")
OUT_DIR = os.path.join(ROOT, "app/src/main/assets/airport_satellite")

# Top 100 nach Passagierzahl (Wikipedia/ACI 2019 + 2024, plus weitere Hubs).
TOP_100 = [
    "ATL", "PEK", "LAX", "DXB", "HND", "ORD", "LHR", "PVG", "CDG", "DFW",
    "CAN", "AMS", "HKG", "ICN", "FRA", "DEN", "DEL", "SIN", "BKK", "JFK",
    "KUL", "MAD", "SFO", "CTU", "CGK", "SZX", "BCN", "IST", "SEA", "LAS",
    "MCO", "YYZ", "MEX", "CLT", "SVO", "TPE", "KMG", "MUC", "MNL", "XIY",
    "BOM", "LGW", "EWR", "PHX", "MIA", "SHA", "IAH", "CKG", "SYD", "NRT",
    "TFU", "DOH", "PKX", "JED", "FCO", "HGH", "BOG", "ZRH", "VIE", "DUB",
    "CPH", "OSL", "ARN", "HEL", "LIS", "ATH", "PRG", "WAW", "BRU", "PMI",
    "AGP", "AYT", "SAW", "TLV", "DME", "OTP", "BER", "DUS", "MXP", "MAN",
    "STN", "JNB", "CPT", "CAI", "CMN", "ADD", "NBO", "BLR", "MAA", "HYD",
    "DMK", "HKT", "DPS", "SGN", "HAN", "NKG", "WUH", "MSP", "DTW", "BOS",
]

# Flughäfen, die im OurAirports-Bestand der App noch fehlen (sehr neu).
FALLBACK_COORDS = {
    "TFU": (30.3125, 104.4417),  # Chengdu Tianfu, eröffnet 2021
}

# EOX-Sentinel-2-Cloudless, TileMatrixSet "GoogleMapsCompatible" (Web Mercator,
# standard XYZ-Reihenfolge Zeile/Spalte). Beide Ausgaben stehen unter CC BY 4.0
# und sind die jüngsten freien (ab 2018 gilt CC BY-NC-SA 4.0). Die 2017er-Ausgabe
# ist die bessere und wird zuerst versucht; wo sie weiß bleibt, springt die
# 2016er-Ausgabe ein (siehe Modul-Docstring).
LAYERS = ("s2cloudless-2017_3857", "s2cloudless_3857")
WMTS_URL = (
    "https://tiles.maps.eox.at/wmts/1.0.0/"
    "{layer}/default/g/{z}/{y}/{x}.jpg"
)
USER_AGENT = "Logbook-airport-satellite/1.0 (offline asset build)"

TILE_SIZE = 256
HALF_WORLD = 20037508.342789244  # halber Erdumfang in Web-Mercator-Metern
EARTH_RADIUS_M = 6378137.0

# Zoomstufe: so gewählt, dass der Ausschnitt in jedem Fall mindestens TARGET
# Pixel breit ist (am Äquator 838 px bei 8 km), danach wird auf 640 px
# herunterskaliert — das ergibt ein scharfes Bild ohne Hochrechnen.
DEFAULT_ZOOM = 14


def lonlat_to_merc(lon, lat):
    """WGS84-Grad in Web-Mercator-Meter (EPSG:3857)."""
    x = lon * HALF_WORLD / 180.0
    y = math.log(math.tan((90.0 + lat) * math.pi / 360.0)) / (math.pi / 180.0)
    y = y * HALF_WORLD / 180.0
    return x, y


def merc_to_lonlat(x, y):
    """Web-Mercator-Meter in WGS84-Grad (umgekehrt zu [lonlat_to_merc])."""
    lon = x / HALF_WORLD * 180.0
    lat = y / HALF_WORLD * 180.0
    lat = 180.0 / math.pi * (2.0 * math.atan(math.exp(lat * math.pi / 180.0)) - math.pi / 2.0)
    return lon, lat


def load_coords():
    with open(AIRPORTS_JSON, encoding="utf-8") as fh:
        data = json.load(fh)
    coords = {code: (lat, lon) for code, (lat, lon) in data.items()}
    coords.update(FALLBACK_COORDS)
    return coords


def fetch_tile(layer, z, x, y, delay, retries=4):
    """Lädt eine Kachel; 404 bedeutet „Kachel fehlt“ und gibt None zurück."""
    url = WMTS_URL.format(layer=layer, z=z, x=x, y=y)
    for attempt in range(retries):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
            time.sleep(delay)
            if not raw:
                return None
            return Image.open(io.BytesIO(raw)).convert("RGB")
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            wait = delay + 2 ** attempt
            print(f"      HTTP {error.code} für {z}/{y}/{x}, warte {wait:.1f}s", flush=True)
            time.sleep(wait)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            wait = delay + 2 ** attempt
            print(f"      Fehler {error} für {z}/{y}/{x}, warte {wait:.1f}s", flush=True)
            time.sleep(wait)
    print(f"      Kachel {z}/{y}/{x} endgültig fehlgeschlagen", flush=True)
    return None


WHITE_LEVEL = 250
# Eine Ausgabe gilt als "sauber", wenn praktisch kein Lückenweiß vorkommt.
CLEAN_WHITE_FRACTION = 0.02
# Ab diesem Weißanteil ist der Ausschnitt unbrauchbar (dann kein Bild).
UNUSABLE_WHITE_FRACTION = 0.5


def white_fraction(image):
    """Anteil fast weißer Bildpunkte – so erkennen wir die Lücken des Mosaiks.

    Die Lücken sind flächig weiß; einzelne Randkacheln können aber echtes Bild
    zeigen. Deshalb zählt der Anteil, nicht die Farbigkeit des ganzen Bildes.
    """
    histogram = image.convert("L").histogram()
    total = sum(histogram)
    return sum(histogram[WHITE_LEVEL:]) / total if total else 1.0


def build_image(layer, lat, lon, zoom, radius_m, pixels, delay):
    """Setzt die Kacheln zusammen und schneidet den Ausschnitt aus."""
    scale = 2 ** zoom
    span = 2.0 * HALF_WORLD / scale
    cx, cy = lonlat_to_merc(lon, lat)
    x0, x1 = cx - radius_m, cx + radius_m
    y0, y1 = cy - radius_m, cy + radius_m

    tx0 = math.floor((x0 + HALF_WORLD) / span)
    tx1 = math.floor((x1 + HALF_WORLD) / span)
    ty0 = math.floor((HALF_WORLD - y1) / span)
    ty1 = math.floor((HALF_WORLD - y0) / span)

    width = (tx1 - tx0 + 1) * TILE_SIZE
    height = (ty1 - ty0 + 1) * TILE_SIZE
    # Neutraler Hintergrund, falls eine Kachel fehlt (z. B. randlos).
    canvas = Image.new("RGB", (width, height), (58, 62, 66))

    missing = 0
    for ty in range(ty0, ty1 + 1):
        for tx in range(tx0, tx1 + 1):
            tile = fetch_tile(layer, zoom, tx, ty, delay)
            if tile is None:
                missing += 1
                continue
            canvas.paste(tile, ((tx - tx0) * TILE_SIZE, (ty - ty0) * TILE_SIZE))

    def px(mx):
        return (mx + HALF_WORLD - tx0 * span) / span * TILE_SIZE

    def py(my):
        return (HALF_WORLD - ty0 * span - my) / span * TILE_SIZE

    box = (round(px(x0)), round(py(y1)), round(px(x1)), round(py(y0)))
    crop = canvas.crop(box)
    if crop.size != (pixels, pixels):
        crop = crop.resize((pixels, pixels), Image.LANCZOS)
    min_lon, min_lat = merc_to_lonlat(x0, y0)
    max_lon, max_lat = merc_to_lonlat(x1, y1)
    total = (tx1 - tx0 + 1) * (ty1 - ty0 + 1)
    return crop, min_lat, min_lon, max_lat, max_lon, missing, total


def build_best(lat, lon, zoom, radius_m, pixels, delay):
    """Baut den Ausschnitt aus der Ausgabe mit dem wenigsten Lückenweiß.

    Keine der beiden Ausgaben ist überall vollständig: Die neuere 2017er-Ausgabe
    hat in weiten Teilen Asiens, Nordamerikas und Afrikas weiße Lücken, die
    2016er-Ausgabe ist dort geschlossen. Deshalb wird zuerst die 2017er-Ausgabe
    gebaut und nur dann auf 2016 ausgewichen, wenn sie nicht sauber ist.

    Gibt None zurück, wenn auch die bessere Ausgabe überwiegend weiß bleibt
    oder die Mehrheit der Kacheln fehlt (dann wird kein Bild abgelegt).
    """
    best = None
    best_white = None
    for layer in LAYERS:
        result = build_image(layer, lat, lon, zoom, radius_m, pixels, delay)
        image, *_rest, missing, total = result
        white = white_fraction(image)
        if missing * 2 > total:
            white = 1.0  # zu viele Kacheln fehlen: unbrauchbar
        if best is None or white < best_white:
            best = result
            best_white = white
        if white <= CLEAN_WHITE_FRACTION:
            # Schon sauber: die ältere Ausgabe muss nicht mehr geladen werden.
            break
        print(f"      {layer}: {white:.0%} Weiß, prüfe die nächste Ausgabe", flush=True)
    if best is None or best_white > UNUSABLE_WHITE_FRACTION:
        return None
    return best[:6]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", help="Nur diese IATA-Codes (Komma-getrennt)")
    parser.add_argument("--zoom", type=int, default=DEFAULT_ZOOM)
    parser.add_argument("--radius-km", type=float, default=4.0,
                        help="Halbe Kantenlänge des Ausschnitts in km")
    parser.add_argument("--pixels", type=int, default=640)
    parser.add_argument("--delay", type=float, default=0.25,
                        help="Pause zwischen zwei Kachelabrufen in Sekunden")
    parser.add_argument("--force", action="store_true",
                        help="Vorhandene Bilder überschreiben")
    args = parser.parse_args()

    coords = load_coords()
    codes = TOP_100
    if args.only:
        wanted = [c.strip().upper() for c in args.only.split(",") if c.strip()]
        codes = [c for c in TOP_100 if c in wanted]
        for c in wanted:
            if c not in TOP_100:
                codes.append(c)

    os.makedirs(OUT_DIR, exist_ok=True)
    radius_m = args.radius_km * 1000.0

    done = skipped = failed = 0
    for index, code in enumerate(codes, start=1):
        webp_path = os.path.join(OUT_DIR, f"{code}.webp")
        json_path = os.path.join(OUT_DIR, f"{code}.json")
        if not args.force and os.path.exists(webp_path) and os.path.exists(json_path):
            skipped += 1
            print(f"[{index}/{len(codes)}] {code}: vorhanden, übersprungen", flush=True)
            continue

        if code not in coords:
            failed += 1
            print(f"[{index}/{len(codes)}] {code}: keine Koordinaten, übersprungen", flush=True)
            continue
        lat, lon = coords[code]

        print(f"[{index}/{len(codes)}] {code} ({lat:.4f}, {lon:.4f}) …", flush=True)
        try:
            result = build_best(
                lat, lon, args.zoom, radius_m, args.pixels, args.delay
            )
        except Exception as error:  # noqa: BLE001
            failed += 1
            print(f"      fehlgeschlagen: {error}", flush=True)
            continue

        if result is None:
            failed += 1
            print("      keine Kachelausgabe ohne Lücke gefunden", flush=True)
            continue
        image, min_lat, min_lon, max_lat, max_lon, missing = result

        image.save(webp_path, "WEBP", quality=82, method=6)
        with open(json_path, "w", encoding="utf-8") as fh:
            json.dump({
                "min_lat": round(min_lat, 6),
                "min_lon": round(min_lon, 6),
                "max_lat": round(max_lat, 6),
                "max_lon": round(max_lon, 6),
            }, fh, ensure_ascii=False)
        size_kb = os.path.getsize(webp_path) / 1024.0
        note = f", {missing} Kachel(n) fehlen" if missing else ""
        print(f"      {image.size[0]}x{image.size[1]}, {size_kb:.1f} KB{note}", flush=True)
        done += 1

    print(f"\nFertig: {done} neu, {skipped} übersprungen, {failed} fehlgeschlagen.")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
