#!/usr/bin/env python3
"""Build the offline airport-ICAO asset for the Logbook app.

Reads the cached OurAirports airport list and produces a compact JSON file
that maps each IATA code to its ICAO code (e.g. MUC -> EDDM), so the airport
detail page can show both codes side by side.

Data source: OurAirports (public domain)
https://ourairports.com/data/
GitHub mirror used by the script:
https://davidmegginson.github.io/ourairports-data/airports.csv

Fehlt der ICAO-Code (bei kleineren Anlagen steht er nicht immer), dient der
`ident` als Rückfall - das ist in der Regel das ICAO-ähnliche ICAO-/FAA-Kürzel
der Anlage und immer eindeutig. Geschlossene Flughäfen, die die App unter
"X" + Dreilettercode führt (z. B. "XMUC"), fehlen hier bewusst: Ihren ICAO
gibt OurAirports nicht mehr her, die Detailseite lässt die Zeile dann weg.

Usage:
    python3 tools/build_airport_icao.py
    python3 tools/build_airport_icao.py --airports tmp_ne/airports.csv \
        --out app/src/main/assets/airports/airport_icao.json

JSON format (IATA code -> ICAO code, sorted by key):
    {
        "JFK": "KJFK",
        "MUC": "EDDM",
        ...
    }
"""

import argparse
import csv
import json
import os
import sys

# Nur echte Flughäfen mit Flugplan-Typ und IATA-Code, identisch zu den
# übrigen Flughafen-Assets. Reicht ein Code mehrfach vor, gewinnt der
# höherwertige Typ wie beim Namens-Asset.
KEEP_TYPES = ("large_airport", "medium_airport", "small_airport")
TYPE_RANK = {"large_airport": 0, "medium_airport": 1, "small_airport": 2}

IATA_COL = 13
ICAO_COL = 12
IDENT_COL = 1
TYPE_COL = 2
SCHEDULED_COL = 11


def main():
    parser = argparse.ArgumentParser()
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument("--airports", default=os.path.join(here, "tmp_ne", "airports.csv"))
    parser.add_argument(
        "--out",
        default=os.path.join(here, "app", "src", "main", "assets", "airports", "airport_icao.json"),
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
            icao = row[ICAO_COL].strip().upper() if len(row) > ICAO_COL else ""
            if not icao:
                icao = row[IDENT_COL].strip().upper() if len(row) > IDENT_COL else ""
            if not icao:
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
                "icao": icao,
                "rank": rank,
                "scheduled": row[SCHEDULED_COL].strip() == "yes" if len(row) > SCHEDULED_COL else False,
            }

    payload = {iata: info["icao"] for iata, info in sorted(best.items())}

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))

    print(
        "%s: %d Flughäfen, %.2f MB, z. B. MUC=%s, JFK=%s, LPB=%s"
        % (
            os.path.relpath(args.out, os.getcwd()),
            len(payload),
            os.path.getsize(args.out) / 1048576.0,
            payload.get("MUC", "-"),
            payload.get("JFK", "-"),
            payload.get("LPB", "-"),
        )
    )


if __name__ == "__main__":
    sys.exit(main())