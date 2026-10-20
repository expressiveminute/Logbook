#!/usr/bin/env python3
"""Sync manually curated airline logos into the offline asset.

The app shows airline logos from `app/src/main/assets/airlines/liveries/`,
named after the airline's ICAO code (`<ICAO>.webp`); the `file` field of each
entry in `assets/airlines/airlines.json` points at it. Logos are NOT fetched
automatically: ICAO codes collide (Wikidata lists "BER" for Air Berlin *and*
Fly Air41 Airways, and an earlier auto-download put the air41 logo under AB),
and new airlines have no logo in older sources at all. Logos are therefore
curated by hand in `liveries_manual/` at the repo root, named by IATA code:

    liveries_manual/SN.png
    liveries_manual/AB.webp
    ...

Usage:
    python3 tools/sync_airline_logos.py

The script converts every `<IATA>.png|jpg|jpeg|webp` to webp (max. 96 px
height, alpha kept), stores it as `liveries/<ICAO>.webp` (ICAO taken from the
catalog entry) and points the entry's `file` field at it. Afterwards it
validates the whole mapping: every referenced file must exist and be named
after the entry's ICAO code. Requires Pillow.

Source of the logos currently in `liveries_manual/`: Wikimedia Commons
(rendered from the SVG, all files public domain / PD-textlogo).
"""

import argparse
import json
import os
import re
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow fehlt: python3 -m pip install --user Pillow")

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Logos werden auf diese Höhe begrenzt; kleinere bleiben unverändert.
MAX_HEIGHT = 96

MANUAL_NAME = re.compile(r"^([A-Za-z0-9]{2})\.(png|jpe?g|webp)$", re.IGNORECASE)


def load_catalog(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)["airlines"]


def dump_catalog(airlines):
    """Schreibt den Katalog im bestehenden Format: keine Einrückung, ein
    Feld pro Zeile, `"key": value`, kein Zeilenumbruch am Dateiende."""
    chunks = ['{\n"airlines": [\n']
    for index, airline in enumerate(airlines):
        chunks.append('{\n')
        chunks.append('"iata": %s,\n' % json.dumps(airline["iata"], ensure_ascii=False))
        chunks.append('"icao": %s,\n' % (
            json.dumps(airline["icao"], ensure_ascii=False)
            if airline.get("icao") is not None else "null"
        ))
        chunks.append('"name": %s,\n' % json.dumps(airline["name"], ensure_ascii=False))
        chunks.append('"file": %s\n' % (
            json.dumps(airline["file"], ensure_ascii=False)
            if airline.get("file") is not None else "null"
        ))
        chunks.append("}" + ("," if index < len(airlines) - 1 else "") + "\n")
    chunks.append("]\n}")
    return "".join(chunks)


def convert(source, target):
    """Nach webp konvertieren, Alpha erhalten, auf MAX_HEIGHT begrenzen."""
    with Image.open(source) as image:
        image = image.convert("RGBA")
        if image.height > MAX_HEIGHT:
            width = max(1, round(image.width * MAX_HEIGHT / image.height))
            image = image.resize((width, MAX_HEIGHT), Image.LANCZOS)
        image.save(target, "WEBP", quality=90, method=6)


def sync(manual_dir, airlines, liveries_dir):
    """Alle manuellen Logos installieren und die file-Felder setzen."""
    by_iata = {a["iata"].upper(): a for a in airlines}
    installed = []
    seen = {}
    for name in sorted(os.listdir(manual_dir)):
        match = MANUAL_NAME.match(name)
        if not match:
            if not name.startswith("."):
                print("  Hinweis: %s ist kein <IATA>.<Endung>-Name, wird übergangen" % name)
            continue
        iata = match.group(1).upper()
        if iata in seen:
            print("  Warnung: %s doppelt (%s) - nur %s wird verwendet"
                  % (iata, name, seen[iata]))
            continue
        seen[iata] = name
        entry = by_iata.get(iata)
        if entry is None:
            print("  Warnung: %s (%s) ist nicht im Katalog, wird übergangen" % (iata, name))
            continue
        if not entry.get("icao"):
            print("  Warnung: %s hat keinen ICAO-Code im Katalog, wird übergangen" % iata)
            continue
        source = os.path.join(manual_dir, name)
        target = os.path.join(liveries_dir, entry["icao"].upper() + ".webp")
        convert(source, target)
        entry["file"] = "liveries/%s.webp" % entry["icao"].upper()
        installed.append((iata, entry["icao"].upper(), os.path.getsize(target)))
    for iata, icao, size in installed:
        print("  %s -> liveries/%s.webp (%d B)" % (iata, icao, size))
    return installed


def validate(airlines, liveries_dir):
    """Prüft die gesamte Zuordnung; False bei Fehlern."""
    ok = True
    referenced = set()
    without_logo = 0
    for entry in airlines:
        file = entry.get("file")
        if not file:
            without_logo += 1
            continue
        referenced.add(file)
        path = os.path.join(os.path.dirname(liveries_dir), file)
        if not os.path.isfile(path):
            print("  Fehler: %s (%s) zeigt auf fehlende Datei %s"
                  % (entry["iata"], entry["name"], file))
            ok = False
            continue
        stem = os.path.splitext(os.path.basename(file))[0]
        if entry.get("icao") and stem != entry["icao"].upper():
            print("  Fehler: %s (%s) zeigt auf %s, nach ICAO müsste es %s.webp heißen"
                  % (entry["iata"], entry["name"], file, entry["icao"].upper()))
            ok = False
    orphans = sorted(
        f for f in os.listdir(liveries_dir)
        if "liveries/" + f not in referenced
    )
    if orphans:
        print("  Hinweis: %d nicht referenzierte Datei(en): %s"
              % (len(orphans), ", ".join(orphans)))
    print("Prüfung: %d Airlines, %d mit Logo, %d ohne Logo, %d verwaiste Dateien"
          % (len(airlines), len(referenced), without_logo, len(orphans)))
    return ok


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manual", default=os.path.join(HERE, "liveries_manual"))
    parser.add_argument(
        "--catalog",
        default=os.path.join(HERE, "app", "src", "main", "assets", "airlines", "airlines.json"),
    )
    parser.add_argument(
        "--liveries",
        default=os.path.join(HERE, "app", "src", "main", "assets", "airlines", "liveries"),
    )
    args = parser.parse_args()

    airlines = load_catalog(args.catalog)
    installed = []
    if os.path.isdir(args.manual):
        installed = sync(args.manual, airlines, args.liveries)
        print("Sync: %d Logo(s) installiert" % len(installed))
    else:
        print("Sync: Ordner %s fehlt, nur Prüfung" % args.manual)

    if installed:
        with open(args.catalog, "w", encoding="utf-8") as handle:
            handle.write(dump_catalog(airlines))

    print("Prüfung:")
    ok = validate(airlines, args.liveries)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
