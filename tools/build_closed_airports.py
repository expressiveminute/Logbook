#!/usr/bin/env python3
"""Build `airports_closed.json`: geschlossene Flughäfen als "X" + Dreilettercode.

Idee
----
Ein offener Flughafen behält seinen dreistelligen IATA-Code. Ein geschlossener
bekommt ein "X" davor und den Code, den er damals trug (München-Riem -> "XMUC").
Ein echter IATA-Code hat immer genau drei Zeichen, deshalb kollidiert "X"+Code
nie mit einem offenen Flughafen. Die App hängt das Asset beim Laden an ihre
bestehenden Karten an, sodass Koordinaten, Land, Name, Distanz, Karte und
Import überall greifen.

Quellen für die damaligen Codes
-------------------------------
1. `keywords` der geschlossenen Zeilen im aktuellen OurAirports-Bestand
   (`tmp_ne/airports.csv`). OurAirports hat die entfernten Codes dorthin
   verschoben, z. B. Riem `"EDDM, MUC, XMUC"` oder Coventry `"RAF Baginton,
   EGBE, CVT"`. Ein echter (ehemaliger) IATA-Code ist eindeutig: kommt ein
   dreistelliger Großbuchstabe nur auf einer einzigen geschlossenen Anlage vor,
   ist er ein Kandidat. Wörter wie `OIL` oder `AFB` stehen auf mehreren und
   fallen dadurch heraus. Codes, die heute ein offener Flughafen trägt, zählen
   ebenfalls nicht.
2. Nähe zu `airports.json` (älterer Stand): Codes, die dort stehen, aber in
   `airport_names.json` fehlen, werden über die Entfernung (<3 km) ihrer
   nächsten geschlossenen Zeile zugeordnet.
3. `--old`: älterer OurAirports-Snapshot, in dem geschlossene Anlagen noch
   einen `iata_code` trugen. Join über `ident`, notfalls über die Koordinaten,
   weil sich die Idents geändert haben (Riem: `XMUC` -> `DE-0901`). Optional.
4. REASSIGNED: weitervergebene Codes, die keine der Quellen liefern kann, weil
   der Code heute einem offenen Flughafen gehört (München-Riem -> MUC, Denver
   Stapleton -> DEN, Hong Kong Kai Tak -> HKG, Athen Ellinikon -> ATH). Bewusst
   handgepflegt und mit Vorrang.

Usage:
    python3 tools/build_closed_airports.py
"""
import argparse
import csv
import json
import math
import os
import re
import sys
import unicodedata

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ident der geschlossenen Anlage -> damaliger (weitervergebener) IATA-Code.
# Nur Fälle, die keine andere Quelle liefert, weil der Code inzwischen einem
# offenen Flughafen gehört. Bewusst klein und mit Bedacht erweitern.
REASSIGNED = {
    "DE-0901": "MUC",   # München-Riem (1992) -> heute neuer Flughafen München
    "US-12484": "DEN",  # Denver Stapleton (1995) -> heute Denver Intl.
    "HK-0099": "HKG",   # Hong Kong Kai Tak (1998) -> heute Chek Lap Kok
    "GR-0098": "ATH",   # Athen Ellinikon (2001) -> heute neuer Athener Flughafen
}


def norm_city(text):
    d = unicodedata.normalize("NFKD", text)
    d = "".join(c for c in d if not unicodedata.combining(c))
    return " ".join(d.strip().lower().split())


def load_german_cities(path):
    """Deutsche Schreibweise je Ort, wie in build_airport_names.py."""
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as handle:
        cities = json.load(handle)
    index = {}
    for key, value in cities.items():
        german = value[4] if len(value) > 4 else key
        english = value[5] if len(value) > 5 else key
        index.setdefault(norm_city(key), german)
        index.setdefault(norm_city(english), german)
    return index


def fnum(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def read_rows(path):
    with open(path, encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def keyword_codes(closed_rows, open_codes):
    """Eindeutige dreistellige Großbuchstaben aus den `keywords` der closed-Zeilen."""
    def tokens(row):
        return {
            t.upper()
            for t in re.findall(r"[A-Za-z0-9-]+", row.get("keywords") or "")
            if re.fullmatch(r"[A-Za-z]{3}", t)
        }

    frequency = {}
    for row in closed_rows:
        for code in tokens(row):
            frequency[code] = frequency.get(code, 0) + 1

    result = {}
    for row in closed_rows:
        candidates = sorted(
            code for code in tokens(row)
            if code not in open_codes and frequency[code] == 1
        )
        if len(candidates) == 1:
            result[row["ident"]] = candidates[0]
        elif len(candidates) > 1:
            print(f"  Hinweis: {row['ident']} ({row['name'][:40]}) mehrdeutig: {candidates}")
    return result


def recovered_from_airports_json(closed_rows, coords, names, open_codes, max_km):
    """Codes aus airports.json, die heute keiner offenen Anlage mehr zugeordnet sind."""
    points = [(r, fnum(r.get("latitude_deg")), fnum(r.get("longitude_deg"))) for r in closed_rows]
    points = [p for p in points if p[1] is not None]
    result = {}
    for code in set(coords) - set(names):
        if code in open_codes:
            continue
        lat, lon = coords[code]
        best = None
        for row, rlat, rlon in points:
            distance = math.hypot((rlat - lat) * 111.0, (rlon - lon) * 111.0)
            if distance <= max_km and (best is None or distance < best[0]):
                best = (distance, row)
        if best:
            result[best[1]["ident"]] = code
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--current", default=os.path.join(HERE, "tmp_ne", "airports.csv"))
    parser.add_argument("--old", default=None,
                        help="älterer OurAirports-Snapshot mit iata_code auf closed-Zeilen (optional)")
    parser.add_argument("--coords", default=os.path.join(
        HERE, "app", "src", "main", "assets", "airports", "airports.json"))
    parser.add_argument("--names", default=os.path.join(
        HERE, "app", "src", "main", "assets", "airports", "airport_names.json"))
    parser.add_argument("--cities", default=os.path.join(
        HERE, "app", "src", "main", "assets", "cities.json"))
    parser.add_argument("--out", default=os.path.join(
        HERE, "app", "src", "main", "assets", "airports", "airports_closed.json"))
    parser.add_argument("--max-km", type=float, default=3.0,
                        help="Nähe, in der ein Code aus airports.json einer closed-Zeile zugeordnet wird")
    args = parser.parse_args()

    german_cities = load_german_cities(args.cities)

    current = read_rows(args.current)
    closed_rows = [r for r in current if r.get("type") == "closed"]
    open_codes = {
        (r.get("iata_code") or "").strip().upper()
        for r in current if r.get("type") != "closed"
    }

    # Quellen in aufsteigendem Vorrang zusammenführen.
    code_by_ident = keyword_codes(closed_rows, open_codes)
    keyword_count = len(code_by_ident)

    coords = json.load(open(args.coords, encoding="utf-8"))
    names = json.load(open(args.names, encoding="utf-8"))
    recovered = recovered_from_airports_json(closed_rows, coords, names, open_codes, args.max_km)
    code_by_ident.update(recovered)

    if args.old:
        # Älterer Snapshot: geschlossene Zeilen trugen damals noch iata_code.
        # Die Idents haben sich seither teils geändert (München-Riem war
        # "XMUC" und ist heute "DE-0901"), deshalb zusätzlich über die
        # Koordinaten der heute geschlossenen Zeile zuordnen.
        old_codes = {}
        closed_pts = [
            (r, fnum(r.get("latitude_deg")), fnum(r.get("longitude_deg")))
            for r in closed_rows if fnum(r.get("latitude_deg")) is not None
        ]
        for row in read_rows(args.old):
            if row.get("type") != "closed":
                continue
            code = (row.get("iata_code") or "").strip().upper()
            if len(code) != 3:
                continue
            if row.get("ident"):
                old_codes.setdefault(row["ident"], code)
            lat, lon = fnum(row.get("latitude_deg")), fnum(row.get("longitude_deg"))
            if lat is None:
                continue
            best = None
            for candidate, clat, clon in closed_pts:
                distance = math.hypot((clat - lat) * 111.0, (clon - lon) * 111.0)
                if distance <= 5.0 and (best is None or distance < best[0]):
                    best = (distance, candidate)
            if best:
                old_codes.setdefault(best[1]["ident"], code)
        code_by_ident.update(old_codes)

    code_by_ident.update(REASSIGNED)

    # Asset bauen.
    payload = {}
    for row in closed_rows:
        code = code_by_ident.get(row["ident"])
        if not code:
            continue
        lat, lon = fnum(row.get("latitude_deg")), fnum(row.get("longitude_deg"))
        if lat is None or lon is None:
            continue
        key = "X" + code
        if key in payload:
            continue
        city = (row.get("municipality") or "").strip()
        entry = {"n": (row.get("name") or "").strip(), "c": city}
        german = german_cities.get(norm_city(city)) if city else None
        if german and german != city:
            entry["d"] = german
        entry["iso"] = (row.get("iso_country") or "").strip()
        entry["lat"] = round(lat, 5)
        entry["lon"] = round(lon, 5)
        payload[key] = entry

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"), sort_keys=True)

    print(f"{args.out}: {len(payload)} geschlossene Flughäfen "
          f"({keyword_count} aus keywords, {len(recovered)} aus airports.json, "
          f"{len(REASSIGNED)} kuratiert)")
    for key in ("XMUC", "XTXL", "XTHF", "XSXF", "XDEN", "XHKG", "XATH"):
        if key in payload:
            print(f"  {key}: {payload[key]['n']} | {payload[key].get('d') or payload[key]['c']} | {payload[key]['iso']}")


if __name__ == "__main__":
    sys.exit(main())
