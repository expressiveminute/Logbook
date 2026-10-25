#!/usr/bin/env python3
"""Build the offline aircraft-facts asset for the Logbook app.

Produces `app/src/main/assets/aircraft/aircraft_facts.json`: general facts
about the best-known aircraft types that do not depend on the user's
logbook - manufacturer, production start, first delivery (date and launch
customer) and a link to the English Wikipedia article. The aircraft detail
page (Hangar -> type) shows them above the route map, in the style of the
airline detail page.

Data source: the English Wikipedia (CC BY-SA 4.0), read from the wikitext
of the aircraft infobox (`{{Infobox aircraft}}`) via the MediaWiki API:

  * `manufacturers` resp. `manufacturer` -> Hersteller. Links become their
    display text, list templates (plainlist/ubli/indented plainlist) become
    their items joined with " / ", references, comments, HTML and remaining
    templates are stripped, parenthesised year ranges and qualifiers are
    dropped ("McDonnell Douglas (1979-1997)" -> "McDonnell Douglas").
  * `produced` resp. `construction_date` -> Baubeginn: the first four-digit
    year of the production period ("ceo: 1986-2021 neo: 2016-present" ->
    1986, "1966-present" -> 1966, "2003" -> 2003).
  * `introduction` -> Erstauslieferung: a `{{Start date|Y|M|D}}` template or
    a written-out date ("18 April 1988", "February 10, 1968"); a month-year
    date becomes "YYYY-MM" ("April 1983"), a bare year is kept as "YYYY".
    The launch customer is the remaining text after the date
    ("with [[Air France]]" -> "Air France", "([[Finnair]])" -> "Finnair");
    without a customer (Concorde) the field is omitted.
  * five types get a fixed manufacturer override (`MANUFACTURER_OVERRIDE`)
    where the infobox names the factory or a sister model instead of the
    design bureau (Tu-154 -> Tupolev, Il-86/Il-96 -> Ilyushin, ...).
  * the article title itself -> the Wikipedia link
    (`https://en.wikipedia.org/wiki/<title>`).

The 50 types are the best-known families - every family of
`aircraft_seats.json` plus the classic jets and turboprops. Each type lists
article candidates; the first one that exists (redirects are resolved) and
has an aircraft infobox wins, so renamed articles keep working ("Sukhoi
SuperJet 100" now redirects to "Yakovlev SJ-100").

`aliases` maps the usual short codes to the canonical type, mirroring
`aircraft_seats.json` (whose aliases win on conflict) plus extras for the
types the seats file does not know ("707" -> B707, "Q400" is already there,
"MD82" -> MD80, "ERJ190" -> E190, "SAAB340" -> Saab340, ...). Keys are
normalised (uppercase, letters and digits only), exactly what
`AircraftFacts.resolve` looks up after normalising the logged type.

Usage:
    python3 tools/build_aircraft_facts.py
    python3 tools/build_aircraft_facts.py --seats app/src/main/assets/aircraft/aircraft_seats.json \
        --out app/src/main/assets/aircraft/aircraft_facts.json

JSON format (canonical type -> facts, only known fields, sorted by key):
    {
        "types": {
            "A320": {
                "name": "Airbus A320",
                "manufacturer": "Airbus",
                "productionStart": 1986,
                "firstDelivery": "1988-04-18",
                "launchCustomer": "Air France",
                "wiki": "https://en.wikipedia.org/wiki/Airbus_A320_family"
            },
            ...
        },
        "aliases": { "320": "A320", ... }
    }
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ENWIKI_API = "https://en.wikipedia.org/w/api.php"
USER_AGENT = "LogbookAircraftFacts/1.0 (https://github.com/expressiveminute/Logbook)"

# Artikeltitel pro Wikipedia-Abruf.
WIKI_BATCH = 50
REQUEST_TIMEOUT = 60
RETRIES = 4

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEATS_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "aircraft", "aircraft_seats.json")
OUT_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "aircraft", "aircraft_facts.json")

# Kanonischer Code, Anzeigename (steht unter dem Foto), Artikeltitel-Kandidaten
# (erster vorhandener gewinnt, Redirects werden aufgeloest).
TYPES = [
    ("A220", "Airbus A220", ["Airbus A220", "Bombardier C Series", "A220"]),
    ("A300", "Airbus A300", ["Airbus A300"]),
    ("A310", "Airbus A310", ["Airbus A310"]),
    ("A318", "Airbus A318", ["Airbus A318", "Airbus A320 family"]),
    ("A319", "Airbus A319", ["Airbus A319", "Airbus A320 family"]),
    ("A320", "Airbus A320", ["Airbus A320 family", "Airbus A320"]),
    ("A320neo", "Airbus A320neo", ["Airbus A320neo family", "Airbus A320neo"]),
    ("A321", "Airbus A321", ["Airbus A321", "Airbus A320 family"]),
    ("A321neo", "Airbus A321neo", ["Airbus A321neo", "Airbus A320neo family"]),
    ("A330", "Airbus A330", ["Airbus A330"]),
    ("A330neo", "Airbus A330neo", ["Airbus A330neo", "Airbus A330"]),
    ("A340", "Airbus A340", ["Airbus A340"]),
    ("A350", "Airbus A350", ["Airbus A350 XWB", "Airbus A350"]),
    ("A380", "Airbus A380", ["Airbus A380"]),
    ("B707", "Boeing 707", ["Boeing 707"]),
    ("B717", "Boeing 717", ["Boeing 717"]),
    ("B727", "Boeing 727", ["Boeing 727"]),
    ("B737", "Boeing 737", ["Boeing 737"]),
    ("B737MAX", "Boeing 737 MAX", ["Boeing 737 MAX", "Boeing 737"]),
    ("B747", "Boeing 747", ["Boeing 747"]),
    ("B757", "Boeing 757", ["Boeing 757"]),
    ("B767", "Boeing 767", ["Boeing 767"]),
    ("B777", "Boeing 777", ["Boeing 777"]),
    ("B787", "Boeing 787", ["Boeing 787 Dreamliner", "Boeing 787"]),
    ("MD80", "McDonnell Douglas MD-80", ["McDonnell Douglas MD-80"]),
    ("MD90", "McDonnell Douglas MD-90", ["McDonnell Douglas MD-90"]),
    ("MD11", "McDonnell Douglas MD-11", ["McDonnell Douglas MD-11"]),
    ("DC8", "Douglas DC-8", ["Douglas DC-8"]),
    ("DC9", "Douglas DC-9", ["McDonnell Douglas DC-9", "Douglas DC-9"]),
    ("DC10", "Douglas DC-10", ["McDonnell Douglas DC-10", "Douglas DC-10"]),
    ("Concorde", "Concorde", ["Concorde"]),
    ("L1011", "Lockheed L-1011 TriStar",
     ["Lockheed L-1011 TriStar", "Lockheed TriStar", "Lockheed L-1011"]),
    ("Comet", "de Havilland Comet", ["De Havilland Comet", "de Havilland DH.106 Comet"]),
    ("Caravelle", "Sud Aviation Caravelle",
     ["Sud Aviation Caravelle", "Caravelle (aircraft)"]),
    ("BAC111", "BAC One-Eleven", ["BAC One-Eleven", "BAC 1-11"]),
    ("Trident", "Hawker Siddeley Trident", ["Hawker Siddeley Trident"]),
    ("Tu154", "Tupolev Tu-154", ["Tupolev Tu-154"]),
    ("Tu204", "Tupolev Tu-204", ["Tupolev Tu-204"]),
    ("Il86", "Ilyushin Il-86", ["Ilyushin Il-86"]),
    ("Il96", "Ilyushin Il-96", ["Ilyushin Il-96"]),
    ("Yak42", "Yakovlev Yak-42", ["Yakovlev Yak-42"]),
    ("SSJ100", "Sukhoi Superjet 100",
     ["Sukhoi SuperJet 100", "Sukhoi Superjet 100", "Yakovlev SJ-100"]),
    ("CRJ", "Bombardier CRJ", ["Bombardier CRJ", "Canadair Regional Jet"]),
    ("E145", "Embraer ERJ 145",
     ["Embraer ERJ 145", "Embraer EMB 145", "Embraer ERJ family"]),
    ("E170", "Embraer E-170", ["Embraer 170", "Embraer E-Jet family"]),
    ("E190", "Embraer E-190", ["Embraer 190", "Embraer E-Jet family"]),
    ("E190E2", "Embraer E190-E2", ["Embraer E190-E2", "Embraer E-Jet E2 family"]),
    ("E195", "Embraer E-195", ["Embraer 195", "Embraer E-Jet family"]),
    ("E195E2", "Embraer E195-E2", ["Embraer E195-E2", "Embraer E-Jet E2 family"]),
    ("ATR42", "ATR 42", ["ATR 42", "ATR 42 and ATR 72"]),
    ("ATR72", "ATR 72", ["ATR 72", "ATR 42 and ATR 72"]),
    ("DH8D", "De Havilland Canada Dash 8",
     ["De Havilland Canada Dash 8", "Bombardier Dash 8", "Bombardier Q400"]),
    ("Saab340", "Saab 340", ["Saab 340"]),
    ("F70", "Fokker 70", ["Fokker 70"]),
    ("F100", "Fokker 100", ["Fokker 100"]),
    ("BAe146", "British Aerospace 146",
     ["British Aerospace 146", "BAe 146", "Avro RJ series"]),
]

# Zusaetzliche Aliase fuer die Typen, die aircraft_seats.json nicht kennt,
# plus Self-Aliase. Schluessel sind normalisiert (Grossbuchstaben, nur
# Buchstaben und Ziffern) - so legt es die App nach (AircraftFacts.resolve).
# Varianten (neo, MAX, E2) zeigen auf ihren eigenen Typ, nicht auf die
# Basisfamilie - A320neo ist ein anderes Flugzeug als A320 ceo.
EXTRA_ALIASES = {
    # Airbus (A220/A300/A310 fehlen im Sitzplatz-Datensatz)
    "A220": "A220", "CS100": "A220", "CS300": "A220", "CSERIES": "A220",
    "A300": "A300", "300": "A300",
    "A310": "A310", "310": "A310",
    # Airbus A320neo-Familie (neo-Varianten als eigene Typen)
    "A320NEO": "A320neo", "320NEO": "A320neo", "20N": "A320neo",
    "A319NEO": "A320neo", "319NEO": "A320neo", "19N": "A320neo",
    "A321NEO": "A321neo", "321NEO": "A321neo", "21N": "A321neo",
    # Airbus A330neo
    "A330NEO": "A330neo", "330NEO": "A330neo", "33X": "A330neo",
    "A330800": "A330neo", "A330900": "A330neo",
    "A330-800": "A330neo", "A330-900": "A330neo",
    "338": "A330neo", "339": "A330neo",
    # Boeing 707/727
    "B707": "B707", "707": "B707",
    "B727": "B727", "727": "B727", "721": "B727", "722": "B727",
    # Boeing 737 MAX (eigener Typ, nicht B737 NG). Die Boeing-Kurzschreibweise
    # "737-8" (MAX 8) bekommt bewusst KEINEN Alias: normalisiert "7378" ist
    # ein Präfix von "737800" (737-800 NG) und würde über den Alias-Präfix-
    # Match die NG-Varianten falsch als MAX erkennen.
    "B737MAX": "B737MAX", "737MAX": "B737MAX", "7MAX": "B737MAX",
    "7M7": "B737MAX", "7M8": "B737MAX", "7M9": "B737MAX",
    "37M": "B737MAX", "38M": "B737MAX", "39M": "B737MAX",
    # McDonnell Douglas
    "MD80": "MD80", "MD8": "MD80", "MD82": "MD80", "MD83": "MD80",
    "MD87": "MD80", "MD88": "MD80",
    "M80": "MD80", "M82": "MD80", "M83": "MD80", "M87": "MD80", "M88": "MD80",
    "MD90": "MD90", "M90": "MD90",
    "MD11": "MD11", "M11": "MD11",
    # Douglas
    "DC8": "DC8", "DC9": "DC9", "DC10": "DC10",
    # Klassiker
    "Concorde": "Concorde", "CONCORDE": "Concorde",
    "L1011": "L1011", "TRISTAR": "L1011",
    "Comet": "Comet", "COMET": "Comet", "DH106": "Comet",
    "Caravelle": "Caravelle", "CARAVELLE": "Caravelle",
    "BAC111": "BAC111", "ONEELEVEN": "BAC111", "BACONEELEVEN": "BAC111",
    "Trident": "Trident", "TRIDENT": "Trident", "HS121": "Trident",
    # Russland
    "Tu154": "Tu154", "TU154": "Tu154",
    "Tu204": "Tu204", "TU204": "Tu204",
    "Il86": "Il86", "IL86": "Il86",
    "Il96": "Il96", "IL96": "Il96",
    "Yak42": "Yak42", "YAK42": "Yak42",
    "SSJ100": "SSJ100", "SUPERJET": "SSJ100", "RRJ": "SSJ100",
    # Regionaljets
    "E145": "E145", "ERJ145": "E145", "EMB145": "E145",
    "ERJ170": "E170", "ERJ175": "E170", "EMB170": "E170",
    "ERJ190": "E190", "EMB190": "E190",
    "ERJ195": "E195", "EMB195": "E195",
    # Embraer E-Jet E2 (zweite Generation, eigene Typen)
    "E190E2": "E190E2", "190E2": "E190E2", "E195E2": "E195E2", "195E2": "E195E2",
    "E2": "E190E2",
    # Turboprops
    "Saab340": "Saab340", "SAAB340": "Saab340", "SF34": "Saab340",
    "SF340": "Saab340", "SAABSF340": "Saab340",
    # Fokker (offizielle Schreibweise "Fokker F70"/"Fokker F100")
    "F70": "F70", "FOKKER70": "F70", "FOKKERF70": "F70",
    "F100": "F100", "FOKKER100": "F100", "FOKKERF100": "F100",
    # BAe 146 / Avro RJ
    "BAe146": "BAe146", "BAE146": "BAe146", "BA146": "BAe146",
    "146": "BAe146", "AVRORJ": "BAe146",
    "RJ70": "BAe146", "RJ85": "BAe146", "RJ100": "BAe146",
}

# Hersteller, die die Infobox nicht sauber nennt (Fabrik statt Konstrukteur
# oder ein Schwestermodell im selben Feld): feste Ueberschreibung.
MANUFACTURER_OVERRIDE = {
    # Tu-154: Infobox nennt Aviakor (die Fabrik), Konstrukteur ist Tupolev.
    "Tu154": "Tupolev",
    # Tu-204: Infobox nennt Aviastar SP und listet gleich das Schwestermodell.
    "Tu204": "Tupolev",
    # Il-86: kein Hersteller-Parameter in der Infobox.
    "Il86": "Ilyushin",
    # Il-96: Hersteller-Parameter leer.
    "Il96": "Ilyushin",
    # Yak-42: kein Hersteller-Parameter in der Infobox.
    "Yak42": "Yakovlev",
    # Superjet: Infobox nennt den Mutterkonzern und einen Lizenzpartner,
    # das Konstruktionsbuero (und der Anzeigename) ist Sukhoi.
    "SSJ100": "Sukhoi",
}

MONTHS = {
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6,
    "july": 7, "august": 8, "september": 9, "october": 10, "november": 11,
    "december": 12,
}

# Monate auch in Kurzform ("Jan", "Feb", ...).
MONTHS.update({name[:3]: number for name, number in MONTHS.items()})

# Listen-Templates, deren Inhalt als Herstellerliste gelesen wird.
LIST_TEMPLATE_RE = re.compile(
    r"\{\{\s*(?:plainlist|ubli|ubl|indented plainlist|unbulleted indent list"
    r"|unbulleted list|bulleted list|flatlist)"
    r"\s*\|",
    re.IGNORECASE,
)

# Umhuellende Templates mit einem Parameter, die den ganzen Wert ausmachen
# ({{nowrap|...}} um eine Datumsangabe).
WRAPPER_TEMPLATE_RE = re.compile(
    r"^\{\{\s*(?:nowrap|nobr)\s*\|\s*(.*?)\s*\}\}$", re.DOTALL
)

REF_RE = re.compile(r"<ref\b[^>]*>.*?</ref>|<ref\b[^>]*/>", re.IGNORECASE | re.DOTALL)
COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
TAG_RE = re.compile(r"<[^>]+>")
LINK_RE = re.compile(r"\[\[([^\[\]]*)\]\]")
BR_RE = re.compile(r"<br\s*/?>", re.IGNORECASE)
PARENS_RE = re.compile(r"\([^()]*\)")
TRAILING_PARENS_RE = re.compile(r"(?:\s*\([^()]*\))+\s*$")
YEAR_RE = re.compile(r"\b((?:19|20)\d{2})\b")

START_DATE_TEMPLATE_RE = re.compile(
    r"\{\{\s*[Ss]tart date[^}|]*\|(\d{4})\|(\d{1,2})\|(\d{1,2})"
)
DATE_DAY_FIRST_RE = re.compile(r"\b(\d{1,2})\s+([A-Za-z]{3,9})\.?\s+((?:19|20)\d{2})\b")
DATE_MONTH_FIRST_RE = re.compile(r"\b([A-Za-z]{3,9})\.?\s+(\d{1,2}),?\s+((?:19|20)\d{2})\b")
# Monat samt Jahr ohne Tag ("April 1983", "October 1994").
DATE_MONTH_YEAR_RE = re.compile(r"\b([A-Za-z]{3,9})\.?\s+((?:19|20)\d{2})\b")
# Zweite Lieferung im selben Feld: Schnitt am <br> oder am naechsten Jahr.
SECOND_DATE_RE = re.compile(r"<br|\b(?:19|20)\d{2}\b", re.IGNORECASE)


def http_get_json(url, params):
    """GET mit User-Agent, Wiederholung bei Rate-Limit/Fehler und Pause."""
    query = urllib.parse.urlencode(params)
    last_error = None
    for attempt in range(RETRIES):
        request = urllib.request.Request(
            url + "?" + query, headers={"User-Agent": USER_AGENT}
        )
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - jede Störung wird wiederholt
            last_error = error
            wait = 2.0 * (attempt + 1)
            print("  Wiederholung nach Fehler (%s), warte %.0fs" % (error, wait))
            time.sleep(wait)
    raise RuntimeError("GET fehlgeschlagen: %s (%s)" % (url, last_error))


def template_end(text, start):
    """Index hinter dem Ende des Templates, das bei `start` ('{{') beginnt.

    Zaehlt {{ }} und [[ ]] balanciert; -1, wenn das Template nicht endet.
    """
    depth = 0
    i = start
    n = len(text)
    while i < n:
        if text.startswith("{{", i) or text.startswith("[[", i):
            depth += 1
            i += 2
        elif text.startswith("}}", i) or text.startswith("]]", i):
            depth -= 1
            i += 2
            if depth == 0:
                return i
        else:
            i += 1
    return -1


def strip_templates(text):
    """Entfernt alle (balancierten) Templates samt Inhalt."""
    while True:
        start = text.find("{{")
        if start < 0:
            return text
        end = template_end(text, start)
        if end < 0:
            return text[:start]
        text = text[:start] + " " + text[end:]


def links_to_text(text):
    """[[Ziel|Anzeige]] -> Anzeige, [[Ziel]] -> Ziel."""
    def replace(match):
        parts = match.group(1).split("|")
        return parts[-1].strip() if len(parts) > 1 else parts[0].strip()

    previous = None
    while previous != text:
        previous = text
        text = LINK_RE.sub(replace, text)
    return text


def list_template_items(text):
    """Inhalt eines Listen-Templates als Liste von Eintraegen.

    plainlist trennt die Eintraege mit Zeilenumbruechen und "* ", ubli/ubl
    als eigene Parameter, manche mit " / " dazwischen - alles wird hier auf
    eine gemeinsame Liste reduziert.
    """
    match = LIST_TEMPLATE_RE.search(text)
    if not match:
        return None
    end = template_end(text, match.start())
    if end < 0:
        return None
    inner = text[match.end():end - 2]
    items = []
    for part in inner.split("|"):
        for line in part.split("\n"):
            for piece in line.split(" / "):
                piece = piece.strip().lstrip("*").strip()
                piece = re.sub(r"/\s*$", "", piece).strip()
                if piece and piece not in ("/", "•"):
                    items.append(piece)
    return items


def wikitext_to_text(value):
    """Wikitext eines Infobox-Werts zu lesbarem Text.

    Listen-Templates werden zu " / "-getrennten Eintraegen, uebrige Templates
    samt Inhalt entfernt, Links zu ihrem Anzeigetext, Referenzen, Kommentare
    und HTML-Tags entfernt, Klammern samt Inhalt (Jahresangaben,
    Ergqualifikationen) entfernt.
    """
    text = REF_RE.sub(" ", value or "")
    text = COMMENT_RE.sub(" ", text)
    text = BR_RE.sub(" / ", text)
    # Listen-Templates zuerst: ihr Inhalt ist die Herstellerliste.
    while True:
        items = list_template_items(text)
        if items is None:
            break
        match = LIST_TEMPLATE_RE.search(text)
        end = template_end(text, match.start())
        replacement = " / ".join(items)
        text = text[:match.start()] + replacement + text[end:]
    text = links_to_text(text)
    text = strip_templates(text)
    text = TAG_RE.sub(" ", text)
    text = text.replace("&nbsp;", " ")
    text = PARENS_RE.sub(" ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def clean_customer(text):
    """Erstkunde aus dem Resttext nach dem Datum, siehe parse_introduction.

    Wie [wikitext_to_text], aber Klammern bleiben erhalten: Umfassen sie den
    ganzen Kunden ("(Finnair)"), ist ihr Inhalt der Kunde; stehen sie
    hinten als Ergaenzung ("Ford Motor Company (private)"), fallen sie weg.
    """
    text = REF_RE.sub(" ", text)
    text = COMMENT_RE.sub(" ", text)
    text = BR_RE.sub(" ", text)
    while True:
        items = list_template_items(text)
        if items is None:
            break
        match = LIST_TEMPLATE_RE.search(text)
        end = template_end(text, match.start())
        text = text[:match.start()] + " / ".join(items) + text[end:]
    text = links_to_text(text)
    text = strip_templates(text)
    text = TAG_RE.sub(" ", text)
    text = text.replace("&nbsp;", " ")
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"^[\s,;:(]+", "", text)
    text = re.sub(r"^with\s+", "", text, flags=re.IGNORECASE)
    text = re.sub(r"[\s,;:]+$", "", text)
    without = TRAILING_PARENS_RE.sub("", text).strip()
    if without:
        text = without
    else:
        match = PARENS_RE.search(text)
        text = match.group(0)[1:-1].strip() if match else ""
    # Haengengebliebene Klammernreste ("Finnair)") fallen weg.
    text = re.sub(r"^[\s,;:()]+|[\s,;:()]+$", "", text)
    return text or None


def infobox_params(content):
    """Parameter der ersten `{{Infobox aircraft}}` (erste Ebene) als Dict."""
    start = -1
    marker_len = 0
    for marker in ("{{Infobox aircraft begin", "{{Infobox aircraft"):
        index = content.find(marker)
        if index >= 0:
            start = index
            marker_len = len(marker)
            break
    if start < 0:
        return None
    end = template_end(content, start)
    if end < 0:
        return None
    body = content[start + marker_len:end - 2]
    params = {}
    depth = 0
    current = []
    parts = []
    i = 0
    n = len(body)
    while i < n:
        if body.startswith("{{", i) or body.startswith("[[", i):
            depth += 1
            current.append(body[i:i + 2])
            i += 2
        elif body.startswith("}}", i) or body.startswith("]]", i):
            depth -= 1
            current.append(body[i:i + 2])
            i += 2
        elif body[i] == "|" and depth == 0:
            parts.append("".join(current))
            current = []
            i += 1
        else:
            current.append(body[i])
            i += 1
    parts.append("".join(current))
    for part in parts:
        if "=" in part:
            key, value = part.split("=", 1)
            params[key.strip()] = value.strip()
    return params


def parse_manufacturer(value):
    """Hersteller aus `manufacturers`/`manufacturer`, siehe Modul-Docstring."""
    text = wikitext_to_text(value)
    if not text:
        return None
    items = []
    seen = set()
    for item in text.split(" / "):
        item = item.strip().strip("*").strip()
        if not item:
            continue
        key = item.lower()
        if key in seen:
            continue
        seen.add(key)
        items.append(item)
    # Kurzere Eintraege, die in einem laengeren aufgehen, fallen weg
    # ("Bombardier" neben "Bombardier Aviation").
    deduped = [
        item for item in items
        if not any(item.lower() in other.lower() for other in items if other != item)
    ]
    return " / ".join(deduped) if deduped else None


def parse_production_start(params):
    """Baubeginn: erstes Jahr aus `produced` bzw. `construction_date`."""
    raw = params.get("produced") or params.get("construction_date") or ""
    text = wikitext_to_text(raw)
    match = YEAR_RE.search(text)
    if not match:
        return None
    year = int(match.group(1))
    return year if 1900 <= year <= 2030 else None


def parse_introduction(value):
    """Erstauslieferung aus `introduction`: (Datum, Erstkunde).

    Das Datum ist "YYYY-MM-DD", "YYYY-MM" (Monat ohne Tag, "April 1983") oder
    nur "YYYY" (z. B. Dash 8: "1984 with [[NorOntair]]"); der Erstkunde ist
    der Resttext nach dem Datum ohne "with"/Klammern. Ohne Datum/Kunde ->
    (None, None) bzw. (None, Kunde).
    """
    raw = value or ""
    text = REF_RE.sub(" ", raw)
    text = COMMENT_RE.sub(" ", text)
    date_str = None
    rest = text
    match = START_DATE_TEMPLATE_RE.search(text)
    if match:
        year, month, day = (int(group) for group in match.groups())
        if 1 <= month <= 12 and 1 <= day <= 31:
            date_str = "%04d-%02d-%02d" % (year, month, day)
            # Das ganze Template abschneiden, nicht nur bis zum Tagesparameter.
            end = template_end(text, match.start())
            rest = (text[:match.start()] + " " + text[end:]) if end > 0 else ""
    if date_str is None:
        # Umhuellende Templates wie {{nowrap|...}} aufloesen.
        while True:
            wrapped = WRAPPER_TEMPLATE_RE.match(text.strip())
            if not wrapped:
                break
            text = wrapped.group(1)
        rest = text
        for pattern, order in (
            (DATE_DAY_FIRST_RE, "day_first"),
            (DATE_MONTH_FIRST_RE, "month_first"),
            (DATE_MONTH_YEAR_RE, "month_year"),
        ):
            match = pattern.search(text)
            if not match:
                continue
            if order == "day_first":
                day = int(match.group(1))
                month = MONTHS.get(match.group(2).lower())
                year = int(match.group(3))
            elif order == "month_first":
                month = MONTHS.get(match.group(1).lower())
                day = int(match.group(2))
                year = int(match.group(3))
            else:
                month = MONTHS.get(match.group(1).lower())
                day = None
                year = int(match.group(2))
            if month and (day is None or 1 <= day <= 31):
                if day is None:
                    date_str = "%04d-%02d" % (year, month)
                else:
                    date_str = "%04d-%02d-%02d" % (year, month, day)
                rest = text[:match.start()] + " " + text[match.end():]
                break
    if date_str is None:
        match = YEAR_RE.search(rest)
        if match:
            date_str = match.group(1)
            rest = rest[:match.start()] + " " + rest[match.end():]
    # Eine zweite Lieferung im selben Feld ("... <br> 1995 with ...") samt
    # deren Kunden gehoert nicht zur Erstauslieferung.
    rest = SECOND_DATE_RE.split(rest, maxsplit=1)[0]
    return date_str, clean_customer(rest)


def fetch_pages(titles):
    """Laedt Wikitext fuer Titel; Rueckgabe (Seiten nach Titel, Redirects)."""
    params = {
        "action": "query",
        "prop": "revisions",
        "rvprop": "content",
        "rvslots": "main",
        "formatversion": "2",
        "format": "json",
        "redirects": "1",
        "titles": "|".join(titles),
    }
    data = http_get_json(ENWIKI_API, params)
    pages = {}
    for page in data.get("query", {}).get("pages", []):
        pages[page.get("title")] = page
    redirects = {
        entry["from"]: entry["to"]
        for entry in data.get("query", {}).get("redirects", [])
    }
    return pages, redirects


def page_content(page):
    revisions = page.get("revisions") or [{}]
    return revisions[0].get("slots", {}).get("main", {}).get("content", "")


def build_types():
    """Baut die Fakten aller Typen aus der englischen Wikipedia."""
    all_titles = []
    for _, _, candidates in TYPES:
        for candidate in candidates:
            if candidate not in all_titles:
                all_titles.append(candidate)
    pages = {}
    redirects = {}
    for start in range(0, len(all_titles), WIKI_BATCH):
        batch_pages, batch_redirects = fetch_pages(all_titles[start:start + WIKI_BATCH])
        pages.update(batch_pages)
        redirects.update(batch_redirects)
        if start + WIKI_BATCH < len(all_titles):
            time.sleep(0.3)

    types = {}
    for code, display_name, candidates in TYPES:
        chosen = None
        params = None
        for candidate in candidates:
            target = redirects.get(candidate, candidate)
            page = pages.get(target)
            if page is None or "missing" in page:
                continue
            candidate_params = infobox_params(page_content(page))
            if candidate_params is None:
                continue
            chosen = target
            params = candidate_params
            break
        if params is None:
            print("  Warnung: %s hat keinen brauchbaren Artikel (%s)"
                  % (code, ", ".join(candidates)))
            continue
        manufacturer = MANUFACTURER_OVERRIDE.get(code) or parse_manufacturer(
            params.get("manufacturers") or params.get("manufacturer")
        )
        production_start = parse_production_start(params)
        # `introduction` (meist) oder `introduced` (737 MAX-Artikel).
        first_delivery, launch_customer = parse_introduction(
            params.get("introduction") or params.get("introduced")
        )
        facts = {"name": display_name}
        if manufacturer:
            facts["manufacturer"] = manufacturer
        if production_start is not None:
            facts["productionStart"] = production_start
        if first_delivery:
            facts["firstDelivery"] = first_delivery
        if launch_customer:
            facts["launchCustomer"] = launch_customer
        facts["wiki"] = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(
            chosen.replace(" ", "_")
        )
        types[code] = facts
        print("  %-8s <- %-32s %s | %s | %s | %s" % (
            code, chosen,
            manufacturer or "-",
            production_start or "-",
            first_delivery or "-",
            launch_customer or "-",
        ))
    return types


def normalize_alias(key):
    """Alias-Schluessel normalisieren: Grossbuchstaben, nur Buchstaben/Ziffern."""
    return re.sub(r"[^A-Za-z0-9]", "", key).upper()


def build_aliases(seats_path):
    """Aliase aus aircraft_seats.json plus EXTRA_ALIASES (EXTRA gewinnt).

    EXTRA_ALIASES definiert die Varianten-Zuordnung (neo, MAX, E2 zeigen auf
    ihren eigenen Typ); aircraft_seats.json kann ältere Mappings enthalten
    ("338" -> "A330"), die hier überschrieben werden.
    """
    aliases = {}
    with open(seats_path, encoding="utf-8") as handle:
        seats = json.load(handle)
    for key, value in (seats.get("aliases") or {}).items():
        aliases[normalize_alias(key)] = value
    # EXTRA_ALIASES gewinnen (Varianten-Zuordnung).
    for key, value in EXTRA_ALIASES.items():
        aliases[normalize_alias(key)] = value
    # Nur Aliase auf bekannte Typen behalten.
    known = {code for code, _, _ in TYPES}
    return {key: value for key, value in sorted(aliases.items()) if value in known}


def validate(types, aliases):
    """Prueft das Ergebnis; False bei Fehlern."""
    ok = True
    if len(types) < 50:
        print("  Fehler: nur %d Typen (mindestens 50 erwartet)" % len(types))
        ok = False
    for code, facts in sorted(types.items()):
        for field in ("name", "wiki"):
            if not facts.get(field):
                print("  Fehler: %s ohne %s" % (code, field))
                ok = False
        if not facts.get("manufacturer") and not facts.get("productionStart"):
            print("  Fehler: %s ohne Hersteller und Baubeginn" % code)
            ok = False
        wiki = facts.get("wiki", "")
        if not wiki.startswith("https://en.wikipedia.org/wiki/"):
            print("  Fehler: %s mit falschem Wiki-Link: %s" % (code, wiki))
            ok = False
        delivery = facts.get("firstDelivery")
        if delivery and not re.fullmatch(
            r"(?:19|20)\d{2}(?:-(?:0[1-9]|1[0-2])(?:-(?:0[1-9]|[12]\d|3[01]))?)?",
            delivery,
        ):
            print("  Fehler: %s mit unplausiblem Datum: %s" % (code, delivery))
            ok = False
    dangling = sorted(set(aliases.values()) - set(types))
    if dangling:
        print("  Fehler: Aliase ohne Typ: %s" % ", ".join(dangling))
        ok = False
    return ok


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--seats", default=SEATS_PATH,
                        help="aircraft_seats.json fuer die Alias-Uebernahme")
    parser.add_argument("--out", default=OUT_PATH, help="Ziel-JSON")
    args = parser.parse_args()

    print("Lade Flugzeugtypen von der englischen Wikipedia ...")
    types = build_types()
    aliases = build_aliases(args.seats)
    print("Aliase: %d (davon %d aus aircraft_seats.json)"
          % (len(aliases), len(aliases) - len(EXTRA_ALIASES)))

    payload = {"types": dict(sorted(types.items())), "aliases": aliases}
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        handle.write("\n")
    print("Geschrieben: %s (%d Typen, %d Aliase)"
          % (args.out, len(types), len(aliases)))

    if not validate(types, aliases):
        sys.exit(1)
    print("Pruefung bestanden.")


if __name__ == "__main__":
    main()
