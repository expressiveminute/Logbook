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
Quellennachweis unter dem Bild (`satellite_credit_cloudless`) – die volle von EOX verlangte
Attribution inklusive s2maps.eu-Link und Copernicus-Quellenvermerk.

Der Ausschnitt ist pro Flughafen verschieden groß: Grundlage sind die Endpunkte aller offenen
Pisten aus dem OurAirports-Bestand `runways.csv` (**Public Domain**,
davidmegginson.github.io/ourairports-data). Der quadratische Kasten in Web-Mercator-Metern
umfasst die Pisten samt Flughafenkoordinate plus `--margin-km` Rand, gedeckelt auf
`--radius-km` … `--max-box-km` (Standard 8–18 km), und liegt mittig auf diesem Gebiet – so
bleibt auch bei den Riesen (DEN, IST, SVO) der ganze Flughafen im Bild, wo ein fester
8-km-Kasten um die Koordinate ihn abschneiden würde; ohne Pisten-Daten bleibt es beim
Mindestmaß um die Koordinate. Die Ausgabe ist `TARGET_M_PER_PX` m/px breit (Standard 12,5 –
mehr als die ~10 m Auflösung von Sentinel-2 bringt nichts), mindestens `--pixels`, höchstens
`--max-pixels` (Standard 640–1280 px).

Die Top-100-Liste stammt aus Wikipedia, „List of busiest airports by passenger
traffic“ (Vereinigung der ACI-Ranglisten 2019 und 2024, ergänzt um weitere
Großflughäfen). Die Koordinaten kommen aus dem mitgelieferten OurAirports-
Bestand `app/src/main/assets/airports/airports.json`; sehr neue Flughäfen, die
dort noch fehlen, stehen in FALLBACK_COORDS.
"""

import argparse
import csv
import io
import json
import math
import os
import socket
import sys
import tempfile
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
# 2016er-Ausgabe ein (siehe Modul-Docstring). Stand 2026-10 ist die 2017er-Layer
# nicht mehr in den GetCapabilities gelistet, liefert aber weiterhin Kacheln.
LAYERS = ("s2cloudless-2017_3857", "s2cloudless_3857")
WMTS_URL = (
    "https://tiles.maps.eox.at/wmts/1.0.0/"
    "{layer}/default/g/{z}/{y}/{x}.jpg"
)
USER_AGENT = "Logbook-airport-satellite/1.0 (offline asset build)"

# OurAirports veröffentlicht seine Daten als Public Domain; `runways.csv`
# liefert die Endpunkte aller Pisten, `airports.csv` den Join IATA -> ident.
OURAIRPORTS_AIRPORTS_CSV = "https://davidmegginson.github.io/ourairports-data/airports.csv"
OURAIRPORTS_RUNWAYS_CSV = "https://davidmegginson.github.io/ourairports-data/runways.csv"
OURAIRPORTS_CACHE = os.path.join(tempfile.gettempdir(), "logbook-ourairports")

# Zielauflösung der Ausgabe: 12,5 m/px (wie bisher 8 km auf 640 px). Mehr als die
# ~10 m der Sentinel-2-Kacheln (in Web-Mercator-Metern) ist ohnehin nicht drin.
TARGET_M_PER_PX = 12.5

TILE_SIZE = 256
HALF_WORLD = 20037508.342789244  # halber Erdumfang in Web-Mercator-Metern
EARTH_RADIUS_M = 6378137.0

# Zoomstufe: so gewählt, dass der Ausschnitt in jedem Fall mindestens die
# Zielpixelzahl breit ist (am Äquator 838 px bei 8 km), danach wird auf die
# Zielgröße gerechnet — das ergibt ein scharfes Bild ohne Hochrechnen.
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


def prefer_ipv4():
    """Lässt `urllib` nur IPv4-Adressen auflösen.

    Manche Netze haben ein AAAA-Routing, über das der WMTS-Server nicht
    antwortet; die Verbindung hängt dann bis zum Timeout. Der Build bevorzugt
    deshalb IPv4 – die Kachelserver haben durchweg A-Records.
    """
    original_getaddrinfo = socket.getaddrinfo

    def getaddrinfo_ipv4(host, port, family=0, type=0, proto=0, flags=0):
        return [
            address for address in original_getaddrinfo(host, port, family, type, proto, flags)
            if address[0] == socket.AF_INET
        ]

    socket.getaddrinfo = getaddrinfo_ipv4


def fetch_csv(url, cache_dir):
    """Lädt eine OurAirports-CSV in den Cache und gibt den Pfad zurück."""
    os.makedirs(cache_dir, exist_ok=True)
    path = os.path.join(cache_dir, os.path.basename(url))
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    with open(path, "wb") as fh:
        fh.write(data)
    return path


def load_runway_bounds(coords, airports_csv, runways_csv):
    """Rechteck der Pisten-Endpunkte pro IATA-Code, in Mercator-Metern.

    Die Endpunkte aller offenen Pisten (`runways.csv`, Public Domain) spannen
    pro Flughafen ein Rechteck auf – im selben Web-Mercator-Maß, in dem der
    Ausschnitt geschnitten wird. Zurück kommt {code: (minX, minY, maxX, maxY)};
    ohne Pisten-Daten fehlt der Code.
    """
    ident_by_iata = {}
    with open(airports_csv, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            iata = (row.get("iata_code") or "").strip().upper()
            if not iata:
                continue
            idents = ident_by_iata.setdefault(iata, set())
            for key in ("ident", "gps_code"):
                value = (row.get(key) or "").strip().upper()
                if value:
                    idents.add(value)

    endpoints = {}
    with open(runways_csv, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            if (row.get("closed") or "").strip() == "1":
                continue
            try:
                points = [
                    (float(row["le_latitude_deg"]), float(row["le_longitude_deg"])),
                    (float(row["he_latitude_deg"]), float(row["he_longitude_deg"])),
                ]
            except (KeyError, TypeError, ValueError):
                continue
            ident = (row.get("airport_ident") or "").strip().upper()
            endpoints.setdefault(ident, []).extend(points)

    bounds = {}
    for code, (lat, lon) in coords.items():
        idents = ident_by_iata.get(code)
        if not idents:
            continue
        xs, ys = [], []
        for ident in idents:
            for rlat, rlon in endpoints.get(ident, ()):
                rx, ry = lonlat_to_merc(rlon, rlat)
                xs.append(rx)
                ys.append(ry)
        if xs:
            bounds[code] = (min(xs), min(ys), max(xs), max(ys))
    return bounds


def box_center_and_half_side(code, lat, lon, bounds, min_side_m, max_side_m, margin_m):
    """Mittelpunkt (Mercator-m) und Halbkante des quadratischen Ausschnitts.

    Ohne Pisten-Daten bleibt es beim Mindestmaß um die Flughafenkoordinate.
    Mit Daten umfasst der Kasten alle Pisten-Endpunkte und die Koordinate samt
    Rand; gedeckelt auf das Höchstmaß, damit die Bilder nicht ausufern. Der
    Kasten liegt mittig auf diesem Gebiet, nicht auf der Koordinate – die Nadel
    in der App markiert damit die Mitte des Flughafens.
    """
    cx, cy = lonlat_to_merc(lon, lat)
    bbox = bounds.get(code)
    if bbox is None:
        return cx, cy, min_side_m / 2.0
    min_x = min(bbox[0], cx)
    max_x = max(bbox[2], cx)
    min_y = min(bbox[1], cy)
    max_y = max(bbox[3], cy)
    side = max(max_x - min_x, max_y - min_y) + 2.0 * margin_m
    side = min(max(side, min_side_m), max_side_m)
    center_x = (min_x + max_x) / 2.0
    center_y = (min_y + max_y) / 2.0
    return center_x, center_y, side / 2.0


def pixels_for(side_m, min_pixels, max_pixels):
    """Kantenlänge der Ausgabe in Pixeln zur Zielauflösung."""
    pixels = int(round(side_m / TARGET_M_PER_PX))
    return min(max(pixels, min_pixels), max_pixels)


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


def build_image(layer, cx, cy, zoom, radius_m, pixels, delay):
    """Setzt die Kacheln zusammen und schneidet den Ausschnitt um (cx, cy) aus.

    (cx, cy) ist der Kastenmittelpunkt in Web-Mercator-Metern.
    """
    scale = 2 ** zoom
    span = 2.0 * HALF_WORLD / scale
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


def build_best(cx, cy, zoom, radius_m, pixels, delay):
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
        result = build_image(layer, cx, cy, zoom, radius_m, pixels, delay)
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
                        help="Mindest-Halbkante des Ausschnitts in km (Fallback ohne Pisten-Daten)")
    parser.add_argument("--max-box-km", type=float, default=18.0,
                        help="Höchstmaß der Kantenlänge in km")
    parser.add_argument("--margin-km", type=float, default=1.5,
                        help="Rand um die Pisten-Endpunkte in km")
    parser.add_argument("--pixels", type=int, default=640,
                        help="Mindest-Kantenlänge der Ausgabe in px")
    parser.add_argument("--max-pixels", type=int, default=1280,
                        help="Höchstmaß der Ausgabe in px")
    parser.add_argument("--ourairports-dir", default=None,
                        help="Lokale OurAirports-CSVs (airports.csv, runways.csv) statt Download")
    parser.add_argument("--delay", type=float, default=0.25,
                        help="Pause zwischen zwei Kachelabrufen in Sekunden")
    parser.add_argument("--force", action="store_true",
                        help="Vorhandene Bilder überschreiben")
    args = parser.parse_args()
    prefer_ipv4()

    coords = load_coords()
    codes = TOP_100
    if args.only:
        wanted = [c.strip().upper() for c in args.only.split(",") if c.strip()]
        codes = [c for c in TOP_100 if c in wanted]
        for c in wanted:
            if c not in TOP_100:
                codes.append(c)

    os.makedirs(OUT_DIR, exist_ok=True)
    min_side_m = 2.0 * args.radius_km * 1000.0
    max_side_m = args.max_box_km * 1000.0
    margin_m = args.margin_km * 1000.0

    # Pisten-Gebiet pro Flughafen (OurAirports, Public Domain). Schlägt der
    # Download fehl, bleibt es beim Mindestkasten – die Bilder werden dann nur
    # neu zugeschnitten, nicht kleiner.
    bounds = {}
    try:
        if args.ourairports_dir:
            airports_csv = os.path.join(args.ourairports_dir, "airports.csv")
            runways_csv = os.path.join(args.ourairports_dir, "runways.csv")
        else:
            airports_csv = fetch_csv(OURAIRPORTS_AIRPORTS_CSV, OURAIRPORTS_CACHE)
            runways_csv = fetch_csv(OURAIRPORTS_RUNWAYS_CSV, OURAIRPORTS_CACHE)
        bounds = load_runway_bounds(coords, airports_csv, runways_csv)
        print(f"Pisten-Gebiet geladen: {len(bounds)} Flughäfen mit Daten", flush=True)
    except Exception as error:  # noqa: BLE001
        print(f"WARNUNG: OurAirports-Daten nicht verfügbar ({error}); "
              "falle auf den Mindestkasten zurück", flush=True)

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
        cx, cy, half_side_m = box_center_and_half_side(
            code, lat, lon, bounds, min_side_m, max_side_m, margin_m
        )
        pixels = pixels_for(half_side_m * 2.0, args.pixels, args.max_pixels)

        print(f"[{index}/{len(codes)}] {code} ({lat:.4f}, {lon:.4f}), "
              f"Kasten {half_side_m * 2.0 / 1000.0:.1f} km, {pixels} px …", flush=True)
        try:
            result = build_best(
                cx, cy, args.zoom, half_side_m, pixels, args.delay
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
