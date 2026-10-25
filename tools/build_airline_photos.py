#!/usr/bin/env python3
"""Erzeugt die mitgelieferten Airline-Fotos (Bemalung) für die Top-250-Airlines.

Die Detailseite einer Fluggesellschaft zeigt über den Stammdaten ein Foto eines
Fliegers in der Bemalung der Airline. Die App lädt nichts nach; die Bilder werden
deshalb einmalig hier erzeugt und als WebP samt Attribution unter
`app/src/main/assets/airlines/photos/` abgelegt:

    photos/LH.webp          Foto, benannt nach dem IATA-Code
    photos/LH_2.webp        zweites Foto einer Galerie (Lufthansa Group)
    photos/photos.json      Attribution je IATA-Code (Autor, Lizenz, Quell-URL)

Aufruf:

    python3 tools/build_airline_photos.py                 # Top 250 + Galerie
    python3 tools/build_airline_photos.py --list          # Liste nur anzeigen
    python3 tools/build_airline_photos.py --only LH,EK    # nur einzelne Airlines
    python3 tools/build_airline_photos.py --force         # vorhandene neu laden
    python3 tools/build_airline_photos.py --refresh       # vorhandene auf Aktualität prüfen
    python3 tools/build_airline_photos.py --no-gallery    # ohne Galerie-Ergänzung

**Galerie**: Airlines der Lufthansa Group (`group` in `airline_facts.json`)
bekommen bis zu drei Fotos, die in der App seitlich gewischt werden. Das erste
Foto ist das bestehende `<IATA>.webp`, die Ergänzungen liegen als `<IATA>_2.webp`,
`<IATA>_3.webp`; in photos.json steht pro Airline ein `photos`-Array, jede
Datei mit eigener Attribution. Airlines ohne Gruppe (oder mit manuellem Foto)
behalten ein einzelnes Foto. Der Lauf ist inkrementell: Airlines mit drei Fotos
werden übersprungen.

**Top 250**: Die Liste wird zur Laufzeit aus `airline_facts.json` berechnet -
alle Airlines ohne Insolvenzjahr (`dissolved`) mit bekannter Flottengroesse
(`fleet`), die im Katalog (`airlines.json`) einen ICAO-Code haben, absteigend
nach Flottengroesse, bei Gleichstand nach IATA-Code. Eine harte Liste wäre
schnell veraltet; so rutschen wachsende Airlines automatisch nach. Die Fotos
werden nach dem **IATA-Code** benannt (nicht nach ICAO wie die Logos): ICAO-Codes
kollidieren zwischen Airlines, IATA-Codes nicht.

**Quelle**: Wikimedia Commons (MediaWiki-API, Dateinamensraum). Andere
Foto-Seiten (planespotters.net, airliners.net) verbieten das Kopieren ihrer
Fotos ausdrücklich; Commons-Dateien stehen dagegen unter freien Lizenzen.

**Lizenz-Whitelist** (aus `LicenseShortName`): Public Domain (PD), CC0, CC BY
(alle Versionen) und GFDL. Nicht-kommerziell (NC) oder "Keine Bearbeitung"
(ND) fallen heraus, ebenso "Copyrighted free use". CC BY-SA ist erlaubt, wird
in der Wertung aber hinter CC BY/PD zurückgestellt. Die App zeigt unter dem
Foto `© Autor · Lizenz · Wikimedia Commons` und verlinkt die Lizenz sowie die
Commons-Dateiseite.

**Auswahl**: Pro Airline bis zu drei Suchanfragen (Name in Anführungszeichen
plus "aircraft", dann ohne Anführungszeichen, dann der Name allein), je bis
zu 50 Treffer; manche Katalognamen sind veraltet (EU heisst im Katalog
"Empresa Ecuatoriana De Aviacion", der IATA-Code gehört aber zu Chengdu
Airlines), deshalb wird als Fallback der Titel des englischen Wikipedia-Artikels
aus `airline_facts.json` probiert. Harte Filter: JPEG/PNG, mindestens
1200 x 700 px, Querformat bis maximal 2,5:1 (keine Panoramen), Lizenz in der
Whitelist, Autor bekannt, die
Airline kommt in Titel oder Beschreibung vor und der Titel nennt keine
andere Airline aus dem Katalog (Markenyamilien wie "Jetstar" in "Jetstar
Asia Airways" zählen nicht als fremd). Wertung: Name im Titel vor Name in
der Beschreibung, Flugzeugtyp im Titel, Bildbreite, Seitenverhältnis 1,3-2,0,
Lizenz (PD/CC0 vor CC BY vor CC BY-SA/GFDL), harte Ausschlüsse für alles, was
keine Außenaufnahme eines ganzen Flugzeugs ist (Modell/Toy/Papier/Rendering/
Logo/Cockpit/Kabine/Karte/Unfallfoto/Militär), Abzüge für Nahaufnahme/
Triebwerk/Sitze/Wingview/Klassenfoto. Gewählt wird die beste Außenaufnahme
eines Flugzeugs der Airline.

**Aktualität**: Die Fotos sollen die Bemalung so aktuell wie möglich zeigen.
Das Aufnahmedatum (EXIF `DateTimeOriginal`, Ersatz: Upload-Datum der Datei)
fliesst in die Wertung ein: Ein Foto, das höchstens etwa drei Jahre alt ist,
bekommt den stärksten Zuschlag, innerhalb der Frist zählt das jüngere, darüber
abgestuft bis acht Jahre. Das Einzelfoto einer Airline zeigt die
**Standardlackierung** auf einem **Flaggschiff-Typ** (Grossraumflugzeug) -
Sonderlackierungen (Star Alliance, Retro, Werbepartner) werden dafür
ausgeschlossen, es sei denn, es gibt gar keine Standardlackierung. In der
**Galerie** (mehr als ein Bild) sind Sonderlackierungen dagegen willkommen und
werden bevorzugt.

**Refresh** (`--refresh`): Prüft die vorhandenen Fotos und ersetzt sie durch
jüngere. Das Aufnahmedatum der gespeicherten Fotos wird dazu bei Commons
nachgezogen; ersetzt wird nur, wenn das neue Foto nachweislich jünger ist als
das gespeicherte - ein bereits gutes Foto wird nicht gegen ein älteres
getauscht. Fehlende Airlines der Top 250 lädt der Lauf wie gewohnt neu.

**Konvertierung**: Download als Thumbnail (max. 1280 px breit), EXIF-Drehung,
Alpha auf Weiß auflösen, auf 1280 px Breite begrenzen, WebP (Qualität 80).
Das Seitenverhältnis bleibt erhalten; die App zeigt das Bild mit `centerCrop`.
`modified` in photos.json ist true, wenn das Bild verkleinert wurde - nur dafür
verlangt CC BY eine Änderungsangabe, eine reine Formatkonvertierung nicht.

**Kein Foto**: Für Airlines, bei denen die Suche nur falsche Treffer findet
(Absturzkontext, Militär, fremde Bemalung), steht `NO_PHOTO` im Skript - sie
bekommen kein Foto, die Kachel bleibt in der App weg.

**Manuelle Vorgabe**: Wie bei den Logos (`liveries_manual/`) können Fotos von
Hand kuratiert werden, etwa wo die automatische Suche nichts Brauchbares
findet. Dafür im Repo-Root `photos_manual/` anlegen:

    photos_manual/LH.jpg
    photos_manual/LH.json    {"author": "...", "license": "CC BY 4.0",
                             "licenseUrl": "https://creativecommons.org/...",
                             "sourceUrl": "https://commons.wikimedia.org/...",
                             "title": "...", "modified": false}

Manuelle Dateien gewinnen immer gegen den Download; das Skript installiert sie
bei jedem Lauf und prüft danach die gesamte Zuordnung (fehlende Dateien,
unvollständige Attribution, nicht erlaubte Lizenz, verwaiste Dateien).
"""

import argparse
import datetime
import html
import io
import json
import os
import re
import socket
import sys
import time
import urllib.parse
import urllib.request

try:
    from PIL import Image, ImageOps
except ImportError:
    sys.exit("Pillow fehlt: python3 -m pip install --user Pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "airlines", "airlines.json")
FACTS_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "airlines", "airline_facts.json")
PHOTOS_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "airlines", "photos")
PHOTOS_JSON = os.path.join(PHOTOS_DIR, "photos.json")
MANUAL_DIR = os.path.join(ROOT, "photos_manual")

COMMONS_API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "LogbookAirlinePhotos/1.0 (https://github.com/expressiveminute/Logbook)"

TOP_N = 250

# Airlines dieser Gruppe bekommen eine Galerie mit bis zu GALLERY_PHOTOS
# Fotos (in der App seitlich wischbar), siehe Modul-Docstring.
GALLERY_GROUP = "Lufthansa Group"
GALLERY_PHOTOS = 3

# Airlines, für die die automatische Suche nur falsche Fotos findet - ein
# Foto aus dem Absturzkontext, ein Militärfoto ohne Flugzeug der Airline oder
# ein Flugzeug in fremder Bemalung. Sie bekommen kein Foto (die Kachel bleibt
# in der App weg); ein Eintrag aus einem früheren Lauf wird entfernt.
NO_PHOTO = {
    "BK",  # Potomac Air: nur ein NTSB-Bergungsfoto (UH-60, kein Potomac-Air-Flugzeug)
    "N4",  # Mountain Air Company: nur Militär-Übungsfotos ("Mountain Exercise")
    "YC",  # Ciel Canadien: nur Fotos in fremder Bemalung (Aero Icarus)
}

# Download als Thumbnail in dieser Breite; harte Maße für die Filter. Sie
# gelten für das Original; das gespeicherte Thumbnail (max. THUMB_WIDTH breit,
# nie hochgerechnet) muss Breite 1200-1280, Höhe mindestens MIN_STORED_HEIGHT
# und Querformat bis MAX_RATIO erfüllen - so prüft es
# AirlinePhotosAssetTest.masseEntsprechenDenFilternDesSkripts. Daraus folgt die
# Panoramagrenze für das Original (breitere Originale würden beim Verkleinern
# unter die Mindesthöhe rutschen).
THUMB_WIDTH = 1280
MIN_WIDTH = 1200
MIN_HEIGHT = 700
MIN_STORED_HEIGHT = 500
MAX_RATIO = 2.5
WEBP_QUALITY = 80

REQUEST_TIMEOUT = 60
RETRIES = 4
SEARCH_LIMIT = 50

# Minimale Dateigröße eines validen Fotos (leere/kaputte WebP-Dateien fallen
# sonst erst in der App auf).
MIN_FILE_BYTES = 5000

# Wörter, die am Ende eines Airline-Namens nichts zum Wiedererkennen beitragen
# ("United Airlines" -> "united", "American Eagle Airlines" -> "american eagle").
GENERIC_NAME_WORDS = {
    "airlines", "airline", "airways", "air", "cargo", "holdings", "holding",
    "group", "limited", "ltd", "ag", "sa", "sas", "plc", "inc", "llc", "gmbh",
    "co", "company", "corporation", "pty", "nv", "bv", "oy", "ab", "as",
}

# Spezifische Flugzeugtypen (Titel-Treffer zählen mehr als der Gattungsbegriff).
AIRCRAFT_TYPE_RE = re.compile(
    r"\b(a2\d\d|a3[0-5]\d|b7[0-8]\d|b38m|b39m|737|747|757|767|777|787|788|789"
    r"|md-?8[0-9]|md-?9[0-9]|md-?11|dc-?9|dc-?10|l-?1011|il-?6[26]|il-?7[66]"
    r"|il-?86|il-?96|tu-?1[35]4|tu-?204|an-?\d+|su-?95|ssj-?100|rj-?\d+|crj\d*"
    r"|e1[6-9]\d|atr|q400|dh8[a-d]|fokker|f-?100|f-?70|e-?145)\b",
    re.IGNORECASE,
)
AIRCRAFT_WORD_RE = re.compile(
    r"\b(boeing|airbus|embraer|bombardier|aircraft|airliner|jetliner|airplane"
    r"|plane|passagierflugzeug|verkehrsflugzeug|flugzeug)\b",
    re.IGNORECASE,
)

# Harte Filter: kein Foto, das eine Außenaufnahme eines ganzen Flugzeugs in
# Bemalung ausschließt. Ein Modell, ein Cockpit, die Kabine, eine Karte oder
# ein Unfallfoto ist nicht die Bemalung der Airline - solche Treffer werden
# verworfen, nicht nur abgewertet. (Sonst würde beim Refresh ein junges
# Kabinenfoto ein älteres, aber brauchbares Außenfoto verdrängen.)
HARD_NEGATIVE_RES = (
    re.compile(r"\b(models?|modell(e|flugzeug)?|toys?|spielzeug)\b", re.IGNORECASE),
    re.compile(r"\bdie-?cast\b", re.IGNORECASE),
    re.compile(r"\bscreenshots?\b", re.IGNORECASE),
    re.compile(r"\b(papers?|zeichnung|sketch)\b", re.IGNORECASE),
    re.compile(r"\b(render|rendering|gerendert|3d-?grafik)\b", re.IGNORECASE),
    re.compile(r"\blogos?\b", re.IGNORECASE),
    re.compile(r"\bcockpits?\b", re.IGNORECASE),
    re.compile(r"\b(interiors?|cabins?|kabine|inside)\b", re.IGNORECASE),
    # Keine Karte und kein Unfallfoto: weder die Wrackkarte von MH 370 noch
    # die Bergungsaktion am Potomac sind ein Foto der Bemalung.
    re.compile(r"\bmaps?\b", re.IGNORECASE),
    re.compile(r"\b(crash(es)?|accident|wreck|emergency)\b", re.IGNORECASE),
    # Bergung und Unfalluntersuchung: das Wrack ist nicht die Bemalung.
    re.compile(r"\b(recovered|recovery|salvage)\b", re.IGNORECASE),
    re.compile(r"\bntsb\b", re.IGNORECASE),
    # Militär: Übung oder Waffenträger ist kein Verkehrsflugzeug der Airline.
    re.compile(r"\b(exercises?|manoeuvres?|maneuvers?|drills?)\b", re.IGNORECASE),
    re.compile(
        r"\b(military|marine corps|air force|navy|army|luftwaffe|bundeswehr)\b",
        re.IGNORECASE,
    ),
)

# Abzüge: Außenaufnahme, aber keine schöne der ganzen Bemalung - Nahaufnahme,
# Triebwerk, Sitzplätze oder nur das Klassenerlebnis.
NEGATIVE_WORDS = (
    (re.compile(r"\bseats?\b", re.IGNORECASE), -15),
    (re.compile(r"\bengines?\b", re.IGNORECASE), -10),
    (re.compile(r"\bwing ?views?\b", re.IGNORECASE), -10),
    (re.compile(r"\bdecals?\b", re.IGNORECASE), -10),
    (re.compile(r"\bclose-?ups?\b", re.IGNORECASE), -5),
    (re.compile(r"\b(first|business|economy|premium)\s*class\b", re.IGNORECASE), -15),
)

# Ziel-Alter der Fotos: höchstens etwa drei Jahre, damit die Bemalung der
# Airline so aktuell wie möglich ist. Abgestuft wird bis acht Jahre; ältere
# Fotos bekommen keinen Zuschlag mehr (siehe recency_score).
TARGET_AGE_DAYS = 3 * 365
RECENT_AGE_DAYS = 5 * 365
MID_AGE_DAYS = 8 * 365

# Sonderlackierungen, die das Einzelfoto nicht zeigen soll - dort gehört die
# Standardlackierung hin (in der Galerie sind sie willkommen, siehe
# candidate_score). Gematcht wird gegen Titel und Beschreibung.
SPECIAL_LIVERY_RES = (
    re.compile(r"special\s+(livery|scheme|colour|color|paint|design)", re.IGNORECASE),
    re.compile(r"sonderlackierung|sonderbemalung|sonderanstrich", re.IGNORECASE),
    re.compile(r"\bretro\s+(livery|scheme|paint)\b", re.IGNORECASE),
    re.compile(r"\bheritage\s+(livery|scheme|paint)\b", re.IGNORECASE),
    re.compile(
        r"\b(classic|historic|historical|former|previous|throwback)\s+(livery|scheme|paint)\b",
        re.IGNORECASE,
    ),
    re.compile(r"\bone-?\s?off\s+(livery|scheme|paint)\b", re.IGNORECASE),
    re.compile(r"\bunique\s+(livery|scheme|paint)\b", re.IGNORECASE),
    re.compile(
        r"\bpokemon\b|\bdoraemon\b|\bhello\s+kitty\b|\bstar\s+wars\b|\bpixar\b|\bdisney\b",
        re.IGNORECASE,
    ),
    re.compile(r"\bfifa\b|\bworld\s+cup\b|\buefa\b", re.IGNORECASE),
)

# Die Allianz-Namen gelten nur für den Titel: Die Beschreibung nennt die
# Allianz oft bloss als Mitgliedschaft der Airline, ohne dass das Flugzeug
# die Sonderlackierung trägt.
SPECIAL_LIVERY_TITLE_RES = (
    re.compile(r"\bstar\s+alliance\b|\boneworld\b|\bskyteam\b", re.IGNORECASE),
)

# Flaggschiff-Typen (Grossraumflugzeuge): Das Einzelfoto soll die
# Standardlackierung auf einem solchen Typ zeigen, nicht auf einer
# Kurzstreckenmaschine.
FLAGSHIP_TYPE_RE = re.compile(
    r"\b(a380|a350|a340|a330|747|777|787|788|789|767|md-?11|il-?96)\b"
    r"|\bb[37][478]\d\b",
    re.IGNORECASE,
)

MANUAL_IMAGE_RE = re.compile(r"^([A-Za-z0-9]{2})\.(png|jpe?g|webp)$", re.IGNORECASE)


def prefer_ipv4():
    """Lässt `urllib` nur IPv4-Adressen auflösen.

    Manche Netze haben ein AAAA-Routing, über das der Server nicht antwortet;
    die Verbindung hängt dann bis zum Timeout (siehe build_airport_satellite).
    """
    original_getaddrinfo = socket.getaddrinfo

    def getaddrinfo_ipv4(host, port, family=0, type=0, proto=0, flags=0):
        return [
            address for address in original_getaddrinfo(host, port, family, type, proto, flags)
            if address[0] == socket.AF_INET
        ]

    socket.getaddrinfo = getaddrinfo_ipv4


def http_get_json(params):
    """GET auf die Commons-API mit User-Agent, Wiederholung bei Fehlern."""
    query = urllib.parse.urlencode(params)
    last_error = None
    for attempt in range(RETRIES):
        request = urllib.request.Request(
            COMMONS_API + "?" + query, headers={"User-Agent": USER_AGENT}
        )
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - jede Störung wird wiederholt
            last_error = error
            wait = 2.0 * (attempt + 1)
            print("  Wiederholung nach Fehler (%s), warte %.0fs" % (error, wait))
            time.sleep(wait)
    raise RuntimeError("GET fehlgeschlagen: %s (%s)" % (COMMONS_API, last_error))


def download(url):
    """Lädt eine Datei (Thumbnail) mit User-Agent, Wiederholung bei Fehlern."""
    last_error = None
    for attempt in range(RETRIES):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 - jede Störung wird wiederholt
            last_error = error
            wait = 2.0 * (attempt + 1)
            print("  Wiederholung nach Fehler (%s), warte %.0fs" % (error, wait))
            time.sleep(wait)
    raise RuntimeError("Download fehlgeschlagen: %s (%s)" % (url, last_error))


def load_catalog(path):
    """Katalog als Dict IATA-Code -> Eintrag."""
    with open(path, encoding="utf-8") as handle:
        airlines = json.load(handle)["airlines"]
    return {a["iata"].strip().upper(): a for a in airlines if a.get("iata")}


def load_facts(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def wiki_title(url):
    """Artikeltitel aus einem Wikipedia-Link, z. B. .../wiki/Chengdu_Airlines."""
    if not url or "/wiki/" not in url:
        return None
    title = urllib.parse.unquote(url.rsplit("/wiki/", 1)[-1])
    title = title.split("#", 1)[0].replace("_", " ").strip()
    return title or None


def top_airlines(catalog, facts, limit):
    """Top-Airlines nach Flottengroesse: aktiv, mit Flotte, mit ICAO im Katalog.

    Rückgabe: Liste (fleet, iata, name, wiki_title), absteigend nach Flotte,
    bei Gleichstand nach IATA-Code. Insolvenzen (`dissolved`) fallen heraus -
    eine aufgelöste Airline bekommt kein Foto. `wiki_title` ist der Titel des
    englischen Wikipedia-Artikels aus den Stammdaten; manche Katalognamen sind
    veraltet (EU heisst im Katalog "Empresa Ecuatoriana De Aviacion", der
    IATA-Code gehört aber zu Chengdu Airlines) - der Wiki-Titel ist dann der
    Fallback für die Suche.
    """
    candidates = []
    for iata, record in facts.items():
        if "dissolved" in record or "fleet" not in record:
            continue
        entry = catalog.get(iata)
        if not entry or not entry.get("icao"):
            continue
        candidates.append((
            record["fleet"],
            iata,
            entry.get("name") or iata,
            wiki_title(record.get("wiki")),
        ))
    candidates.sort(key=lambda item: (-item[0], item[1]))
    return candidates[:limit]


def license_tokens(short_name):
    """Lizenz-String in Tokens, z. B. 'CC BY-NC-SA 4.0' -> [CC, BY, NC, SA, 4, 0]."""
    return [token for token in re.split(r"[^A-Za-z0-9]+", short_name.upper()) if token]


def license_allowed(short_name):
    """Whitelist: PD, CC0, CC BY, GFDL - ohne NC (nicht kommerziell) und ND."""
    tokens = license_tokens(short_name)
    if not tokens:
        return False
    if "NC" in tokens or "ND" in tokens:
        return False
    if "GFDL" in tokens:
        return True
    if "CC0" in tokens or "PD" in tokens:
        return True
    if "PUBLIC" in tokens and "DOMAIN" in tokens:
        return True
    if "NO" in tokens and "RESTRICTIONS" in tokens:
        return True
    return "CC" in tokens and "BY" in tokens


def license_score(short_name):
    """Wertung der Lizenz: PD/CC0 vor CC BY vor CC BY-SA/GFDL."""
    tokens = license_tokens(short_name)
    if "CC0" in tokens or "PD" in tokens:
        return 15
    if "CC" in tokens and "BY" in tokens and "SA" not in tokens:
        return 10
    return 5


def normalize_license_url(url):
    """http-Lizenz-URLs auf https heben (ältere Commons-Dateien liefern http)."""
    if url.startswith("http://"):
        return "https://" + url[len("http://"):]
    return url


def license_url_for(short_name):
    """Kopie der Lizenz-URL aus dem Kurznamen.

    Commons liefert nicht zu jeder Datei ein `LicenseUrl` (Public Domain und
    ältere CC-Versionen oft nicht); die kanonische Adresse der Lizenz ist dann
    die einzige verlässliche Referenz für die Namensnennung.
    """
    tokens = license_tokens(short_name)
    if not tokens:
        return ""
    if "CC0" in tokens:
        return "https://creativecommons.org/publicdomain/zero/1.0"
    if "GFDL" in tokens:
        return "https://www.gnu.org/licenses/fdl-1.2.html"
    if "PUBLIC" in tokens and "DOMAIN" in tokens or "PD" in tokens:
        return "https://creativecommons.org/publicdomain/mark/1.0"
    if "CC" in tokens and "BY" in tokens:
        version = re.search(r"\d+(?:\.\d+)?", short_name)
        version = version.group(0) if version else "4.0"
        if "SA" in tokens:
            return "https://creativecommons.org/licenses/by-sa/%s" % version
        return "https://creativecommons.org/licenses/by/%s" % version
    return ""


def parse_photo_date(metadata, info, title=""):
    """Aufnahmedatum eines Fotos als ISO-String oder None.

    Primär das EXIF-Datum (DateTimeOriginal) - es sagt, wann das Flugzeug
    fotografiert wurde, und damit, wie alt die Bemalung auf dem Foto ist.
    Steht im Titel ein eindeutiges Datum (siehe title_date_year) und weicht
    sein Jahr vom EXIF-Jahr ab, ist die Kamerauhr verstellt - der Titel des
    Fotografen zählt. Fehlt das EXIF-Datum, dient das Titel-Datum, sonst das
    Upload-Datum der Datei als Ersatz (Commons-`timestamp`).
    """
    raw = strip_html((metadata.get("DateTimeOriginal") or {}).get("value", ""))
    match = re.search(r"(\d{4})[:-](\d{1,2})[:-](\d{1,2})", raw)
    exif_date = None
    if match:
        year, month, day = (int(group) for group in match.groups())
        if 2005 <= year <= datetime.date.today().year + 1:
            try:
                exif_date = datetime.date(year, month, day)
            except ValueError:
                pass
    title_year = title_date_year(title)
    if title_year is not None and exif_date is not None:
        if abs(title_year - exif_date.year) > 1:
            # Kamerauhr verstellt: Das Datum im Titel des Fotografen zählt.
            try:
                return datetime.date(
                    title_year, exif_date.month, exif_date.day
                ).isoformat()
            except ValueError:
                return "%04d-01-01" % title_year
    if exif_date is not None:
        return exif_date.isoformat()
    if title_year is not None:
        return "%04d-01-01" % title_year
    upload = info.get("timestamp") or ""
    match = re.match(r"(\d{4}-\d{2}-\d{2})", upload)
    if match:
        return match.group(1)
    return None


# Monatsname samt Jahr im Titel ("July 2026", "Februar 2026").
MONTH_YEAR_RE = re.compile(
    r"\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?"
    r"|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?"
    r"|dec(?:ember)?)\.?\s+(?:19|20)\d{2}\b",
    re.IGNORECASE,
)

# Datumsangaben im Titel: ausgeschrieben (02.10.2017, 31.12.2012), ISO
# (2017-10-02) oder Jahr samt zweistelliger Nummer (MUC 2015 01).
TITLE_DATE_RES = (
    re.compile(r"\b\d{1,2}[./-]\d{1,2}[./-](?:19|20)\d{2}\b"),
    re.compile(r"\b(?:19|20)\d{2}-\d{2}-\d{2}\b"),
    re.compile(r"\b(?:19|20)\d{2}\s\d{2}\b"),
)


def title_date_year(title):
    """Jahr aus einem Datum im Titel; None, wenn der Titel keines nennt.

    Ein blosses Jahr zählt nicht ("in 2018 livery" - das kann das Jahr der
    Lackierung sein, nicht das des Fotos).
    """
    text = title or ""
    for pattern in TITLE_DATE_RES:
        match = pattern.search(text)
        if match:
            return int(re.search(r"(?:19|20)\d{2}", match.group(0)).group(0))
    match = MONTH_YEAR_RE.search(text)
    if match:
        return int(re.search(r"(?:19|20)\d{2}", match.group(0)).group(0))
    return None


def age_days(date_str, today=None):
    """Alter eines ISO-Datums in Tagen; None, wenn es fehlt oder ungültig ist."""
    if not date_str:
        return None
    try:
        return ((today or datetime.date.today()) -
                datetime.date.fromisoformat(date_str)).days
    except ValueError:
        return None


def recency_score(date_str, today=None):
    """Wertung nach Alter des Fotos.

    Die Fotos sollen so neu wie möglich sein: Ein Foto, das höchstens etwa
    drei Jahre alt ist, bekommt den stärksten Zuschlag (TARGET_AGE_DAYS),
    innerhalb der Frist zählt das jüngere. Darüber abgestuft bis acht Jahre,
    ältere Fotos bekommen nichts - dann entscheidet die Qualität allein.
    """
    days = age_days(date_str, today)
    if days is None:
        return 0.0
    days = max(days, 0)
    if days <= TARGET_AGE_DAYS:
        return 300.0 + 40.0 * (1.0 - days / float(TARGET_AGE_DAYS))
    if days <= RECENT_AGE_DAYS:
        return 150.0
    if days <= MID_AGE_DAYS:
        return 60.0
    return 0.0


def is_better(new_date, old_date, today=None):
    """True, wenn ein neues Foto ein gespeichertes ersetzen soll (--refresh).

    Ersetzt wird nur, wenn das neue Foto nachweislich jünger ist. Ist das
    Alter des gespeicherten Fotos unbekannt, zählt ein neues Foto, das das
    Zielalter (höchstens etwa drei Jahre) sicher einhält.
    """
    if not new_date:
        return False
    if not old_date:
        days = age_days(new_date, today)
        return days is not None and days <= TARGET_AGE_DAYS
    return new_date > old_date


def stored_dimensions_ok(photo):
    """True, wenn die Maße eines gespeicherten Fotos den Test-Grenzen genügen.

    AirlinePhotosAssetTest.masseEntsprechenDenFilternDesSkripts prüft das
    gespeicherte Thumbnail: Breite 1200-1280 px, Höhe mindestens
    MIN_STORED_HEIGHT, Querformat, höchstens MAX_RATIO zu 1.
    """
    width = photo.get("width") or 0
    height = photo.get("height") or 0
    return (MIN_WIDTH <= width <= THUMB_WIDTH
            and height >= MIN_STORED_HEIGHT
            and width > height
            and width <= height * MAX_RATIO)


def strip_html(text):
    """HTML-Tags und Entities aus einem extmetadata-Wert entfernen.

    Commons hängt an manche Autor-Angaben "( talk page )" an, wenn der
    Benutzername verlinkt ist - das ist kein Teil des Namens.
    """
    without_tags = re.sub(r"<[^>]+>", " ", text or "")
    cleaned = html.unescape(without_tags)
    cleaned = re.sub(r"\s*\(\s*talk(\s+page)?\s*\)", "", cleaned, flags=re.IGNORECASE)
    return re.sub(r"\s+", " ", cleaned).strip()


def name_variants(name):
    """Schreibweisen des Airline-Namens für den Titel-Abgleich.

    "United Airlines" -> ["united airlines", "united"], "Lufthansa" ->
    ["lufthansa"]. Generische Wörter (airlines, air, ag, ...) fallen weg.
    """
    normalized = re.sub(r"\s+", " ", name).strip().lower()
    variants = [normalized]
    stripped = [
        word for word in normalized.split()
        if word not in GENERIC_NAME_WORDS
    ]
    if stripped and " ".join(stripped) != normalized:
        variants.append(" ".join(stripped))
    return variants


def other_airline_pattern(catalog, self_iata, self_variants=()):
    """Regex der Namen aller anderen Katalog-Airlines.

    Nennt der Titel eines Treffers eine andere Airline (z. B. "Thunder
    Airlines" statt Bearskin oder "easyJet" statt Lufttransport), ist das Foto
    falsch zugeordnet - solche Treffer werden verworfen. Nur unterscheidende
    Varianten ab einer Mindestlänge, damit Kurznamen ("Go", "US") nicht
    jede zweite Zeile treffen. Markenyamilien fallen nicht darunter: Steht
    der Fremdname in einem eigenen Namen ("Jetstar" in "Jetstar Asia
    Airways", "Lufthansa" in "Lufthansa City Airlines"), ist das Flugzeug
    derselben Familie zugehörig.
    """
    variants = set()
    for iata, entry in catalog.items():
        if iata == self_iata:
            continue
        for variant in name_variants(entry.get("name") or ""):
            stripped = variant.split()
            if len(variant) >= 10 or (len(stripped) == 1 and len(variant) >= 5):
                if any(variant != s and variant in s for s in self_variants):
                    continue
                variants.add(variant)
    if not variants:
        return None
    ordered = sorted(variants, key=len, reverse=True)
    return re.compile("|".join(re.escape(v) for v in ordered), re.IGNORECASE)


def candidate_score(page, info, variants, other_airlines,
                    allow_special=True, primary=False):
    """Wertung eines Commons-Treffers; None, wenn er die harten Filter nicht
    besteht (kein JPEG/PNG, zu klein, Hochformat, Lizenz nicht erlaubt, Autor
    unbekannt, kein Thumbnail, keine Lizenz-URL ermittelbar, Airline kommt
    nirgends vor oder der Titel nennt eine andere Airline). Rückgabe
    (score, license_url, date, special).

    `date` ist das Aufnahmedatum (ISO) oder None, `special` markiert eine
    Sonderlackierung. Die Wertung bevorzugt junge Fotos (siehe recency_score):
    Die Bemalung soll so aktuell wie möglich sein. Eine Sonderlackierung ist
    fürs Einzelfoto ausgeschlossen (`allow_special=False`), in der Galerie
    willkommen und gibt dort einen Zuschlag. Das Einzelfoto (`primary=True`)
    bekommt einen Zuschlag, wenn der Titel einen Flaggschiff-Typ nennt.
    """
    if info.get("mime") not in ("image/jpeg", "image/png"):
        return None
    width = int(info.get("width") or 0)
    height = int(info.get("height") or 0)
    if (width < MIN_WIDTH or height < MIN_HEIGHT or width <= height
            or width > height * MAX_RATIO):
        return None
    metadata = info.get("extmetadata") or {}
    license_short = (metadata.get("LicenseShortName") or {}).get("value", "").strip()
    if not license_allowed(license_short):
        return None
    # Commons liefert nicht immer ein LicenseUrl (Public Domain oft nicht);
    # ohne eine Lizenz-URL ist die Namensnennung nicht vollständig.
    license_url = normalize_license_url(
        (metadata.get("LicenseUrl") or {}).get("value", "").strip()
    )
    if not license_url:
        license_url = license_url_for(license_short)
    if not license_url.startswith("http"):
        return None
    author = strip_html((metadata.get("Artist") or {}).get("value", ""))
    if not author or author.lower().startswith("unknown"):
        return None
    if not info.get("thumburl"):
        return None

    title = page.get("title", "")
    if title.startswith("File:"):
        title = title[len("File:"):]
    description = strip_html((metadata.get("ImageDescription") or {}).get("value", ""))
    title_lower = title.lower()
    text = (title + " " + description).lower()

    # Die Airline muss im Titel oder in der Beschreibung vorkommen; sonst ist
    # der Treffer eine Fehlzuordnung (über ein loses Wort der Suche gelandet).
    if not any(variant in title_lower for variant in variants) and \
            not any(variant in description.lower() for variant in variants):
        return None
    # Nennt der Titel eine andere Airline, ist das Flugzeug deren - auch wenn
    # die Beschreibung die gesuchte Airline erwähnt.
    if other_airlines is not None and other_airlines.search(title_lower):
        return None
    # Kein Foto einer Außenaufnahme (Modell, Cockpit, Kabine, Karte, Unfall):
    # siehe HARD_NEGATIVE_RES.
    if any(pattern.search(text) for pattern in HARD_NEGATIVE_RES):
        return None

    # Sonderlackierung erkennen: die Allianz-Namen nur im Titel (die
    # Beschreibung nennt die Allianz oft bloss als Mitgliedschaft), die übrigen
    # Merkmale in Titel und Beschreibung.
    special = any(pattern.search(text) for pattern in SPECIAL_LIVERY_RES) or \
        any(pattern.search(title_lower) for pattern in SPECIAL_LIVERY_TITLE_RES)
    if special and not allow_special:
        return None

    date = parse_photo_date(metadata, info, title)

    score = 0.0
    if any(variant in title_lower for variant in variants):
        score += 100
    else:
        score += 30
    if AIRCRAFT_TYPE_RE.search(title_lower):
        score += 40
    elif AIRCRAFT_WORD_RE.search(title_lower):
        score += 20
    score += min(width, 2400) / 2400.0 * 30
    ratio = width / height
    if 1.3 <= ratio <= 2.0:
        score += 20
    elif 1.15 <= ratio < 1.3 or 2.0 < ratio <= 2.4:
        score += 8
    else:
        score -= 10
    score += license_score(license_short)
    for pattern, malus in NEGATIVE_WORDS:
        if pattern.search(text):
            score += malus
    # Aktualität: je jünger das Foto, desto besser - die Bemalung soll so
    # neu wie möglich sein (Ziel: höchstens etwa drei Jahre).
    score += recency_score(date)
    if special:
        # In der Galerie sind Sonderlackierungen willkommen.
        score += 40
    if primary and FLAGSHIP_TYPE_RE.search(title_lower):
        # Einzelfoto: lieber die Standardlackierung auf einem Flaggschiff-Typ.
        score += 25
    return score, license_url, date, special


def search_commons(query):
    """Sucht im Dateinamensraum, liefert (page, imageinfo)-Paare."""
    data = http_get_json({
        "action": "query",
        "generator": "search",
        "gsrsearch": query,
        "gsrnamespace": "6",
        "gsrlimit": str(SEARCH_LIMIT),
        "prop": "imageinfo",
        "iiprop": "url|size|extmetadata|mime|timestamp",
        "iiurlwidth": str(THUMB_WIDTH),
        "format": "json",
        "formatversion": "2",
    })
    results = []
    for page in (data.get("query") or {}).get("pages", []):
        infos = page.get("imageinfo") or []
        if infos:
            results.append((page, infos[0]))
    return results


def query_ladder(name):
    """Suchanfragen von spezifisch nach allgemein."""
    quoted = '"%s"' % name.replace('"', "").strip()
    return [
        quoted + " aircraft",
        name.replace('"', "").strip() + " aircraft",
        quoted,
    ]


def special_query_ladder(name):
    """Suchanfragen nach Sonderlackierungen (für die Galerie-Fotos).

    Die Galerie einer Airline darf neben der Standardlackierung auch
    Sonderlackierungen zeigen; diese Stufen surfacen sie gezielt, weil sie in
    der allgemeinen Suche zwischen den vielen Standardfotos untergehen.
    """
    quoted = '"%s"' % name.replace('"', "").strip()
    return [
        quoted + " special livery aircraft",
        quoted + " special scheme aircraft",
        quoted + " \"star alliance\" aircraft",
        quoted + " retro livery aircraft",
    ]


def convert_to_webp(data, target):
    """Bilddaten nach webp; Rückgabe (Breite, Höhe, verkleinert).

    EXIF-Drehung wird angewendet, Transparenz auf Weiß aufgelöst, die Breite
    auf THUMB_WIDTH begrenzt. Eine reine Formatkonvertierung gilt nicht als
    Änderung im Sinn von CC BY; eine Verkleinerung schon (siehe `modified`).
    """
    with Image.open(io.BytesIO(data)) as image:
        image = ImageOps.exif_transpose(image)
        if image.mode in ("RGBA", "LA") or (
            image.mode == "P" and "transparency" in image.info
        ):
            image = image.convert("RGBA")
            background = Image.new("RGB", image.size, (255, 255, 255))
            background.paste(image, mask=image.split()[-1])
            image = background
        else:
            image = image.convert("RGB")
        resized = False
        if image.width > THUMB_WIDTH:
            height = max(1, round(image.height * THUMB_WIDTH / image.width))
            image = image.resize((THUMB_WIDTH, height), Image.LANCZOS)
            resized = True
        image.save(target, "WEBP", quality=WEBP_QUALITY, method=6)
    return image.size[0], image.size[1], resized


def source_url(title):
    """URL der Commons-Dateiseite aus dem Titel ohne 'File:'-Präfix."""
    return "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(
        title.replace(" ", "_")
    )


def migrate_schema(photos):
    """Alte Einträge (flache Felder) ins Galerie-Schema heben.

    Jeder Eintrag bekommt ein `photos`-Array; das bisherige Einzelfoto ist das
    erste Element und verweist auf `<IATA>.webp`. Rückgabe: Anzahl der
    gehobenen Einträge.
    """
    migrated = 0
    for iata, entry in photos.items():
        if "photos" in entry:
            continue
        photo = {
            "file": iata + ".webp",
            "author": entry.get("author", ""),
            "license": entry.get("license", ""),
            "licenseUrl": entry.get("licenseUrl", ""),
            "sourceUrl": entry.get("sourceUrl", ""),
            "title": entry.get("title", ""),
            "modified": entry.get("modified", False),
            "width": entry.get("width", 0),
            "height": entry.get("height", 0),
        }
        new_entry = {"icao": entry.get("icao", ""), "photos": [photo]}
        if entry.get("manual"):
            new_entry["manual"] = True
        photos[iata] = new_entry
        migrated += 1
    return migrated


def photo_filename(iata, position):
    """Dateiname des Fotos an Position `position` (1-basiert) der Galerie."""
    return iata + ".webp" if position == 1 else "%s_%d.webp" % (iata, position)


def base_title(title):
    """Titel ohne Erweiterung und Crop-Endung, kleingeschrieben.

    'X (crop).jpg' und 'X.jpg' sind dasselbe Foto - in einer Galerie darf es
    nicht zweimal landen. Nummernierte Folgen ('X (2).jpg') sind dagegen
    verschiedene Fotos und bleiben erlaubt.
    """
    name = (title or "").strip()
    name = re.sub(r"\.(jpe?g|png|webp)$", "", name, flags=re.IGNORECASE)
    name = re.sub(r"\s*\(?\s*crop(?:ped)?\s*\)?\s*$", "", name, flags=re.IGNORECASE)
    return name.strip().lower()


def find_photos(iata, names, catalog, delay, want=1, exclude_urls=(),
                allow_special=True, primary=False, prefer_special=False,
                gather_all=False):
    """Sucht bis zu `want` verschiedene Fotos einer Airline.

    Rückgabe: Liste (page, imageinfo, score, license_url, date, special),
    absteigend nach Wertung. `exclude_urls` sind Quell-URLs schon vorhandener
    Fotos (einer Galerie) - dieselbe Datei landet nicht zweimal. `names` ist
    die Liste der Namen, die probiert werden (Katalogname, dann der englische
    Wikipedia-Titel als Fallback für veraltete Katalognamen).

    `allow_special=False` sortiert Sonderlackierungen aus (Einzelfoto: dort
    gehört die Standardlackierung hin), `primary=True` gibt Flaggschiff-Typen
    einen Zuschlag. `prefer_special=True` hängt Suchstufen für
    Sonderlackierungen an die Anfragereihe an (Galerie: Sonderlackierungen sind
    willkommen).

    Ohne `gather_all` gewinnt die erste Suchstufe mit einem brauchbaren
    Treffer (schneller Lauf beim Neuaufbau). Mit `gather_all` werden alle
    Stufen gesammelt und gemeinsam gewertet - das neuste Foto kann in jeder
    Stufe stehen, deshalb lohnt sich das volle Durchsuchen beim Refresh.
    Crop-Varianten desselben Fotos ('X (crop).jpg') landen nie zweimal im
    Ergebnis (s siehe [base_title]).
    """
    exclude = set(exclude_urls)
    self_variants = [v for name in names for v in name_variants(name)]
    other_airlines = other_airline_pattern(catalog, iata.upper(), self_variants)

    def title_of(page):
        title = page.get("title", "")
        if title.startswith("File:"):
            title = title[len("File:"):]
        return title

    def collect(variants, query, pool):
        """Sammelt die brauchbaren Treffer einer Suchanfrage in `pool`."""
        added = 0
        for page, info in search_commons(query):
            scored = candidate_score(page, info, variants, other_airlines,
                                     allow_special=allow_special,
                                     primary=primary)
            if scored is None:
                continue
            score, license_url, date, special = scored
            url = source_url(title_of(page))
            if url in exclude:
                continue
            key = (-score, -int(info.get("width") or 0), title_of(page))
            pool.append((key, page, info, score, license_url, date, special))
            # Schon beim Sammeln merken: Dieselbe Datei darf nicht über
            # zwei Suchstufen hinweg zweimal in den Pool geraten.
            exclude.add(url)
            added += 1
        return added

    stages = []
    for name in names:
        variants = name_variants(name)
        for query in query_ladder(name):
            stages.append((variants, query))
        if prefer_special:
            for query in special_query_ladder(name):
                stages.append((variants, query))

    found = []
    bases = set()
    if gather_all:
        pool = []
        for variants, query in stages:
            collect(variants, query, pool)
            time.sleep(delay)
        pool.sort(key=lambda item: item[0])
        for _, page, info, score, license_url, date, special in pool:
            if len(found) >= want:
                break
            title = title_of(page)
            base = base_title(title)
            if base in bases:
                continue
            bases.add(base)
            found.append((page, info, score, license_url, date, special))
            exclude.add(source_url(title))
        return found

    for variants, query in stages:
        if len(found) >= want:
            break
        pool = []
        added = collect(variants, query, pool)
        pool.sort(key=lambda item: item[0])
        for _, page, info, score, license_url, date, special in pool:
            if len(found) >= want:
                break
            title = title_of(page)
            base = base_title(title)
            if base in bases:
                continue
            bases.add(base)
            found.append((page, info, score, license_url, date, special))
            exclude.add(source_url(title))
        # Pause zwischen API-Abfragen; die letzte erfolgreiche Stufe vor
        # dem Rücksprung braucht keine.
        if added == 0 or len(found) < want:
            time.sleep(delay)
    return found


def install_manual(manual_dir, catalog, photos_dir, photos):
    """Installiert kuratierte Fotos aus photos_manual/ (gewinnen immer)."""
    if not os.path.isdir(manual_dir):
        return 0
    count = 0
    for name in sorted(os.listdir(manual_dir)):
        match = MANUAL_IMAGE_RE.match(name)
        if not match:
            continue
        iata = match.group(1).upper()
        entry = catalog.get(iata)
        if not entry or not entry.get("icao"):
            print("  Warnung: %s (%s) ist nicht mit ICAO im Katalog, wird übergangen"
                  % (iata, name))
            continue
        meta_path = os.path.join(manual_dir, iata + ".json")
        if not os.path.isfile(meta_path):
            print("  Warnung: %s hat keine %s.json-Attribution, wird übergangen"
                  % (name, iata))
            continue
        with open(meta_path, encoding="utf-8") as handle:
            meta = json.load(handle)
        missing = [
            field for field in ("author", "license", "licenseUrl", "sourceUrl")
            if not meta.get(field)
        ]
        if missing:
            print("  Warnung: %s.json fehlen Felder (%s), wird übergangen"
                  % (iata, ", ".join(missing)))
            continue
        if not license_allowed(meta["license"]):
            print("  Warnung: %s.json hat eine nicht erlaubte Lizenz (%s), wird übergangen"
                  % (iata, meta["license"]))
            continue
        target = os.path.join(photos_dir, iata + ".webp")
        with open(os.path.join(manual_dir, name), "rb") as handle:
            data = handle.read()
        width, height, resized = convert_to_webp(data, target)
        photos[iata] = {
            "icao": entry["icao"].upper(),
            "photos": [{
                "file": iata + ".webp",
                "author": meta["author"],
                "license": meta["license"],
                "licenseUrl": meta["licenseUrl"],
                "sourceUrl": meta["sourceUrl"],
                "title": meta.get("title", ""),
                "modified": bool(meta.get("modified", resized)),
                "width": width,
                "height": height,
            }],
            # Markierung, damit main() verwaiste manuelle Einträge entfernt,
            # wenn die Datei aus photos_manual/ verschwindet.
            "manual": True,
        }
        print("  manuell: %s <- %s (%dx%d, %s)" % (iata, name, width, height, meta["license"]))
        count += 1
    return count


def write_photos(photos, photos_dir):
    """Schreibt photos.json (kompakt, sortiert, wie airline_facts.json)."""
    with open(os.path.join(photos_dir, "photos.json"), "w", encoding="utf-8") as handle:
        json.dump(photos, handle, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        handle.write("\n")


def validate(photos, photos_dir):
    """Prüft die gesamte Zuordnung; False bei Fehlern."""
    ok = True
    referenced = set()
    total = 0
    for iata in sorted(photos):
        entry = photos[iata]
        gallery = entry.get("photos") or []
        if not gallery:
            print("  Fehler: %s hat kein Foto im photos-Array" % iata)
            ok = False
            continue
        for photo in gallery:
            filename = photo.get("file", "")
            referenced.add(filename)
            total += 1
            path = os.path.join(photos_dir, filename)
            if not os.path.isfile(path):
                print("  Fehler: %s verweist auf %s, Datei fehlt" % (iata, filename))
                ok = False
                continue
            with open(path, "rb") as handle:
                header = handle.read(16)
            if header[:4] != b"RIFF" or header[8:12] != b"WEBP":
                print("  Fehler: %s ist kein WebP" % filename)
                ok = False
            if os.path.getsize(path) < MIN_FILE_BYTES:
                print("  Fehler: %s ist verdächtig klein (%d B)"
                      % (filename, os.path.getsize(path)))
                ok = False
            missing = [
                field for field in ("author", "license", "licenseUrl", "sourceUrl")
                if not photo.get(field)
            ]
            if missing:
                print("  Fehler: %s (%s) fehlen Attribution-Felder (%s)"
                      % (iata, filename, ", ".join(missing)))
                ok = False
            if not license_allowed(photo.get("license", "")):
                print("  Fehler: %s (%s) hat eine nicht erlaubte Lizenz (%s)"
                      % (iata, filename, photo.get("license")))
                ok = False
            if not stored_dimensions_ok(photo):
                print("  Fehler: %s (%s) Maße außerhalb der Grenzen: %s x %s"
                      % (iata, filename, photo.get("width"), photo.get("height")))
                ok = False
    orphans = sorted(
        f for f in os.listdir(photos_dir)
        if f.endswith(".webp") and f not in referenced
    )
    if orphans:
        print("  Hinweis: %d nicht referenzierte Datei(en): %s"
              % (len(orphans), ", ".join(orphans)))
    total_bytes = sum(
        os.path.getsize(os.path.join(photos_dir, f))
        for f in os.listdir(photos_dir) if f.endswith(".webp")
    )
    print("Prüfung: %d Fotos, %.2f MB, %d verwaiste Dateien"
          % (total, total_bytes / 1048576.0, len(orphans)))
    return ok


def title_from_source_url(url):
    """Dateititel aus einer Commons-Quell-URL (mit 'File:'-Präfix)."""
    if not url or "/wiki/File:" not in url:
        return None
    return urllib.parse.unquote(url.rsplit("/wiki/File:", 1)[-1])


def normalize_title(title):
    return (title or "").replace(" ", "_")


def resolve_stored_dates(photos, delay):
    """Ermittelt das Aufnahmedatum der gespeicherten Fotos bei Commons.

    Die bisherigen Einträge tragen kein Datum; für --refresh wird es
    nachgezogen (gebündelt je 50 Dateien), damit ein gespeichertes Foto nur
    durch ein nachweislich jüngeres ersetzt wird. Manuelle Fotos bleiben
    aussen vor - sie werden nie ersetzt.
    """
    pending = {}
    for iata, entry in photos.items():
        if entry.get("manual"):
            continue
        for photo in entry.get("photos") or []:
            title = title_from_source_url(photo.get("sourceUrl", ""))
            if title:
                pending.setdefault(normalize_title(title), []).append(photo)
    titles = sorted(pending)
    resolved = 0
    for start in range(0, len(titles), 50):
        chunk = titles[start:start + 50]
        data = http_get_json({
            "action": "query",
            "prop": "imageinfo",
            "iiprop": "extmetadata|timestamp",
            "iiextmetadatafilter": "DateTimeOriginal",
            "redirects": "1",
            "titles": "|".join("File:" + title for title in chunk),
            "format": "json",
            "formatversion": "2",
        })
        info_by_title = {}
        for page in (data.get("query") or {}).get("pages", []):
            infos = page.get("imageinfo") or []
            if infos:
                title = page.get("title", "")
                if title.startswith("File:"):
                    title = title[len("File:"):]
                info_by_title[normalize_title(title)] = infos[0]
        for title in chunk:
            info = info_by_title.get(title)
            if not info:
                continue
            date = parse_photo_date(info.get("extmetadata") or {}, info, title)
            if not date:
                continue
            for photo in pending[title]:
                photo["date"] = date
                resolved += 1
        time.sleep(delay)
    print("Datum nachgezogen: %d Fotos" % resolved)
    return resolved


def download_photo(photos_dir, iata, page, info, license_url, date, position):
    """Lädt ein Foto herunter, konvertiert es und liefert den photos.json-Eintrag.

    `position` ist die 1-basierte Stelle in der Galerie und bestimmt den
    Dateinamen (`<IATA>.webp`, `<IATA>_2.webp`, ...). None bei einem
    fehlgeschlagenen Download.
    """
    metadata = info.get("extmetadata") or {}
    title = page.get("title", "")
    if title.startswith("File:"):
        title = title[len("File:"):]
    author = strip_html((metadata.get("Artist") or {}).get("value", ""))
    license_short = (metadata.get("LicenseShortName") or {}).get("value", "").strip()
    target = os.path.join(photos_dir, photo_filename(iata, position))
    try:
        data = download(info["thumburl"])
    except RuntimeError as error:
        print("%s: Download fehlgeschlagen (%s)" % (iata, error))
        return None
    width, height, resized = convert_to_webp(data, target)
    return {
        "file": photo_filename(iata, position),
        "author": author,
        "license": license_short,
        "licenseUrl": license_url,
        "sourceUrl": source_url(title),
        "title": title,
        "date": date or "",
        "modified": resized,
        "width": width,
        "height": height,
    }


def refresh_existing(photos, catalog, facts, photos_dir, delay, only=None):
    """Ersetzt gespeicherte Fotos durch jüngere (--refresh).

    Pro Airline mit Foto wird das beste neue Foto gesucht: fürs erste Foto
    eine Standardlackierung auf einem Flaggschiff-Typ (Sonderlackierungen nur,
    wenn es gar keine Standardlackierung gibt), für die Galerie bevorzugt
    Sonderlackierungen. Ersetzt wird ein gespeichertes Foto nur, wenn das neue
    nachweislich jünger ist (siehe is_better); sonst bleibt es stehen.
    Ausnahme: Ein gespeichertes Foto, dessen Maße die Test-Grenzen verletzen
    (siehe stored_dimensions_ok), wird unabhängig vom Alter durch das beste
    gültige Angebot ersetzt. Die Galerie wird bis drei Fotos aufgefüllt,
    fehlende Slots bekommen Sonderlackierungen zuerst. `only` beschränkt den
    Lauf auf gegebene IATA-Codes (--only).
    """
    replaced = 0
    for iata in sorted(photos):
        if only is not None and iata not in only:
            continue
        entry = photos[iata]
        if entry.get("manual") or iata in NO_PHOTO:
            continue
        stored = entry.get("photos") or []
        catalog_entry = catalog.get(iata)
        if not stored or not catalog_entry:
            continue
        names = [catalog_entry.get("name") or iata]
        wiki = wiki_title((facts.get(iata) or {}).get("wiki"))
        if wiki and wiki.lower() != names[0].lower():
            names.append(wiki)
        group = (facts.get(iata) or {}).get("group")
        is_gallery = len(stored) > 1 or group == GALLERY_GROUP
        stored_urls = {photo.get("sourceUrl", "") for photo in stored}
        exclude = set(stored_urls)
        new_photos = [None] * len(stored)

        # Erstes Foto: Standardlackierung bevorzugt; ersetzen, wenn das
        # neue Foto jünger ist als das gespeicherte.
        pool = find_photos(iata, names, catalog, delay, want=3,
                           exclude_urls=exclude, allow_special=False,
                           primary=True, gather_all=True)
        if not pool:
            # Gar keine Standardlackierung im Angebot: Dann lieber eine
            # Sonderlackierung als ein veraltetes oder gar kein Foto.
            pool = find_photos(iata, names, catalog, delay, want=3,
                               exclude_urls=exclude, allow_special=True,
                               primary=True, gather_all=True)
        for cand in pool:
            if (is_better(cand[4], stored[0].get("date"))
                    or not stored_dimensions_ok(stored[0])):
                record = download_photo(photos_dir, iata, cand[0], cand[1],
                                        cand[3], cand[4], 1)
                if record:
                    new_photos[0] = record
                    exclude.add(record["sourceUrl"])
                    print("%s: neues Foto (%s, %s)"
                          % (iata, record["date"], record["title"][:50]))
                break

        if is_gallery:
            # Galerie-Slots: ersetzen, wenn jünger; leere Slots bis drei
            # auffüllen, Sonderlackierungen zuerst.
            extra_pool = find_photos(iata, names, catalog, delay, want=6,
                                     exclude_urls=exclude, allow_special=True,
                                     prefer_special=True, gather_all=True)
            pool = list(extra_pool)
            for index in range(1, len(stored)):
                chosen = None
                for cand in pool:
                    if (is_better(cand[4], stored[index].get("date"))
                            or not stored_dimensions_ok(stored[index])):
                        chosen = cand
                        break
                if chosen is None:
                    continue
                pool.remove(chosen)
                record = download_photo(photos_dir, iata, chosen[0], chosen[1],
                                        chosen[3], chosen[4], index + 1)
                if record:
                    new_photos[index] = record
                    exclude.add(record["sourceUrl"])
                    print("%s: neues Galerie-Foto %d (%s, %s)"
                          % (iata, index + 1, record["date"], record["title"][:50]))
            if group == GALLERY_GROUP:
                fill = [c for c in pool if c[5]] + [c for c in pool if not c[5]]
                while len(new_photos) < GALLERY_PHOTOS and fill:
                    cand = fill.pop(0)
                    record = download_photo(photos_dir, iata, cand[0], cand[1],
                                            cand[3], cand[4], len(new_photos) + 1)
                    if record:
                        new_photos.append(record)
                        print("%s: Galerie aufgefüllt (%s, %s)"
                              % (iata, record["date"], record["title"][:50]))

        result = [
            new_photos[index] if new_photos[index] is not None else stored[index]
            for index in range(len(stored))
        ] + new_photos[len(stored):]
        if result != stored:
            entry["photos"] = result
            replaced += 1
            # Nach jeder Airline speichern: ein Abbruch verliert höchstens eine.
            write_photos(photos, photos_dir)
    return replaced


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", default=CATALOG_PATH)
    parser.add_argument("--facts", default=FACTS_PATH)
    parser.add_argument("--photos-dir", default=PHOTOS_DIR)
    parser.add_argument("--manual", default=MANUAL_DIR)
    parser.add_argument("--only", help="Komma-getrennte IATA-Codes statt Top 250")
    parser.add_argument("--limit", type=int, default=TOP_N)
    parser.add_argument("--force", action="store_true", help="Vorhandene neu laden")
    parser.add_argument("--refresh", action="store_true",
                        help="Vorhandene Fotos prüfen und durch jüngere ersetzen")
    parser.add_argument("--delay", type=float, default=0.5,
                        help="Pause zwischen API-Abfragen in Sekunden (Standard 0,5)")
    parser.add_argument("--list", action="store_true", help="Top-Liste nur anzeigen")
    parser.add_argument("--no-gallery", action="store_true",
                        help="Galerie-Ergänzung (Lufthansa Group) überspringen")
    args = parser.parse_args()

    photos_dir = args.photos_dir
    photos_json = os.path.join(photos_dir, "photos.json")

    catalog = load_catalog(args.catalog)
    facts = load_facts(args.facts)

    if args.only:
        codes = [code.strip().upper() for code in args.only.split(",") if code.strip()]
        targets = []
        for iata in codes:
            entry = catalog.get(iata)
            if not entry:
                print("Fehler: %s ist nicht im Katalog" % iata)
                return 1
            record = facts.get(iata, {})
            targets.append((
                record.get("fleet", 0),
                iata,
                entry.get("name") or iata,
                wiki_title(record.get("wiki")),
            ))
    else:
        targets = top_airlines(catalog, facts, args.limit)

    if args.list:
        for rank, (fleet, iata, name, _) in enumerate(targets, 1):
            print("%3d  %s  %4d  %s" % (rank, iata, fleet, name))
        return 0

    print("Ziel: %d Airlines" % len(targets))

    if os.path.isfile(photos_json):
        with open(photos_json, encoding="utf-8") as handle:
            photos = json.load(handle)
        # Altes Schema (flache Felder je Airline) ins Galerie-Schema heben.
        migrated = migrate_schema(photos)
        if migrated:
            print("Schema gehoben: %d Einträge" % migrated)
        # Lizenz-URL bei älteren Einträgen nachziehen bzw. auf https heben:
        # Public Domain liefert Commons oft keine, ältere Dateien ein http-URL.
        for entry in photos.values():
            for photo in entry.get("photos", []):
                if not photo.get("licenseUrl"):
                    photo["licenseUrl"] = license_url_for(photo.get("license", ""))
                else:
                    photo["licenseUrl"] = normalize_license_url(photo["licenseUrl"])
        # Airlines ohne brauchbares Foto: Eintrag und Dateien entfernen, falls
        # ein früherer Lauf (mit schwächerer Suche) doch eines installiert hat.
        for iata in [i for i in photos if i in NO_PHOTO]:
            print("Entfernt (NO_PHOTO): %s" % iata)
            del photos[iata]
            for name in os.listdir(photos_dir):
                if name == iata + ".webp" or (
                    name.startswith(iata + "_") and name.endswith(".webp")
                ):
                    os.remove(os.path.join(photos_dir, name))
    else:
        photos = {}

    # Verwaiste manuelle Einträge entfernen: Datei in photos_manual/ weg,
    # Eintrag und Bild landen nicht als Leiche im Asset.
    manual_codes = set()
    if os.path.isdir(args.manual):
        for name in os.listdir(args.manual):
            match = MANUAL_IMAGE_RE.match(name)
            if match:
                manual_codes.add(match.group(1).upper())
    for iata in [i for i, e in photos.items() if e.get("manual") and i not in manual_codes]:
        print("Manuell entfernt: %s (keine Datei mehr in %s)" % (iata, args.manual))
        del photos[iata]
        stale = os.path.join(photos_dir, iata + ".webp")
        if os.path.isfile(stale):
            os.remove(stale)

    prefer_ipv4()
    os.makedirs(photos_dir, exist_ok=True)

    manual_count = install_manual(args.manual, catalog, photos_dir, photos)
    if manual_count:
        print("Manuell: %d Foto(s) installiert" % manual_count)

    if args.refresh:
        # --refresh: Die vorhandenen Fotos auf Aktualität prüfen und durch
        # jüngere ersetzen (nur wenn nachweislich jünger, siehe is_better).
        # Fehlende Airlines der Top 250 lädt der Lauf unten wie gewohnt neu.
        resolve_stored_dates(photos, args.delay)
        write_photos(photos, photos_dir)
        only = None
        if args.only:
            only = {code.strip().upper() for code in args.only.split(",") if code.strip()}
        refreshed = refresh_existing(photos, catalog, facts, photos_dir,
                                     args.delay, only=only)
        print("Refresh: %d Airlines aktualisiert" % refreshed)
        write_photos(photos, photos_dir)

    downloaded = 0
    skipped = 0
    failed = []
    for fleet, iata, name, wiki in targets:
        if iata in NO_PHOTO:
            # Siehe NO_PHOTO: die Suche findet nur falsche Fotos.
            skipped += 1
            continue
        target = os.path.join(photos_dir, iata + ".webp")
        if not args.force and iata in photos and os.path.isfile(target):
            skipped += 1
            continue
        # Katalognamen können veraltet sein (EU: "Empresa Ecuatoriana De
        # Aviacion" statt Chengdu Airlines) - der Wiki-Titel ist der Fallback.
        names = [name]
        if wiki and wiki.lower() != name.lower():
            names.append(wiki)
        found = []
        try:
            # Einzelfoto: Standardlackierung bevorzugt (Sonderlackierungen
            # nur, wenn es gar keine gibt), möglichst jung, Flaggschiff-Typ.
            found = find_photos(iata, names, catalog, args.delay, want=1,
                                allow_special=False, primary=True,
                                gather_all=args.refresh)
            if not found:
                found = find_photos(iata, names, catalog, args.delay, want=1,
                                    allow_special=True, primary=True,
                                    gather_all=args.refresh)
        except RuntimeError as error:
            print("%s: Suche fehlgeschlagen (%s)" % (iata, error))
        if not found:
            print("%s: kein brauchbares Foto gefunden (%s)" % (iata, name))
            failed.append(iata)
            time.sleep(args.delay)
            continue
        page, info, score, license_url, date, special = found[0]
        record = download_photo(photos_dir, iata, page, info, license_url, date, 1)
        if record is None:
            failed.append(iata)
            time.sleep(args.delay)
            continue
        photos[iata] = {
            "icao": (catalog[iata].get("icao") or "").upper(),
            "photos": [record],
        }
        downloaded += 1
        print("%s: %s (%dx%d, %.0f, %s, %s, %s)"
              % (iata, record["title"][:60], record["width"], record["height"],
                 score, record["license"], record["author"][:30], record["date"]))
        # Nach jeder Airline speichern: ein Abbruch verliert höchstens eine.
        write_photos(photos, photos_dir)
        time.sleep(args.delay)

    # Galerie: Airlines der Lufthansa Group bekommen bis zu GALLERY_PHOTOS
    # Fotos, in der App seitlich wischbar. Inkrementell - Airlines mit genug
    # Fotos werden übersprungen.
    gallery_added = 0
    gallery_failed = []
    if not args.no_gallery:
        gallery_targets = [
            (fleet, iata, name, wiki)
            for fleet, iata, name, wiki in targets
            if facts.get(iata, {}).get("group") == GALLERY_GROUP
            and iata not in NO_PHOTO
        ]
        for fleet, iata, name, wiki in gallery_targets:
            entry = photos.get(iata)
            if not entry or entry.get("manual"):
                continue
            gallery = entry.setdefault("photos", [])
            if len(gallery) >= GALLERY_PHOTOS:
                continue
            exclude_urls = {photo.get("sourceUrl", "") for photo in gallery}
            names = [name]
            if wiki and wiki.lower() != name.lower():
                names.append(wiki)
            want = GALLERY_PHOTOS - len(gallery)
            found = []
            try:
                # Galerie-Ergänzung: Sonderlackierungen sind willkommen und
                # werden bevorzugt (Standardlackierung steht schon an erster
                # Stelle), möglichst junge Fotos.
                found = find_photos(
                    iata, names, catalog, args.delay, want=want,
                    exclude_urls=exclude_urls,
                    allow_special=True, prefer_special=True, gather_all=True,
                )
            except RuntimeError as error:
                print("%s: Galerie-Suche fehlgeschlagen (%s)" % (iata, error))
            added = 0
            for page, info, score, license_url, date, special in found:
                position = len(gallery) + 1
                record = download_photo(photos_dir, iata, page, info,
                                        license_url, date, position)
                if record is None:
                    time.sleep(args.delay)
                    continue
                gallery.append(record)
                added += 1
                print("%s: Galerie %d/%d: %s (%dx%d, %.0f, %s, %s, %s)"
                      % (iata, position, GALLERY_PHOTOS, record["title"][:50],
                         record["width"], record["height"], score,
                         record["license"], record["author"][:30], record["date"]))
                # Nach jedem Foto speichern: ein Abbruch verliert höchstens eins.
                write_photos(photos, photos_dir)
                time.sleep(args.delay)
            if added:
                gallery_added += added
            elif len(gallery) < GALLERY_PHOTOS:
                gallery_failed.append(iata)
                print("%s: nur %d von %d Galerie-Fotos gefunden (%s)"
                      % (iata, len(gallery), GALLERY_PHOTOS, name))

    write_photos(photos, photos_dir)

    print("Fertig: %d geladen, %d übersprungen (vorhanden), %d ohne Foto, "
          "%d Galerie-Fotos ergänzt" % (downloaded, skipped, len(failed), gallery_added))
    if failed:
        print("Ohne Foto: %s" % ", ".join(failed))
    if gallery_failed:
        print("Galerie unvollständig: %s" % ", ".join(gallery_failed))
    print("Prüfung:")
    ok = validate(photos, photos_dir)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
