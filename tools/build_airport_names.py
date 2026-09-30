#!/usr/bin/env python3
"""Build the offline airport-name asset for the Logbook app.

Reads the cached OurAirports airport list and produces a compact JSON file
that adds a display name and the urban locality (Stadt) to every IATA code,
so detail pages can show "JFK - John F Kennedy International Airport -
New York" instead of just the three-letter code.

Data source: OurAirports (public domain)
https://ourairports.com/data/
GitHub mirror used by the script:
https://davidmegginson.github.io/ourairports-data/airports.csv

The app already owns coordinates (`airports.json`) and the country ISO
(`airports_countries.json`) per IATA code; this asset only fills in the two
missing human-readable fields. Airport and city names are proper nouns and
stay as-is (the original English form), no translation is attempted.

Usage:
    python3 tools/build_airport_names.py
    python3 tools/build_airport_names.py --airports tmp_ne/airports.csv \
        --out app/src/main/assets/airports/airport_names.json

JSON format (one object per IATA code, sorted by key):
    {
        "JFK": {
            "n": "John F Kennedy International Airport",
            "c": "New York"
        },
        ...
    }
"""

import argparse
import csv
import json
import os
import sys

# Nur echte Flughäfen mit Flugplan-Typ und IATA-Code. Heliports,
# Wasserflugzeugspäsenbahnen und geschlossene Anlagen tauchen im Logbuch
# sowieso nicht auf. Reicht ein Code mehrfach vor (z. B. ein Heliport am
# selben Platz), gewinnt der höherwertige Typ.
KEEP_TYPES = ("large_airport", "medium_airport", "small_airport")
TYPE_RANK = {"large_airport": 0, "medium_airport": 1, "small_airport": 2}

IATA_COL = 13
NAME_COL = 3
CITY_COL = 10
TYPE_COL = 2
SCHEDULED_COL = 11


def main():
    parser = argparse.ArgumentParser()
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument("--airports", default=os.path.join(here, "tmp_ne", "airports.csv"))
    parser.add_argument(
        "--out",
        default=os.path.join(here, "app", "src", "main", "assets", "airports", "airport_names.json"),
    )
    args = parser.parse_args()

    best = {}
    with open(args.airports, encoding="utf-8", newline="") as handle:
        for row in csv.reader(handle):
            if len(row) <= IATA_COL:
                continue
            iata = row[IATA_COL].strip().upper()
            if not iata or not all(c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ" for c in iata):
                continue
            airport_type = row[TYPE_COL] if len(row) > TYPE_COL else ""
            if airport_type not in KEEP_TYPES:
                continue
            name = row[NAME_COL].strip() if len(row) > NAME_COL else ""
            city = row[CITY_COL].strip() if len(row) > CITY_COL else ""
            if not name and not city:
                continue
            rank = TYPE_RANK[airport_type]
            previous = best.get(iata)
            if previous is not None:
                new_scheduled = len(row) > SCHEDULED_COL and row[SCHEDULED_COL].strip() == "yes"
                better = rank < previous["rank"] or (
                    rank == previous["rank"]
                    and new_scheduled
                    and not previous["scheduled"]
                )
                if not better:
                    continue
            best[iata] = {
                "name": name,
                "city": city,
                "rank": rank,
                "scheduled": row[SCHEDULED_COL].strip() == "yes" if len(row) > SCHEDULED_COL else False,
            }

    payload = {
        iata: {"n": info["name"], "c": info["city"]}
        for iata, info in sorted(best.items())
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))

    with_empty = sum(1 for info in best.values() if not info["name"] or not info["city"])
    print(
        "%s: %d Flughäfen, %d ohne Name oder Stadt, %.2f MB"
        % (
            os.path.relpath(args.out, os.getcwd()),
            len(payload),
            with_empty,
            os.path.getsize(args.out) / 1048576.0,
        )
    )


if __name__ == "__main__":
    sys.exit(main())