#!/usr/bin/env python3
"""Build the offline airport-elevation asset for the Logbook app.

Reads the cached OurAirports airport list and produces a compact JSON file
that maps each IATA code to its elevation above mean sea level in meters
("Höhe über N.N."), so the airports tile can show the lowest and highest
airport of the recorded flights.

Data source: OurAirports (public domain)
https://ourairports.com/data/
GitHub mirror used by the script:
https://davidmegginson.github.io/ourairports-data/airports.csv

The app already owns coordinates (`airports.json`), country ISO
(`airports_countries.json`) and names (`airport_names.json`) per IATA code;
this asset only adds the elevation. Feet are converted to meters
(1 ft = 0.3048 m) and rounded to whole meters.

Usage:
    python3 tools/build_airport_elevation.py
    python3 tools/build_airport_elevation.py --airports tmp_ne/airports.csv \
        --out app/src/main/assets/airports/airport_elevation.json

JSON format (IATA code -> whole meters, sorted by key):
    {
        "JFK": 4,
        "MUC": 453,
        ...
    }
"""

import argparse
import csv
import json
import os
import sys

# Nur echte Flughäfen mit Flugplan-Typ und IATA-Code, identisch zum
# Namens-Generator. Heliports usw. tauchen im Logbuch nicht auf. Reicht ein
# Code mehrfach vor (z. B. ein Heliport am selben Platz), gewinnt der
# höherwertige Typ wie beim Namens-Asset.
ELEVATION_FT_COL = 6
KEEP_TYPES = ("large_airport", "medium_airport", "small_airport")
TYPE_RANK = {"large_airport": 0, "medium_airport": 1, "small_airport": 2}

IATA_COL = 13
TYPE_COL = 2
SCHEDULED_COL = 11

FEET_TO_METERS = 0.3048


def main():
    parser = argparse.ArgumentParser()
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument("--airports", default=os.path.join(here, "tmp_ne", "airports.csv"))
    parser.add_argument(
        "--out",
        default=os.path.join(here, "app", "src", "main", "assets", "airports", "airport_elevation.json"),
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
            elevation_text = row[ELEVATION_FT_COL].strip() if len(row) > ELEVATION_FT_COL else ""
            try:
                elevation_ft = float(elevation_text)
            except ValueError:
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
                "meters": int(round(elevation_ft * FEET_TO_METERS)),
                "rank": rank,
                "scheduled": row[SCHEDULED_COL].strip() == "yes" if len(row) > SCHEDULED_COL else False,
            }

    payload = {iata: info["meters"] for iata, info in sorted(best.items())}

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))

    print(
        "%s: %d Flughäfen, %.2f MB, z. B. LPB=%d m, MUC=%d m, JFK=%d m"
        % (
            os.path.relpath(args.out, os.getcwd()),
            len(payload),
            os.path.getsize(args.out) / 1048576.0,
            payload.get("LPB", -1),
            payload.get("MUC", -1),
            payload.get("JFK", -1),
        )
    )


if __name__ == "__main__":
    sys.exit(main())