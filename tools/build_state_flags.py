#!/usr/bin/env python3
"""Lädt die Flaggen der Bundesstaaten/-länder als PNG-Assets herunter.

Ziel: `app/src/main/assets/state_flags/<ISO-3166-2>.png`, z. B. `DE-BY.png`
oder `US-CA.png` - angezeigt in der Staatenliste der Rubbelkarte (Kachel
Deutschland / Vereinigte Staaten). Die Codes kommen aus dem fertigen
`state_shapes.bin`, damit Asset und Flaggen immer zusammenpassen.

Quelle: Wikimedia Commons (Special:FilePath rasterisiert die SVG-Flaggen
serverseitig als PNG in der gewünschten Breite). Die Flaggen der deutschen
Bundesländer und der US-Bundesstaaten sind dort gemeinfrei (Public Domain).

Aufruf:
    python3 tools/build_state_flags.py            # lädt fehlende/alle Flaggen neu
    python3 tools/build_state_flags.py --check    # prüft nur, ohne herunterzuladen
"""

import argparse
import io
import os
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# ISO-3166-2-Code -> Dateiname auf Wikimedia Commons.
DE_FLAGS = {
    "DE-BW": "Flag of Baden-Württemberg.svg",
    "DE-BY": "Flag of Bavaria.svg",
    "DE-BE": "Flag of Berlin.svg",
    "DE-BB": "Flag of Brandenburg.svg",
    "DE-HB": "Flag of Bremen.svg",
    "DE-HH": "Flag of Hamburg.svg",
    "DE-HE": "Flag of Hesse.svg",
    "DE-MV": "Flag of Mecklenburg-Vorpommern.svg",
    "DE-NI": "Flag of Lower Saxony.svg",
    "DE-NW": "Flag of North Rhine-Westphalia.svg",
    "DE-RP": "Flag of Rhineland-Palatinate.svg",
    "DE-SL": "Flag of Saarland.svg",
    "DE-SN": "Flag of Saxony.svg",
    "DE-ST": "Flag of Saxony-Anhalt.svg",
    "DE-SH": "Flag of Schleswig-Holstein.svg",
    "DE-TH": "Flag of Thuringia.svg",
}

US_STATE_NAMES = {
    "AL": "Alabama", "AK": "Alaska", "AZ": "Arizona", "AR": "Arkansas",
    "CA": "California", "CO": "Colorado", "CT": "Connecticut", "DE": "Delaware",
    "FL": "Florida", "GA": "Georgia", "HI": "Hawaii", "ID": "Idaho",
    "IL": "Illinois", "IN": "Indiana", "IA": "Iowa", "KS": "Kansas",
    "KY": "Kentucky", "LA": "Louisiana", "ME": "Maine", "MD": "Maryland",
    "MA": "Massachusetts", "MI": "Michigan", "MN": "Minnesota", "MS": "Mississippi",
    "MO": "Missouri", "MT": "Montana", "NE": "Nebraska", "NV": "Nevada",
    "NH": "New Hampshire", "NJ": "New Jersey", "NM": "New Mexico", "NY": "New York",
    "NC": "North Carolina", "ND": "North Dakota", "OH": "Ohio", "OK": "Oklahoma",
    "OR": "Oregon", "PA": "Pennsylvania", "RI": "Rhode Island", "SC": "South Carolina",
    "SD": "South Dakota", "TN": "Tennessee", "TX": "Texas", "UT": "Utah",
    "VT": "Vermont", "VA": "Virginia", "WA": "Washington", "WV": "West Virginia",
    "WI": "Wisconsin", "WY": "Wyoming", "DC": "the District of Columbia",
}


API = "https://commons.wikimedia.org/w/api.php"


def commons_url(filename, width):
    """Direkte Thumbnail-URL (PNG) über die Commons-API.

    Special:FilePath?width=... lieferte für einzelne Dateien ein fast leeres
    Fehler-PNG; die API mit iiurlwidth gibt die echte thumburl zurück.
    """
    query = (
        "action=query&format=json&prop=imageinfo&iiprop=url&iiurlwidth=%d&titles="
        "File:%s" % (width, urllib.parse.quote(filename))
    )
    import json
    data = fetch_with_retry(API + "?" + query)
    info = json.loads(data)["query"]["pages"]
    for page in info.values():
        imageinfo = page.get("imageinfo") or []
        if imageinfo and imageinfo[0].get("thumburl"):
            return imageinfo[0]["thumburl"]
    raise SystemExit("kein Thumbnail für %s" % filename)


def state_codes_from_asset(path):
    """Liest die ISO-3166-2-Codes direkt aus state_shapes.bin (Version 2)."""
    with open(path, "rb") as handle:
        buf = io.BytesIO(handle.read())
    magic = struct.unpack("<i", buf.read(4))[0]
    if magic != 0x48535453:
        raise SystemExit("state_shapes.bin hat die falsche Kennung")
    version = buf.read(1)[0]
    if version != 2:
        raise SystemExit("state_shapes.bin Version %d nicht unterstützt" % version)
    buf.read(1)
    count = struct.unpack("<H", buf.read(2))[0]
    codes = []
    for _ in range(count):
        code = buf.read(5).decode("ascii").strip()
        buf.read(2)  # iso2
        buf.read(16)  # capLat/capLon/labelLat/labelLon
        buf.read(16)  # bbox
        for _ in range(4):  # nameDe, nameEn, capDe, capEn
            length = buf.read(1)[0]
            buf.read(length)
        buf.read(8)  # firstRing, ringCount
        codes.append(code)
    return codes


def flag_file_for(code):
    if code in DE_FLAGS:
        return DE_FLAGS[code]
    if code.startswith("US-"):
        suffix = code[3:]
        if suffix in US_STATE_NAMES:
            return "Flag of %s.svg" % US_STATE_NAMES[suffix]
    raise SystemExit("kein Flaggen-Dateiname für %s bekannt" % code)


def validate_png(data, url):
    """Prüft per PIL, dass die PNG dekodierbar und nicht leer/einfarbig ist.

    Reine Zweifarben-Flaggen (z. B. schwarz-gold) sind legitim wenige hundert
    Bytes gross - die Dateigroesse taugt nicht als Prüfmass, die Bilddaten schon.
    """
    from PIL import Image
    if not data.startswith(b"\x89PNG"):
        raise SystemExit("keine PNG von %s (beginnt mit %r)" % (url, data[:16]))
    img = Image.open(io.BytesIO(data)).convert("RGB")
    if img.width < 100:
        raise SystemExit("PNG von %s zu schmal (%d px)" % (url, img.width))
    # getcolors liefert None, sobald es MEHR als maxcolors Farben gibt - für die
    # Prüfung reicht eine Million, dann ist das Ergebnis immer eine Liste.
    colors = img.getcolors(maxcolors=img.width * img.height) or []
    if len(colors) < 2:
        raise SystemExit("PNG von %s ist einfarbig/leer" % url)
    return img.width


def fetch_with_retry(url, attempts=5):
    """GET mit Backoff - Commons drosselt schnelle Abfragen (HTTP 429)."""
    import time
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "logbook-tools/1.0"})
            return urllib.request.urlopen(req, timeout=60).read()
        except urllib.error.HTTPError as exc:
            if exc.code == 429 and attempt < attempts - 1:
                time.sleep(5.0 * (attempt + 1))
                continue
            raise


def download(url, dest, width=160):
    data = fetch_with_retry(url)
    validate_png(data, url)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as handle:
        handle.write(data)
    return len(data)


def main():
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset", default=os.path.join(here, "app", "src", "main", "assets", "state_shapes.bin"))
    parser.add_argument("--out-dir", default=os.path.join(here, "app", "src", "main", "assets", "state_flags"))
    parser.add_argument("--width", type=int, default=160)
    parser.add_argument("--check", action="store_true", help="nur prüfen, nichts herunterladen")
    args = parser.parse_args()

    codes = state_codes_from_asset(args.asset)
    print("%d Staaten/Codes im Asset" % len(codes))

    ok = 0
    fehlt = []
    for code in sorted(codes):
        dest = os.path.join(args.out_dir, "%s.png" % code)
        if args.check:
            if os.path.exists(dest):
                with open(dest, "rb") as handle:
                    head = handle.read(8)
                if head.startswith(b"\x89PNG"):
                    ok += 1
                else:
                    fehlt.append(code + " (keine PNG)")
            else:
                fehlt.append(code)
            continue
        if os.path.exists(dest):
            try:
                with open(dest, "rb") as handle:
                    validate_png(handle.read(), dest)
                ok += 1
                continue
            except SystemExit:
                pass  # kaputte Datei neu laden
        url = commons_url(flag_file_for(code), args.width)
        try:
            size = download(url, dest, args.width)
        except SystemExit as exc:
            print("  FEHLER %s: %s" % (code, exc))
            fehlt.append(code)
            continue
        except Exception as exc:
            print("  FEHLER %s: %s" % (code, exc))
            fehlt.append(code)
            continue
        ok += 1
        print("  %s <- %s (%d Bytes)" % (code, flag_file_for(code), size))
        time.sleep(1.5)  # Commons drosselt: zwischen den Downloads warten

    os.makedirs(args.out_dir, exist_ok=True)
    if args.check:
        print("Check: %d Flaggen vorhanden, %d fehlen" % (ok, len(fehlt)))
        for f in fehlt:
            print("  fehlt: %s" % f)
        return 1 if fehlt else 0
    print("%d Flaggen in %s, %d fehlgeschlagen" % (ok, os.path.relpath(args.out_dir, os.getcwd()), len(fehlt)))
    for f in fehlt:
        print("  fehlt: %s" % f)
    return 1 if fehlt else 0


if __name__ == "__main__":
    sys.exit(main())
