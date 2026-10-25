#!/usr/bin/env python3
"""Erzeugt die mitgelieferten Fotos der Flugzeugtypen (Hangar -> Typ).

Die Detailseite eines Flugzeugtyps zeigt über den Stammdaten ein Foto des
Typs. Die App lädt nichts nach; die Bilder werden deshalb einmalig hier
erzeugt und als WebP samt Attribution unter
`app/src/main/assets/aircraft/photos/` abgelegt:

    photos/A320.webp        Foto, benannt nach dem kanonischen Typ-Code
    photos/photos.json      Attribution je Typ-Code (Autor, Lizenz, Quell-URL)

Aufruf:

    python3 tools/build_aircraft_photos.py                  # alle 50 Typen
    python3 tools/build_aircraft_photos.py --list           # Liste nur anzeigen
    python3 tools/build_aircraft_photos.py --only A320,B737 # nur einzelne Typen
    python3 tools/build_aircraft_photos.py --force          # vorhandene neu laden

Die Typen kommen aus `aircraft_facts.json` (die 50 bekanntesten Familien,
derselbe Bestand wie die Stammdaten). Pro Typ gibt es **ein** Foto - im
Unterschied zu den Airlines keine Galerie. Benannt werden die Dateien nach
dem kanonischen Code aus `aircraft_facts.json` (A320, B737, Tu154, ...);
dieselben Codes loest die App ueber `AircraftFacts.resolve` auf.

**Quelle**: Wikimedia Commons (MediaWiki-API, Dateinamensraum), wie bei den
Airline-Fotos. Andere Foto-Seiten (planespotters.net, airliners.net)
verbieten das Kopieren ihrer Fotos ausdruecklich.

**Lizenz-Whitelist** (aus `LicenseShortName`): Public Domain (PD), CC0, CC BY
(alle Versionen) und GFDL. Nicht-kommerziell (NC) oder "Keine Bearbeitung"
(ND) fallen heraus. Die App zeigt unter dem Foto `© Autor · Lizenz ·
bearbeitet · Wikimedia Commons` und verlinkt die Lizenz sowie die
Commons-Dateiseite.

**Auswahl**: Pro Typ eine Anfragereihe (`TYPE_SEARCH_NAMES`: Anzeigename,
bekannte Kurzformen wie "MD-82" oder "Q400"), je Name bis zu drei
Suchanfragen (Name in Anfuehrungszeichen plus "aircraft", dann ohne
Anfuehrungszeichen, dann der Name allein), je bis zu 50 Treffer. Die erste
Stufe mit einem brauchbaren Treffer gewinnt. Harte Filter: JPEG/PNG,
mindestens 1200 x 700 px, Querformat bis maximal 2,5:1, Lizenz in der
Whitelist, Autor bekannt, der Typ kommt im **Titel** vor (`TYPE_MATCH` -
der Typ-Token, z. B. "MD-?8[0-8]" fuer die MD-80-Familie; Commons betitelt
Fotos eines Typs mit dem Typ, ein Vorkommen nur in der Beschreibung ist
meist eine beilaeufige Erwaehnung in einem Foto eines anderen Flugzeugs)
und kein Ausschlussgrund greift. Nur wenn ein Typ ueberhaupt keinen
Titel-Treffer hat, gilt als Fallback die Beschreibung. Harte Ausschluesse sind alles, was keine
Aussenaufnahme eines echten Flugzeugs ist: Modell (auch Massstabsangaben wie
1:400 und Modellhersteller wie Herpa), Die-Cast, Rendering, Cockpit, Kabine,
Karte, Unfallfoto, Militaer - und bei Comet/Trident das Himmelskoerper bzw.
Rakete/U-Boot gleichen Namens. Wertung: Typ-Token im Titel vor Beschreibung,
Anzeigename im Titel, Bildbreite, Seitenverhaeltnis 1,3-2,0, Lizenz
(PD/CC0 vor CC BY vor CC BY-SA/GFDL), Abzuege fuer Nahaufnahme/Triebwerk/
Sitze/Wingview, Klassenerlebnis. Gewaehlt wird die beste Aussenaufnahme des
Typs.

**Aktualitaet**: Wie bei den Airline-Fotos fliesst das Aufnahmedatum (EXIF
`DateTimeOriginal`, Ersatz: Datum im Titel, sonst Upload-Datum) in die
Wertung ein: Ein Foto, das hoechstens etwa drei Jahre alt ist, bekommt den
staerksten Zuschlag, darueber abgestuft bis acht Jahre. So landen moeglichst
neue, gut aufgeloste Fotos des Typs im Asset.

**Konvertierung**: Wie bei den Airline-Fotos - Download als Thumbnail (max.
1280 px breit), EXIF-Drehung, Alpha auf Weiss aufloesen, auf 1280 px Breite
begrenzen, WebP (Qualitaet 80). `modified` in photos.json ist true, wenn das
Bild verkleinert wurde.

**Kein Foto**: Findet die Suche fuer einen Typ nur falsche Treffer (Militaer,
fremder Typ, Modell), steht der Code in `NO_PHOTO` - der Typ bekommt kein
Foto, die Fotokachel bleibt in der App weg; ein Eintrag aus einem frueheren
Lauf wird entfernt.

**Manuelle Vorgabe**: Wie bei den Airline-Fotos (`photos_manual/`) koennen
Fotos von Hand kuratiert werden. Dafuer im Repo-Root `aircraft_photos_manual/`
anlegen:

    aircraft_photos_manual/A320.jpg
    aircraft_photos_manual/A320.json   {"author": "...", "license": "CC BY 4.0",
                                        "licenseUrl": "https://creativecommons.org/...",
                                        "sourceUrl": "https://commons.wikimedia.org/...",
                                        "title": "...", "modified": false}

Manuelle Dateien gewinnen immer gegen den Download; das Skript installiert
sie bei jedem Lauf und prueft danach die gesamte Zuordnung (fehlende
Dateien, unvollstaendige Attribution, nicht erlaubte Lizenz, Masse
ausserhalb der Grenzen, verwaiste Dateien, unbekannte Typ-Codes).
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
FACTS_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "aircraft", "aircraft_facts.json")
PHOTOS_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "aircraft", "photos")
MANUAL_DIR = os.path.join(ROOT, "aircraft_photos_manual")

COMMONS_API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "LogbookAircraftPhotos/1.0 (https://github.com/expressiveminute/Logbook)"

# Typen, fuer die die automatische Suche nur falsche Fotos findet - sie
# bekommen kein Foto (die Fotokachel bleibt in der App weg); ein Eintrag aus
# einem frueheren Lauf wird entfernt.
NO_PHOTO = set()

# Download als Thumbnail in dieser Breite; harte Masse fuer die Filter. Sie
# gelten fuer das Original; das gespeicherte Thumbnail (max. THUMB_WIDTH
# breit, nie hochgerechnet) muss Breite 1200-1280, Hoehe mindestens
# MIN_STORED_HEIGHT und Querformat bis MAX_RATIO erfuellen - so prueft es
# AircraftPhotosAssetTest. Daraus folgt die Panoramagrenze fuer das Original.
THUMB_WIDTH = 1280
MIN_WIDTH = 1200
MIN_HEIGHT = 700
MIN_STORED_HEIGHT = 500
MAX_RATIO = 2.5
WEBP_QUALITY = 80

REQUEST_TIMEOUT = 60
RETRIES = 4
SEARCH_LIMIT = 50

# Minimale Dateigroesse eines validen Fotos (leere/kaputte WebP-Dateien
# fallen sonst erst in der App auf).
MIN_FILE_BYTES = 5000

# Anfragereihe pro Typ: Anzeigename, dann bekannte Kurzformen. Die erste
# Stufe mit einem brauchbaren Treffer gewinnt.
TYPE_SEARCH_NAMES = {
    "A220": ["Airbus A220", "Bombardier C Series", "A220-300"],
    "A300": ["Airbus A300", "A300-600"],
    "A310": ["Airbus A310", "A310-300"],
    "A318": ["Airbus A318", "A318"],
    "A319": ["Airbus A319", "A319"],
    "A320": ["Airbus A320", "A320-200"],
    "A320neo": ["Airbus A320neo", "A320neo"],
    "A321": ["Airbus A321", "A321-200"],
    "A321neo": ["Airbus A321neo", "A321neo"],
    "A330": ["Airbus A330", "A330-300"],
    "A330neo": ["Airbus A330neo", "A330-900"],
    "A340": ["Airbus A340", "A340-600"],
    "A350": ["Airbus A350", "A350-900"],
    "A380": ["Airbus A380", "A380-800"],
    "B707": ["Boeing 707", "707-320"],
    "B717": ["Boeing 717", "717-200"],
    "B727": ["Boeing 727", "727-200"],
    "B737": ["Boeing 737", "737-800"],
    "B737MAX": ["Boeing 737 MAX", "737 MAX 8", "737-8"],
    "B747": ["Boeing 747", "747-400"],
    "B757": ["Boeing 757", "757-200"],
    "B767": ["Boeing 767", "767-300"],
    "B777": ["Boeing 777", "777-300"],
    "B787": ["Boeing 787", "787-9"],
    "MD80": ["McDonnell Douglas MD-80", "MD-82", "MD-80"],
    "MD90": ["McDonnell Douglas MD-90", "MD-90"],
    "MD11": ["McDonnell Douglas MD-11", "MD-11"],
    "DC8": ["Douglas DC-8", "DC-8-63"],
    "DC9": ["McDonnell Douglas DC-9", "DC-9-30"],
    "DC10": ["McDonnell Douglas DC-10", "DC-10-30"],
    "Concorde": ["Concorde", "Airbus-BAC Concorde"],
    "L1011": ["Lockheed L-1011", "L-1011", "TriStar"],
    "Comet": ["de Havilland Comet", "DH.106 Comet", "Comet"],
    "Caravelle": ["Sud Aviation Caravelle", "Caravelle"],
    "BAC111": ["BAC One-Eleven", "BAC 1-11", "One-Eleven"],
    "Trident": ["Hawker Siddeley Trident", "Trident"],
    "Tu154": ["Tupolev Tu-154", "Tu-154"],
    "Tu204": ["Tupolev Tu-204", "Tu-204"],
    "Il86": ["Ilyushin Il-86", "Il-86"],
    "Il96": ["Ilyushin Il-96", "Il-96"],
    "Yak42": ["Yakovlev Yak-42", "Yak-42"],
    "SSJ100": ["Sukhoi Superjet 100", "Superjet 100", "SSJ100"],
    "CRJ": ["Bombardier CRJ", "CRJ900", "CRJ-900"],
    "E145": ["Embraer ERJ 145", "ERJ-145", "ERJ 145"],
    "E170": ["Embraer E-170", "E170", "Embraer 170"],
    "E190": ["Embraer E-190", "E190", "Embraer 190"],
    "E190E2": ["Embraer E190-E2", "E190-E2", "E-190 E2"],
    "E195": ["Embraer E-195", "E195", "Embraer 195"],
    "E195E2": ["Embraer E195-E2", "E195-E2", "E-195 E2"],
    "ATR42": ["ATR 42", "ATR-42"],
    "ATR72": ["ATR 72", "ATR-72"],
    "DH8D": ["De Havilland Canada Dash 8", "Dash 8-400", "Q400"],
    "Saab340": ["Saab 340", "SF340"],
    "F70": ["Fokker 70", "Fokker F70"],
    "F100": ["Fokker 100", "Fokker F100"],
    "BAe146": ["British Aerospace 146", "BAe 146", "Avro RJ100"],
}

# Der Typ-Token, der in Titel oder Beschreibung des Fotos vorkommen muss -
# die Familie samt ihren Varianten ("MD-?8[0-8]" trifft MD-82 und MD82,
# "Dash ?8" auch DHC-8-400 und Q400). Ohne linken Wortumbruch, damit
# Registrierungs-Stil ("B737-800") mittrifft; ohne rechten, damit
# Anhaengungen ("A320neo", "737-800") mittriffen.
TYPE_MATCH = {
    "A220": r"A220|\bCS[ -]?[13]00\b|\bC ?Series\b",
    "A300": r"A300",
    "A310": r"A310",
    "A318": r"A318",
    "A319": r"A319",
    "A320": r"A320",
    "A320neo": r"A320\s?neo|\bA320NEO\b|A319\s?neo|\bA319NEO\b",
    "A321": r"A321",
    "A321neo": r"A321\s?neo|\bA321NEO\b|A321LR|A321XLR",
    "A330": r"A330",
    "A330neo": r"A330\s?neo|\bA330NEO\b|A330-?800|A330-?900|\bA338\b|\bA339\b",
    "A340": r"A340",
    "A350": r"A350",
    "A380": r"A380",
    "B707": r"707",
    "B717": r"717",
    "B727": r"727",
    "B737": r"737",
    "B737MAX": r"737\s?MAX|\b737-?[789]\b|\b737-?10\b|\bMAX ?[789]\b|\bMAX ?10\b",
    "B747": r"747",
    "B757": r"757",
    "B767": r"767",
    "B777": r"777",
    "B787": r"787|\bDreamliner\b",
    "MD80": r"MD-?8[0-8]",
    "MD90": r"MD-?90",
    "MD11": r"MD-?11",
    "DC8": r"DC-?8",
    "DC9": r"DC-?9",
    "DC10": r"DC-?10",
    "Concorde": r"Concorde",
    "L1011": r"L-?1011|\bTri ?Star\b",
    "Comet": r"DH\.? ?106|\bComet\b",
    "Caravelle": r"Caravelle",
    "BAC111": r"BAC ?1-?11|\bOne-? ?Eleven\b",
    "Trident": r"Trident",
    "Tu154": r"Tu-?154",
    "Tu204": r"Tu-?20[34]",
    "Il86": r"Il-?86",
    "Il96": r"Il-?96",
    "Yak42": r"Yak-?42",
    "SSJ100": r"Super ?Jet ?100|SSJ-?100|RRJ-?95|SJ-?100",
    "CRJ": r"\bCRJ|Canadair Regional Jet",
    "E145": r"\b(?:ERJ|EMB)-? ?145\b",
    "E170": r"\b(?:ERJ|EMB)-? ?17[05]\b|\bE-? ?17[05]\b",
    "E190": r"\b(?:ERJ|EMB)-? ?190\b|\bE-? ?190\b",
    "E190E2": r"\bE-? ?190-? ?E2\b|\bE190E2\b|\b(?:ERJ|EMB)-? ?190-? ?E2\b",
    "E195": r"\b(?:ERJ|EMB)-? ?195\b|\bE-? ?195\b",
    "E195E2": r"\bE-? ?195-? ?E2\b|\bE195E2\b|\b(?:ERJ|EMB)-? ?195-? ?E2\b",
    "ATR42": r"\bATR-? ?42\b",
    "ATR72": r"\bATR-? ?72\b",
    "DH8D": r"\bDHC-?8|\bDash-? ?8\b|\bQ400\b",
    "Saab340": r"\bSaab ?340\b|\bSF-? ?340\b",
    "F70": r"\bFokker ?70\b|\bF-? ?70\b|\bF70\b",
    "F100": r"\bFokker ?100\b|\bF-? ?100\b|\bF100\b",
    "BAe146": r"\bBAe ?146\b|\bAvro RJ ?\d*\b|\bRJ(?:70|85|100)\b",
}
TYPE_MATCH_RE = {
    code: re.compile(pattern, re.IGNORECASE) for code, pattern in TYPE_MATCH.items()
}

# Harte Filter: kein Foto, das eine Aussenaufnahme eines echten Flugzeugs
# ausschliesst. Ein Modell (auch als Massstab oder Hersteller genannt), ein
# Cockpit, die Kabine, eine Karte oder ein Unfallfoto ist nicht der Typ -
# solche Treffer werden verworfen, nicht nur abgewertet.
HARD_NEGATIVE_RES = (
    re.compile(r"\b(models?|modell(e|flugzeug)?|toys?|spielzeug)\b", re.IGNORECASE),
    re.compile(r"\bdie-?cast\b", re.IGNORECASE),
    re.compile(r"\bscreenshots?\b", re.IGNORECASE),
    re.compile(r"\b(papers?|zeichnung|sketch)\b", re.IGNORECASE),
    re.compile(r"\b(render|rendering|gerendert|3d-?grafik)\b", re.IGNORECASE),
    re.compile(r"\blogos?\b", re.IGNORECASE),
    re.compile(r"\bcockpits?\b", re.IGNORECASE),
    re.compile(r"\b(interiors?|cabins?|kabine|inside)\b", re.IGNORECASE),
    re.compile(r"\bmaps?\b", re.IGNORECASE),
    re.compile(r"\b(crash(es)?|accident|wreck|emergency)\b", re.IGNORECASE),
    # Bergung und Unfalluntersuchung: das Wrack ist nicht der Typ.
    re.compile(r"\b(recovered|recovery|salvage)\b", re.IGNORECASE),
    re.compile(r"\bntsb\b", re.IGNORECASE),
    # Militaer: Uebung oder Waffentraeger ist kein Verkehrsflugzeug.
    re.compile(r"\b(exercises?|manoeuvres?|maneuvers?|drills?)\b", re.IGNORECASE),
    re.compile(
        r"\b(military|marine corps|air force|navy|army|luftwaffe|bundeswehr)\b",
        re.IGNORECASE,
    ),
    # Modellflugzeuge im Massstab und von Modell-Herstellern.
    re.compile(r"\b1[:/]\s?(?:72|76|100|144|200|400|500|600)\b", re.IGNORECASE),
    re.compile(
        r"\b(herpa|hogan|gemini ?jets|phoenix ?models|ng ?models|jc ?wings)\b",
        re.IGNORECASE,
    ),
    # Comet/Trident: das Himmelskoerper bzw. Rakete und U-Boot gleichen Namens.
    re.compile(r"\bhalleys?\b|\bmissiles?\b|\bsubmarines?\b", re.IGNORECASE),
)

# Harte Filter nur fuer den Titel: Ein Foto, dessen Titel das Triebwerk, ein
# Rumpfteil oder ein Denkmal nennt, zeigt nicht das Flugzeug als Ganzes
# ("Kuznetsov NK-86 engines on an Ilyushin Il-86", "L-1011 TriStar fuselage",
# "Yak-42 Monument"). Eine Ganzaufnahme nennt solche Woerter nicht im Titel.
TITLE_HARD_NEGATIVE_RES = (
    re.compile(r"\b(engines?|triebwerke?)\b", re.IGNORECASE),
    re.compile(r"\bfuselages?\b", re.IGNORECASE),
    re.compile(r"\b(wings?|winglets?|sharklets?)\b", re.IGNORECASE),
    re.compile(r"\b(tails?|noses?|radomes?|cowlings?)\b", re.IGNORECASE),
    re.compile(r"\b(landing ?gear|propellers?|rotors?)\b", re.IGNORECASE),
    re.compile(r"\bmonuments?\b", re.IGNORECASE),
)

# Abzuege: Aussenaufnahme, aber keine schoene des ganzen Flugzeugs -
# Nahaufnahme, Triebwerk, Sitzplaetze oder nur das Klassenerlebnis.
NEGATIVE_WORDS = (
    (re.compile(r"\bseats?\b", re.IGNORECASE), -15),
    (re.compile(r"\bengines?\b|\btriebwerk(e|en)?\b", re.IGNORECASE), -10),
    (re.compile(r"\bwing ?views?\b", re.IGNORECASE), -10),
    (re.compile(r"\bdecals?\b", re.IGNORECASE), -10),
    (re.compile(r"\bclose-?ups?\b|\bnahaufnahme\b", re.IGNORECASE), -5),
    (re.compile(r"\b(first|business|economy|premium)\s*class\b", re.IGNORECASE), -15),
)

# Ziel-Alter der Fotos: hoechstens etwa drei Jahre, damit das Foto den Typ
# moeglichst aktuell und gut aufgeloest zeigt. Abgestuft bis acht Jahre;
# aeltere Fotos bekommen keinen Zuschlag mehr (siehe recency_score).
TARGET_AGE_DAYS = 3 * 365
RECENT_AGE_DAYS = 5 * 365
MID_AGE_DAYS = 8 * 365

MANUAL_IMAGE_RE = re.compile(r"^([A-Za-z0-9]{2,8})(_launch)?\.(png|jpe?g|webp)$", re.IGNORECASE)


def prefer_ipv4():
    """Laesst `urllib` nur IPv4-Adressen aufloesen.

    Manche Netze haben ein AAAA-Routing, ueber das der Server nicht antwortet;
    die Verbindung haengt dann bis zum Timeout (siehe build_airport_satellite).
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
        except Exception as error:  # noqa: BLE001 - jede Stoerung wird wiederholt
            last_error = error
            wait = 2.0 * (attempt + 1)
            print("  Wiederholung nach Fehler (%s), warte %.0fs" % (error, wait))
            time.sleep(wait)
    raise RuntimeError("GET fehlgeschlagen: %s (%s)" % (COMMONS_API, last_error))


def download(url):
    """Laedt eine Datei (Thumbnail) mit User-Agent, Wiederholung bei Fehlern."""
    last_error = None
    for attempt in range(RETRIES):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 - jede Stoerung wird wiederholt
            last_error = error
            wait = 2.0 * (attempt + 1)
            print("  Wiederholung nach Fehler (%s), warte %.0fs" % (error, wait))
            time.sleep(wait)
    raise RuntimeError("Download fehlgeschlagen: %s (%s)" % (url, last_error))


def load_facts(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


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
    """http-Lizenz-URLs auf https heben (aeltere Commons-Dateien liefern http)."""
    if url.startswith("http://"):
        return "https://" + url[len("http://"):]
    return url


def license_url_for(short_name):
    """Kanonische Lizenz-URL aus dem Kurznamen (siehe build_airline_photos)."""
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


def strip_html(text):
    """HTML-Tags und Entities aus einem extmetadata-Wert entfernen.

    Commons haengt an manche Autor-Angaben "( talk page )" an, wenn der
    Benutzername verlinkt ist - das ist kein Teil des Namens.
    """
    without_tags = re.sub(r"<[^>]+>", " ", text or "")
    cleaned = html.unescape(without_tags)
    cleaned = re.sub(r"\s*\(\s*talk(\s+page)?\s*\)", "", cleaned, flags=re.IGNORECASE)
    return re.sub(r"\s+", " ", cleaned).strip()


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

    Ein blosses Jahr zaehlt nicht ("in 2018 livery" - das kann das Jahr der
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


def parse_photo_date(metadata, info, title=""):
    """Aufnahmedatum eines Fotos als ISO-String oder None.

    Primaer das EXIF-Datum (DateTimeOriginal) - es sagt, wann das Flugzeug
    fotografiert wurde. Steht im Titel ein eindeutiges Datum und weicht sein
    Jahr vom EXIF-Jahr ab, ist die Kamerauhr verstellt - der Titel des
    Fotografen zaehlt. Fehlt das EXIF-Datum, dient das Titel-Datum, sonst
    das Upload-Datum der Datei als Ersatz (Commons-`timestamp`).
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
            # Kamerauhr verstellt: Das Datum im Titel des Fotografen zaehlt.
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


def age_days(date_str, today=None):
    """Alter eines ISO-Datums in Tagen; None, wenn es fehlt oder ungueltig ist."""
    if not date_str:
        return None
    try:
        return ((today or datetime.date.today()) -
                datetime.date.fromisoformat(date_str)).days
    except ValueError:
        return None


def recency_score(date_str, today=None):
    """Wertung nach Alter des Fotos.

    Die Fotos sollen so neu wie moeglich sein: Ein Foto, das hoechstens etwa
    drei Jahre alt ist, bekommt den staerksten Zuschlag (TARGET_AGE_DAYS),
    innerhalb der Frist zaehlt das juengere. Darueber abgestuft bis acht
    Jahre, aeltere Fotos bekommen nichts - dann entscheidet die Qualitaet.
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


def stored_dimensions_ok(photo):
    """True, wenn die Masse eines gespeicherten Fotos den Test-Grenzen genuegen.

    AircraftPhotosAssetTest prueft das gespeicherte Thumbnail: Breite
    1200-1280 px, Hoehe mindestens MIN_STORED_HEIGHT, Querformat, hoechstens
    MAX_RATIO zu 1.
    """
    width = photo.get("width") or 0
    height = photo.get("height") or 0
    return (MIN_WIDTH <= width <= THUMB_WIDTH
            and height >= MIN_STORED_HEIGHT
            and width > height
            and width <= height * MAX_RATIO)


# Haeufige Airline-Namen: Ein Hersteller-Foto (Werkslackierung) traegt
# keinen davon im Titel; ein Launch-Foto traegt den Erstkunden. Die Liste
# ist bewusst kurz (Top-Airlines + Europa), sie dient nur der Wertung.
COMMON_AIRLINE_WORDS = re.compile(
    r"\b(?:"
    r"air ?france|lufthansa|british ?airways|american ?airlines|united ?airlines|"
    r"delta ?air ?lines|southwest|ryanair|easyjet|easy ?jet|"
    r"qantas|emirates|qatar|etihad|singapore ?airlines|cathay|ana|jal|"
    r"klm|air ?canada|swiss|swissair|sas|finnair|aeroflot|turkish|"
    r"iberia|alitalia|tap ?air ?portugal|virgin ?america|malindo|"
    r"lion ?air|garuda|air ?asia|jet ?airways|wizz ?air|"
    r"air ?china|china ?eastern|southern ?china|ainan|hainan|"
    r"lot ?polish|wideroe|widerøe|azul|gol ?air|avianca|"
    r"frontier|alaska ?airlines|alaska|spirit|jetblue|"
    r"interjet|volaris|aeromexico|copa ?air|latam|aerolineas|"
    r"europe ?air|eurowings|condor|tuifly|sunexpress|"
    r"air ?europa|air ?italia|eurowings|vueling|transavia|"
    r"norwegian|icelandair|aer ?lingus|portugalia|"
    r"air ?new ?zealand|virgin ?australia|"
    r"pan ?am|eastern ?air|braniff| TWA |twa|"
    r"boac|bEA|british ?overseas|caledonian|"
    r"swissair|sabena|sabena|ansett|"
    r"aer ?flot|aeroflot|rossiya|"
    r"air ?inter|air ?monde|UTA|"
    r"bahamasair|cubana|air ?jamaica|"
    r"bahrain ?air|gulf ?air|oman ?air|"
    r"el ?al|royal ?air ?maroc|RAM|"
    r"ethiopian|kenya ?airways|south ?african|"
    r"egyptair|saudia|saudi ?arabian|"
    r"iran ?air|mahan ?air|"
    r"pakistan ?int|PIA|"
    r"thai ?airways|bangkok ?air|"
    r"vietnam ?airlines|garuda|philippine|"
    r"malaysia ?airlines|singapore|"
    r"cathay ?pacific|cathay|dragonair|"
    r"china ?airlines|air ?china|"
    r"asiana|korean ?air|jin ?air|"
    r"ana|all ?nippon|japan ?airlines|"
    r"jal|"
    r"lan ?chile|gol ?transportes|aeromexico|"
    r"aerolineas ?argentinas|"
    r"varig ?log|gol ?log|tam|"
    r"air ?arabia|fly ?dubai|emirates|"
    r"air ?astana|azerbaijan ?air|"
    r"uzbekistan|belfast|city ?jet|"
    r"lufthansa ?cityline|cityline|"
    r"britannia|monarch|thomson|tui|"
    r"first ?choice|corsair|xl ?airways|"
    r"germanwings|air ?berlin|air ?berlin|"
    r"condor|thomas ?cook|"
    r"air ?europa|air ?europa|"
    r"primera ?air|nordic ?regional|"
    r"noble ?air|windrose|wind ?rose|"
    r"roseair|windrose|"
    r"^)",
    re.IGNORECASE,
)


def candidate_score(page, info, name, match_re, require_title=True,
                    facts_entry=None, kind="general"):
    """Wertung eines Commons-Treffers; None, wenn er die harten Filter nicht
    besteht (kein JPEG/PNG, zu klein, Hochformat, Lizenz nicht erlaubt, Autor
    unbekannt, kein Thumbnail, keine Lizenz-URL ermittelbar, der Typ kommt
    nicht im Titel vor oder ein Ausschlussgrund greift). Rueckgabe
    (score, license_url, date).

    `name` ist der Anzeigename des Typs (Zusatzpunkte, wenn er im Titel
    steht), `match_re` der Typ-Token (siehe TYPE_MATCH). Die Wertung
    bevorzugt junge Fotos (siehe recency_score).

    `kind` steuert die Zusatzwertung: "manufacturer" gibt Bonus, wenn kein
    Airline-Name im Titel steht (Werkslackierung traegt keine Bemalung);
    "launch" gibt Bonus, wenn der Launch-Customer-Name im Titel steht.

    `require_title=True` verlangt den Typ-Token im Titel: Commons betitelt
    Fotos eines Typs mit dem Typ ("Airbus A300-600, D-A..."), ein Vorkommen
    nur in der Beschreibung ist meist eine beilaeufige Erwaehnung in einem
    Foto eines anderen Flugzeugs ("Aircraft lining up ... A300 in the
    background"). Nur wenn ein Typ ueberhaupt keinen Titel-Treffer hat,
    laesst der zweite Durchlauf in find_photo die Beschreibung gelten.
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
    # ohne eine Lizenz-URL ist die Namensnennung nicht vollstaendig.
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

    # Der Typ muss im Titel vorkommen; sonst ist der Treffer meist eine
    # Fehlzuordnung (ueber ein loses Wort der Suche gelandet, der Typ steht
    # bloss in der Beschreibung eines Fotos eines anderen Flugzeugs).
    title_match = match_re.search(title_lower) is not None
    if require_title and not title_match:
        return None
    if not title_match and not match_re.search(description.lower()):
        return None
    # Kein Foto einer Aussenaufnahme (Modell, Cockpit, Kabine, Karte, Unfall):
    # siehe HARD_NEGATIVE_RES.
    if any(pattern.search(text) for pattern in HARD_NEGATIVE_RES):
        return None
    # Titel, der ein Triebwerk, Rumpfteil oder Denkmal nennt: kein Foto des
    # Typs als Ganzes (siehe TITLE_HARD_NEGATIVE_RES).
    if any(pattern.search(title_lower) for pattern in TITLE_HARD_NEGATIVE_RES):
        return None

    date = parse_photo_date(metadata, info, title)

    score = 0.0
    if title_match:
        score += 100
    else:
        score += 30
    if name.lower() in title_lower:
        score += 15
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

    # Hersteller-/Launch-Wertung.
    has_airline = COMMON_AIRLINE_WORDS.search(title_lower) is not None
    if kind == "manufacturer":
        # Werkslackierung: Bonus ohne Airline-Namen, leichter Abzug mit.
        if not has_airline:
            score += 40
        else:
            score -= 10
        # Begriffe wie "livery", "prototype", "rollout", "delivery" sind gut.
        if re.search(r"\b(?:livery|prototype|rollout|delivery|first flight|house)\b",
                     title_lower):
            score += 20
    elif kind == "launch":
        # Launch Customer: Bonus, wenn der Kundenname im Titel steht.
        customer = (facts_entry or {}).get("launchCustomer") or ""
        customer = re.split(r"\s+and\s+", customer)[0].strip().lower()
        if customer and customer in title_lower:
            score += 50
        # Auch ohne Kunden-Match: Airline im Titel ist beim Launch-Foto OK.
        if has_airline:
            score += 10

    # Aktualitaet: je juenger das Foto, desto besser (Ziel: hoechstens etwa
    # drei Jahre).
    score += recency_score(date)
    return score, license_url, date


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


# Hersteller-Suchbegriffe pro Hersteller: Werkslackierung, Prototyp, Rollout,
# Erstflug. Gesucht wird "<Typ> <Begriff>" - ein Foto ohne Airline-Namen im
# Titel ist meist die Hersteller-Variante.
MANUFACTURER_TERMS = {
    "Airbus": ["Airbus livery", "Airbus house livery", "prototype", "first flight",
               "rollout", "delivery"],
    "Boeing": ["Boeing livery", "Boeing house livery", "prototype", "first flight",
               "rollout", "delivery"],
    "Bombardier": ["Bombardier livery", "prototype", "first flight", "rollout"],
    "Embraer": ["Embraer livery", "prototype", "first flight", "rollout"],
    "ATR": ["ATR livery", "prototype", "first flight", "rollout"],
    "McDonnell Douglas": ["McDonnell Douglas livery", "prototype", "first flight",
                          "rollout", "delivery"],
    "Douglas": ["Douglas livery", "prototype", "first flight", "rollout",
                "delivery"],
    "de Havilland": ["de Havilland livery", "prototype", "first flight"],
    "Sud Aviation": ["Sud Aviation livery", "prototype", "first flight"],
    "Lockheed": ["Lockheed livery", "prototype", "first flight", "rollout"],
    "British Aircraft Corporation": ["BAC livery", "prototype", "first flight"],
    "Hawker Siddeley": ["Hawker Siddeley livery", "prototype", "first flight"],
    "Tupolev": ["Tupolev livery", "prototype", "first flight", "rollout"],
    "Ilyushin": ["Ilyushin livery", "prototype", "first flight", "rollout"],
    "Yakovlev": ["Yakovlev livery", "prototype", "first flight"],
    "Sukhoi": ["Sukhoi livery", "prototype", "first flight", "rollout"],
    "Saab": ["Saab livery", "prototype", "first flight"],
    "Fokker": ["Fokker livery", "prototype", "first flight", "rollout"],
    "British Aerospace": ["British Aerospace livery", "prototype", "first flight"],
}


def manufacturer_name(facts_entry):
    """Hersteller-Schluesselwort aus dem Fakten-Eintrag (fuer MANUFACTURER_TERMS)."""
    manufacturer = (facts_entry.get("manufacturer") or "").lower()
    for key in MANUFACTURER_TERMS:
        if key.lower() in manufacturer:
            return key
    # Erste Wortgruppe versuchen ("McDonnell Douglas / Boeing" -> "McDonnell Douglas").
    first = manufacturer.split(" / ")[0].strip()
    for key in MANUFACTURER_TERMS:
        if key.lower() == first:
            return key
    return None


def manufacturer_query_ladder(code, type_names, facts_entry):
    """Suchanfragen fuer das Hersteller-Foto (Werkslackierung/Prototyp).

    Erst die spezifischen Hersteller-Begriffe, dann der Typ allein (ein Foto
    ohne Airline im Titel ist meist die Werkslackierung).
    """
    queries = []
    mfr = manufacturer_name(facts_entry)
    terms = MANUFACTURER_TERMS.get(mfr, ["prototype", "first flight", "rollout"]) if mfr else \
        ["prototype", "first flight", "rollout", "livery"]
    for name in type_names[:2]:  # nur die ersten zwei Namen (nicht zu allgemein)
        for term in terms:
            queries.append('"%s" "%s"' % (name, term))
    # Fallback: Typ allein, ohne Airline.
    for name in type_names:
        queries.extend(query_ladder(name))
    return queries


def launch_query_ladder(code, type_names, facts_entry):
    """Suchanfragen fuer das Launch-Customer-Foto.

    Sucht nach "<Typ> <Launch Customer>" - der Erstkunde steht im
    Fakten-Eintrag. Ohne bekannten Kunden entfällt das zweite Foto.
    """
    customer = (facts_entry.get("launchCustomer") or "").strip()
    if not customer:
        return []
    # Kunden-Namen kürzen: "Lufthansa and Air France" -> "Lufthansa",
    # "Delta Air Lines and United Airlines" -> "Delta Air Lines".
    customer = re.split(r"\s+and\s+", customer)[0].strip()
    queries = []
    for name in type_names[:2]:
        queries.append('"%s" "%s" aircraft' % (name, customer))
        queries.append('"%s" "%s"' % (name, customer))
        queries.append('%s %s aircraft' % (name, customer))
    return queries


def convert_to_webp(data, target):
    """Bilddaten nach webp; Rueckgabe (Breite, Hoehe, verkleinert).

    EXIF-Drehung wird angewendet, Transparenz auf Weiss aufgeloest, die
    Breite auf THUMB_WIDTH begrenzt. Eine reine Formatkonvertierung gilt
    nicht als Aenderung im Sinn von CC BY; eine Verkleinerung schon (siehe
    `modified`).
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
    """URL der Commons-Dateiseite aus dem Titel ohne 'File:'-Praefix."""
    return "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(
        title.replace(" ", "_")
    )


def title_of(page):
    title = page.get("title", "")
    if title.startswith("File:"):
        title = title[len("File:"):]
    return title


def base_title(title):
    """Titel ohne Erweiterung und Crop-Endung, kleingeschrieben.

    'X (crop).jpg' und 'X.jpg' sind dasselbe Foto - es darf nicht zweimal
    bewertet werden. Nummernierte Folgen ('X (2).jpg') sind dagegen
    verschiedene Fotos und bleiben erlaubt.
    """
    name = (title or "").strip()
    name = re.sub(r"\.(jpe?g|png|webp)$", "", name, flags=re.IGNORECASE)
    name = re.sub(r"\s*\(?\s*crop(?:ped)?\s*\)?\s*$", "", name, flags=re.IGNORECASE)
    return name.strip().lower()


def find_photo(names, match_re, delay, require_title=True, queries=None,
               facts_entry=None, kind="general"):
    """Sucht das beste Foto eines Typs; None, wenn keines brauchbar ist.

    `names` ist die Anfragereihe (siehe TYPE_SEARCH_NAMES). Pro Name laeuft
    die query_ladder; die erste Suchstufe mit einem brauchbaren Treffer
    gewinnt (schneller Lauf). Innerhalb einer Stufe wird der bestbewertete
    Treffer genommen, Crop-Varianten desselben Fotos werden uebersprungen.
    Rueckgabe (page, imageinfo, score, license_url, date) oder None.

    `require_title` siehe candidate_score: Der erste Durchlauf verlangt den
    Typ im Titel, der zweite (in main) laesst als Fallback die Beschreibung
    gelten, wenn es sonst kein Foto gaebe.

    `queries` ueberschreibt die Suchanfragen (fuer Hersteller- und
    Launch-Customer-Fotos, siehe manufacturer_query_ladder /
    launch_query_ladder). `kind` ("manufacturer"/"launch"/"general") steuert
    die Wertung in candidate_score.
    """
    exclude = set()
    bases = set()
    search_queries = queries if queries is not None else [
        q for name in names for q in query_ladder(name)
    ]
    for query in search_queries:
        pool = []
        for page, info in search_commons(query):
            scored = candidate_score(page, info, names[0], match_re,
                                     require_title=require_title,
                                     facts_entry=facts_entry, kind=kind)
            if scored is None:
                continue
            score, license_url, date = scored
            title = title_of(page)
            url = source_url(title)
            if url in exclude:
                continue
            key = (-score, -int(info.get("width") or 0), title)
            pool.append((key, page, info, score, license_url, date))
            exclude.add(url)
        pool.sort(key=lambda item: item[0])
        for _, page, info, score, license_url, date in pool:
            base = base_title(title_of(page))
            if base in bases:
                continue
            bases.add(base)
            return page, info, score, license_url, date
        # Pause zwischen API-Abfragen; die letzte erfolgreiche Stufe vor
        # dem Rueckprung braucht keine.
        time.sleep(delay)
    return None


def download_photo(photos_dir, code, page, info, license_url, date, suffix=""):
    """Laedt ein Foto herunter, konvertiert es und liefert den photos.json-Eintrag.

    `suffix` haengt an den Datei-Namen an ("" fuer das Hersteller-Foto,
    "_launch" fuer das Launch-Customer-Foto). None bei einem fehlgeschlagenen
    Download.
    """
    metadata = info.get("extmetadata") or {}
    title = title_of(page)
    author = strip_html((metadata.get("Artist") or {}).get("value", ""))
    license_short = (metadata.get("LicenseShortName") or {}).get("value", "").strip()
    filename = code + suffix + ".webp"
    target = os.path.join(photos_dir, filename)
    try:
        data = download(info["thumburl"])
    except RuntimeError as error:
        print("%s: Download fehlgeschlagen (%s)" % (code + suffix, error))
        return None
    width, height, resized = convert_to_webp(data, target)
    return {
        "file": filename,
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


def install_manual(manual_dir, types, photos_dir, photos):
    """Installiert kuratierte Fotos aus aircraft_photos_manual/ (gewinnen immer).

    `A320.jpg` ist das Hersteller-Foto, `A320_launch.jpg` das Launch-Customer-
    Foto. Beide landen im `photos`-Array des Typs.
    """
    if not os.path.isdir(manual_dir):
        return 0
    count = 0
    for name in sorted(os.listdir(manual_dir)):
        match = MANUAL_IMAGE_RE.match(name)
        if not match:
            continue
        code = match.group(1).upper()
        kind = "launch" if match.group(2) else "manufacturer"
        if code not in types:
            print("  Warnung: %s (%s) ist kein bekannter Typ, wird uebergangen"
                  % (code, name))
            continue
        meta_path = os.path.join(manual_dir, match.group(1) + ".json")
        if not os.path.isfile(meta_path):
            print("  Warnung: %s hat keine %s.json-Attribution, wird uebergangen"
                  % (name, match.group(1)))
            continue
        with open(meta_path, encoding="utf-8") as handle:
            meta = json.load(handle)
        missing = [
            field for field in ("author", "license", "licenseUrl", "sourceUrl")
            if not meta.get(field)
        ]
        if missing:
            print("  Warnung: %s.json fehlen Felder (%s), wird uebergangen"
                  % (match.group(1), ", ".join(missing)))
            continue
        if not license_allowed(meta["license"]):
            print("  Warnung: %s.json hat eine nicht erlaubte Lizenz (%s), wird uebergangen"
                  % (match.group(1), meta["license"]))
            continue
        suffix = "_launch" if kind == "launch" else ""
        target = os.path.join(photos_dir, code + suffix + ".webp")
        with open(os.path.join(manual_dir, name), "rb") as handle:
            data = handle.read()
        width, height, resized = convert_to_webp(data, target)
        entry = {
            "file": code + suffix + ".webp",
            "kind": kind,
            "author": meta["author"],
            "license": meta["license"],
            "licenseUrl": meta["licenseUrl"],
            "sourceUrl": meta["sourceUrl"],
            "title": meta.get("title", ""),
            "date": meta.get("date", ""),
            "modified": bool(meta.get("modified", resized)),
            "width": width,
            "height": height,
            "manual": True,
        }
        # In das photos-Array des Typs einfuegen oder ersetzen.
        type_entry = photos.setdefault(code, {"photos": []})
        type_entry["photos"] = [
            p for p in type_entry["photos"] if p.get("kind") != kind
        ]
        type_entry["photos"].append(entry)
        print("  manuell: %s [%s] <- %s (%dx%d, %s)" % (code, kind, name, width, height, meta["license"]))
        count += 1
    return count


def remove_entry(photos, photos_dir, code):
    """Entfernt Eintrag samt Dateien (NO_PHOTO, nicht mehr im Fakten-Asset)."""
    if code not in photos:
        return
    for photo in photos[code].get("photos", []):
        stale = os.path.join(photos_dir, photo.get("file", ""))
        if os.path.isfile(stale):
            os.remove(stale)
    del photos[code]


def write_photos(photos, photos_dir):
    """Schreibt photos.json (kompakt, sortiert, wie aircraft_facts.json)."""
    with open(os.path.join(photos_dir, "photos.json"), "w", encoding="utf-8") as handle:
        json.dump(photos, handle, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        handle.write("\n")


def validate(photos, photos_dir, known_types):
    """Prueft die gesamte Zuordnung; False bei Fehlern.

    Erwartet die neue Struktur: photos[code] = {"photos": [foto, ...]} mit
    jedem Foto als {file, kind, author, license, ...}. Hersteller-Foto
    (kind="manufacturer") muss vorhanden sein; Launch-Foto (kind="launch")
    ist optional.
    """
    ok = True
    referenced = set()
    total_photos = 0
    for code in sorted(photos):
        if code not in known_types:
            print("  Fehler: %s ist kein Typ aus aircraft_facts.json" % code)
            ok = False
        entry = photos[code]
        photo_list = entry.get("photos") or []
        if not photo_list:
            print("  Fehler: %s hat keine Fotos" % code)
            ok = False
            continue
        kinds = [p.get("kind") for p in photo_list]
        if "manufacturer" not in kinds:
            print("  Fehler: %s hat kein Hersteller-Foto" % code)
            ok = False
        for photo in photo_list:
            total_photos += 1
            filename = photo.get("file", "")
            referenced.add(filename)
            path = os.path.join(photos_dir, filename)
            if not os.path.isfile(path):
                print("  Fehler: %s verweist auf %s, Datei fehlt" % (code, filename))
                ok = False
                continue
            with open(path, "rb") as handle:
                header = handle.read(16)
            if header[:4] != b"RIFF" or header[8:12] != b"WEBP":
                print("  Fehler: %s ist kein WebP" % filename)
                ok = False
            if os.path.getsize(path) < MIN_FILE_BYTES:
                print("  Fehler: %s ist verdaechtig klein (%d B)"
                      % (filename, os.path.getsize(path)))
                ok = False
            missing = [
                field for field in ("author", "license", "licenseUrl", "sourceUrl")
                if not photo.get(field)
            ]
            if missing:
                print("  Fehler: %s (%s) fehlen Attribution-Felder (%s)"
                      % (code, filename, ", ".join(missing)))
                ok = False
            if not license_allowed(photo.get("license", "")):
                print("  Fehler: %s (%s) hat eine nicht erlaubte Lizenz (%s)"
                      % (code, filename, photo.get("license")))
                ok = False
            if not stored_dimensions_ok(photo):
                print("  Fehler: %s (%s) Masse ausserhalb der Grenzen: %s x %s"
                      % (code, filename, photo.get("width"), photo.get("height")))
                ok = False
    orphans = sorted(
        f for f in os.listdir(photos_dir)
        if f.endswith(".webp") and f not in referenced
    )
    if orphans:
        print("  Fehler: %d nicht referenzierte Datei(en): %s"
              % (len(orphans), ", ".join(orphans)))
        ok = False
    total_bytes = sum(
        os.path.getsize(os.path.join(photos_dir, f))
        for f in os.listdir(photos_dir) if f.endswith(".webp")
    )
    print("Pruefung: %d Typen, %d Fotos, %.2f MB, %d verwaiste Dateien"
          % (len(photos), total_photos, total_bytes / 1048576.0, len(orphans)))
    return ok


def migrate_legacy_photos(photos):
    """Migriert alte photos.json-Struktur (ein Foto pro Typ) auf das
    photos-Array mit kind="manufacturer"."""
    migrated = {}
    for code, entry in photos.items():
        if "photos" in entry:
            migrated[code] = entry
            continue
        # Legacy: entry ist das Foto selbst.
        photo = dict(entry)
        photo["kind"] = "manufacturer"
        migrated[code] = {"photos": [photo]}
    return migrated


def find_and_download(photos_dir, code, names, match_re, delay, facts_entry):
    """Sucht Hersteller- und Launch-Customer-Foto und installiert beide.

    Rueckgabe (manufacturer_record, launch_record) - jedes None, wenn die
    Suche erfolglos war.
    """
    manufacturer_record = None
    launch_record = None

    # Hersteller-Foto: Werkslackierung / Prototyp.
    mfr_queries = manufacturer_query_ladder(code, names, facts_entry)
    found = None
    try:
        found = find_photo(names, match_re, delay, require_title=True,
                           queries=mfr_queries, facts_entry=facts_entry,
                           kind="manufacturer")
        if not found:
            found = find_photo(names, match_re, delay, require_title=False,
                               queries=mfr_queries, facts_entry=facts_entry,
                               kind="manufacturer")
    except RuntimeError as error:
        print("%s [manufacturer]: Suche fehlgeschlagen (%s)" % (code, error))
    if found:
        page, info, score, license_url, date = found
        manufacturer_record = download_photo(photos_dir, code, page, info,
                                             license_url, date, suffix="")
        if manufacturer_record:
            manufacturer_record["kind"] = "manufacturer"
            print("%s [manufacturer]: %s (%dx%d, %.0f, %s, %s, %s)"
                  % (code, manufacturer_record["title"][:60],
                     manufacturer_record["width"], manufacturer_record["height"],
                     score, manufacturer_record["license"],
                     manufacturer_record["author"][:30], manufacturer_record["date"]))

    # Launch-Customer-Foto.
    launch_queries = launch_query_ladder(code, names, facts_entry)
    if launch_queries:
        found = None
        try:
            found = find_photo(names, match_re, delay, require_title=True,
                               queries=launch_queries, facts_entry=facts_entry,
                               kind="launch")
            if not found:
                found = find_photo(names, match_re, delay, require_title=False,
                                   queries=launch_queries, facts_entry=facts_entry,
                                   kind="launch")
        except RuntimeError as error:
            print("%s [launch]: Suche fehlgeschlagen (%s)" % (code, error))
        if found:
            page, info, score, license_url, date = found
            launch_record = download_photo(photos_dir, code, page, info,
                                           license_url, date, suffix="_launch")
            if launch_record:
                launch_record["kind"] = "launch"
                print("%s [launch]: %s (%dx%d, %.0f, %s, %s, %s)"
                      % (code, launch_record["title"][:60],
                         launch_record["width"], launch_record["height"],
                         score, launch_record["license"],
                         launch_record["author"][:30], launch_record["date"]))

    return manufacturer_record, launch_record


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--facts", default=FACTS_PATH)
    parser.add_argument("--photos-dir", default=PHOTOS_DIR)
    parser.add_argument("--manual", default=MANUAL_DIR)
    parser.add_argument("--only", help="Komma-getrennte Typ-Codes statt aller Typen")
    parser.add_argument("--force", action="store_true", help="Vorhandene neu laden")
    parser.add_argument("--delay", type=float, default=0.5,
                        help="Pause zwischen API-Abfragen in Sekunden (Standard 0,5)")
    parser.add_argument("--list", action="store_true", help="Typenliste nur anzeigen")
    args = parser.parse_args()

    photos_dir = args.photos_dir
    photos_json = os.path.join(photos_dir, "photos.json")

    facts = load_facts(args.facts)
    types = facts.get("types") or {}
    if not types:
        print("Fehler: keine Typen in %s" % args.facts)
        return 1

    if args.only:
        # Die Codes werden gross geschrieben; die Schluessel im Asset tragen
        # aber die originale Schreibweise ("Concorde", "Saab340").
        by_upper = {code.upper(): code for code in types}
        targets = []
        for raw in args.only.split(","):
            code = raw.strip()
            if not code:
                continue
            canonical = by_upper.get(code.upper())
            if canonical is None:
                print("Fehler: %s ist nicht in %s" % (code, args.facts))
                return 1
            targets.append(canonical)
    else:
        targets = sorted(types)

    if args.list:
        for code in targets:
            print("%s  %s" % (code, types[code].get("name", "")))
        return 0

    print("Ziel: %d Typen" % len(targets))

    if os.path.isfile(photos_json):
        with open(photos_json, encoding="utf-8") as handle:
            photos = json.load(handle)
        photos = migrate_legacy_photos(photos)
        # Lizenz-URL bei aelteren Eintraegen nachziehen bzw. auf https heben.
        for entry in photos.values():
            for photo in entry.get("photos", []):
                if not photo.get("licenseUrl"):
                    photo["licenseUrl"] = license_url_for(photo.get("license", ""))
                else:
                    photo["licenseUrl"] = normalize_license_url(photo["licenseUrl"])
        # Typen ohne brauchbares Foto: Eintrag und Datei entfernen, falls ein
        # frueherer Lauf (mit schwaecherer Suche) doch eines installiert hat.
        for code in [c for c in photos if c in NO_PHOTO]:
            print("Entfernt (NO_PHOTO): %s" % code)
            remove_entry(photos, photos_dir, code)
        # Typen, die es im Fakten-Asset nicht mehr gibt, sind veraltet.
        for code in [c for c in photos if c not in types]:
            print("Entfernt (nicht mehr im Fakten-Asset): %s" % code)
            remove_entry(photos, photos_dir, code)
    else:
        photos = {}

    # Verwaiste manuelle Eintraege entfernen: Datei in
    # aircraft_photos_manual/ weg, Eintrag und Bild landen nicht als Leiche
    # im Asset.
    manual_codes = set()
    if os.path.isdir(args.manual):
        for name in os.listdir(args.manual):
            match = MANUAL_IMAGE_RE.match(name)
            if match:
                key = match.group(1).upper() + ("_launch" if match.group(2) else "")
                manual_codes.add(key)
    for code, entry in list(photos.items()):
        for photo in list(entry.get("photos", [])):
            pkey = code + ("_launch" if photo.get("kind") == "launch" else "")
            if photo.get("manual") and pkey not in manual_codes:
                print("Manuell entfernt: %s [%s] (keine Datei mehr in %s)"
                      % (code, photo.get("kind"), args.manual))
                stale = os.path.join(photos_dir, photo.get("file", ""))
                if os.path.isfile(stale):
                    os.remove(stale)
                entry["photos"].remove(photo)
        if not entry.get("photos"):
            del photos[code]

    prefer_ipv4()
    os.makedirs(photos_dir, exist_ok=True)

    manual_count = install_manual(args.manual, types, photos_dir, photos)
    if manual_count:
        print("Manuell: %d Foto(s) installiert" % manual_count)
        write_photos(photos, photos_dir)

    downloaded = 0
    skipped = 0
    failed = []
    for code in targets:
        if code in NO_PHOTO:
            # Siehe NO_PHOTO: die Suche findet nur falsche Fotos.
            skipped += 1
            continue
        # Pruefen, ob Hersteller-Foto bereits vorhanden ist.
        entry = photos.get(code, {"photos": []})
        has_manufacturer = any(
            p.get("kind") == "manufacturer"
            for p in entry.get("photos", [])
        )
        target_mfr = os.path.join(photos_dir, code + ".webp")
        target_launch = os.path.join(photos_dir, code + "_launch.webp")
        if not args.force and has_manufacturer and os.path.isfile(target_mfr):
            # Hersteller-Foto vorhanden; Launch-Foto nur nachholen, wenn es
            # noch keines gibt und ein Launch Customer bekannt ist.
            has_launch = any(
                p.get("kind") == "launch"
                for p in entry.get("photos", [])
            )
            launch_customer = (types[code].get("launchCustomer") or "").strip()
            if has_launch or not launch_customer or os.path.isfile(target_launch):
                skipped += 1
                continue
        names = TYPE_SEARCH_NAMES.get(code) or [types[code].get("name") or code]
        match_re = TYPE_MATCH_RE.get(code)
        if match_re is None:
            print("Fehler: kein Typ-Token fuer %s definiert" % code)
            return 1
        mfr_record, launch_record = find_and_download(
            photos_dir, code, names, match_re, args.delay, types[code]
        )
        if mfr_record is None and launch_record is None:
            print("%s: kein brauchbares Foto gefunden (%s)"
                  % (code, types[code].get("name", code)))
            failed.append(code)
            time.sleep(args.delay)
            continue
        # In photos.json eintragen.
        entry = photos.setdefault(code, {"photos": []})
        if mfr_record is not None:
            entry["photos"] = [
                p for p in entry["photos"] if p.get("kind") != "manufacturer"
            ]
            entry["photos"].append(mfr_record)
            downloaded += 1
        if launch_record is not None:
            entry["photos"] = [
                p for p in entry["photos"] if p.get("kind") != "launch"
            ]
            entry["photos"].append(launch_record)
            downloaded += 1
        # Nach jedem Typ speichern: ein Abbruch verliert hoechstens einen.
        write_photos(photos, photos_dir)
        time.sleep(args.delay)

    write_photos(photos, photos_dir)

    print("Fertig: %d geladen, %d uebersprungen (vorhanden), %d ohne Foto"
          % (downloaded, skipped, len(failed)))
    if failed:
        print("Ohne Foto: %s" % ", ".join(failed))
    print("Pruefung:")
    ok = validate(photos, photos_dir, set(types))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
