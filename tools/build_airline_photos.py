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
1200 x 700 px, Querformat, Lizenz in der Whitelist, Autor bekannt, die
Airline kommt in Titel oder Beschreibung vor und der Titel nennt keine
andere Airline aus dem Katalog (Markenyamilien wie "Jetstar" in "Jetstar
Asia Airways" zählen nicht als fremd). Wertung: Name im Titel vor Name in
der Beschreibung, Flugzeugtyp im Titel, Bildbreite, Seitenverhältnis 1,3-2,0,
Lizenz (PD/CC0 vor CC BY vor CC BY-SA/GFDL), Abzüge für Modell/Toy/Papier/
Rendering/Logo/Cockpit/Innenraum/Sitze/Nahaufnahme/Karte/Unfallfoto/
Klassenfoto. Gewählt wird die beste Außenaufnahme eines Flugzeugs der
Airline.

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

# Download als Thumbnail in dieser Breite; harte Mindestmaße für die Filter.
THUMB_WIDTH = 1280
MIN_WIDTH = 1200
MIN_HEIGHT = 700
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

# Abzüge: keine Außenaufnahme eines ganzen Flugzeugs in Bemalung.
NEGATIVE_WORDS = (
    (re.compile(r"\b(models?|modell|modellflugzeug)\b", re.IGNORECASE), -30),
    (re.compile(r"\b(toys?|spielzeug)\b", re.IGNORECASE), -30),
    (re.compile(r"\bdie-?cast\b", re.IGNORECASE), -30),
    (re.compile(r"\bscreenshots?\b", re.IGNORECASE), -30),
    (re.compile(r"\b(papers?|zeichnung|sketch)\b", re.IGNORECASE), -25),
    (re.compile(r"\b(render|rendering|gerendert|3d-?grafik)\b", re.IGNORECASE), -25),
    (re.compile(r"\blogos?\b", re.IGNORECASE), -25),
    (re.compile(r"\bcockpits?\b", re.IGNORECASE), -20),
    (re.compile(r"\binteriors?\b", re.IGNORECASE), -20),
    (re.compile(r"\b(cabins?|kabine)\b", re.IGNORECASE), -15),
    (re.compile(r"\bseats?\b", re.IGNORECASE), -15),
    (re.compile(r"\bengines?\b", re.IGNORECASE), -10),
    (re.compile(r"\bwing ?views?\b", re.IGNORECASE), -10),
    (re.compile(r"\bdecals?\b", re.IGNORECASE), -10),
    (re.compile(r"\bclose-?ups?\b", re.IGNORECASE), -5),
    # Keine Karte und kein Unfallfoto: weder die Wrackkarte von MH 370 noch
    # die Bergungsaktion am Potomac sind ein Foto der Bemalung.
    (re.compile(r"\bmaps?\b", re.IGNORECASE), -25),
    (re.compile(r"\b(crash(es)?|accident|wreck|emergency)\b", re.IGNORECASE), -20),
    # Bergung und Unfalluntersuchung: das Wrack ist nicht die Bemalung.
    (re.compile(r"\b(recovered|recovery|salvage)\b", re.IGNORECASE), -20),
    (re.compile(r"\bntsb\b", re.IGNORECASE), -25),
    # Militär: Übung oder Waffenträger ist kein Verkehrsflugzeug der Airline.
    (re.compile(r"\b(exercises?|manoeuvres?|maneuvers?|drills?)\b", re.IGNORECASE), -25),
    (re.compile(
        r"\b(military|marine corps|air force|navy|army|luftwaffe|bundeswehr)\b",
        re.IGNORECASE,
    ), -25),
    (re.compile(r"\b(first|business|economy|premium)\s*class\b", re.IGNORECASE), -15),
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


def candidate_score(page, info, variants, other_airlines):
    """Wertung eines Commons-Treffers; None, wenn er die harten Filter nicht
    besteht (kein JPEG/PNG, zu klein, Hochformat, Lizenz nicht erlaubt, Autor
    unbekannt, kein Thumbnail, keine Lizenz-URL ermittelbar, Airline kommt
    nirgends vor oder der Titel nennt eine andere Airline). Rückgabe
    (score, license_url)."""
    if info.get("mime") not in ("image/jpeg", "image/png"):
        return None
    width = int(info.get("width") or 0)
    height = int(info.get("height") or 0)
    if width < MIN_WIDTH or height < MIN_HEIGHT or width <= height:
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
    return score, license_url


def search_commons(query):
    """Sucht im Dateinamensraum, liefert (page, imageinfo)-Paare."""
    data = http_get_json({
        "action": "query",
        "generator": "search",
        "gsrsearch": query,
        "gsrnamespace": "6",
        "gsrlimit": str(SEARCH_LIMIT),
        "prop": "imageinfo",
        "iiprop": "url|size|extmetadata|mime",
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


def find_photos(iata, names, catalog, delay, want=1, exclude_urls=()):
    """Sucht bis zu `want` verschiedene Fotos einer Airline.

    Rückgabe: Liste (page, imageinfo, score, license_url), absteigend nach
    Wertung. `exclude_urls` sind Quell-URLs schon vorhandener Fotos (einer
    Galerie) - dieselbe Datei landet nicht zweimal. `names` ist die Liste der
    Namen, die probiert werden (Katalogname, dann der englische
    Wikipedia-Titel als Fallback für veraltete Katalognamen). Pro Name läuft
    die Anfragereihe (siehe query_ladder); die erste Stufe mit einem
    brauchbaren Treffer gewinnt.
    """
    exclude = set(exclude_urls)
    self_variants = [v for name in names for v in name_variants(name)]
    other_airlines = other_airline_pattern(catalog, iata.upper(), self_variants)
    found = []
    for name in names:
        variants = name_variants(name)
        for query in query_ladder(name):
            if len(found) >= want:
                return found
            candidates = []
            for page, info in search_commons(query):
                scored = candidate_score(page, info, variants, other_airlines)
                if scored is None:
                    continue
                score, license_url = scored
                title = page.get("title", "")
                if title.startswith("File:"):
                    title = title[len("File:"):]
                if source_url(title) in exclude:
                    continue
                key = (-score, -int(info.get("width") or 0), title)
                candidates.append((key, page, info, score, license_url, title))
            candidates.sort(key=lambda item: item[0])
            added = 0
            for _, page, info, score, license_url, title in candidates:
                if len(found) >= want:
                    break
                found.append((page, info, score, license_url))
                exclude.add(source_url(title))
                added += 1
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", default=CATALOG_PATH)
    parser.add_argument("--facts", default=FACTS_PATH)
    parser.add_argument("--photos-dir", default=PHOTOS_DIR)
    parser.add_argument("--manual", default=MANUAL_DIR)
    parser.add_argument("--only", help="Komma-getrennte IATA-Codes statt Top 250")
    parser.add_argument("--limit", type=int, default=TOP_N)
    parser.add_argument("--force", action="store_true", help="Vorhandene neu laden")
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
            found = find_photos(iata, names, catalog, args.delay, want=1)
        except RuntimeError as error:
            print("%s: Suche fehlgeschlagen (%s)" % (iata, error))
        if not found:
            print("%s: kein brauchbares Foto gefunden (%s)" % (iata, name))
            failed.append(iata)
            time.sleep(args.delay)
            continue
        page, info, score, license_url = found[0]
        metadata = info.get("extmetadata") or {}
        title = page.get("title", "")
        if title.startswith("File:"):
            title = title[len("File:"):]
        author = strip_html((metadata.get("Artist") or {}).get("value", ""))
        license_short = (metadata.get("LicenseShortName") or {}).get("value", "").strip()
        try:
            data = download(info["thumburl"])
        except RuntimeError as error:
            print("%s: Download fehlgeschlagen (%s)" % (iata, error))
            failed.append(iata)
            time.sleep(args.delay)
            continue
        width, height, resized = convert_to_webp(data, target)
        entry = catalog[iata]
        photos[iata] = {
            "icao": (entry.get("icao") or "").upper(),
            "photos": [{
                "file": iata + ".webp",
                "author": author,
                "license": license_short,
                "licenseUrl": license_url,
                "sourceUrl": source_url(title),
                "title": title,
                "modified": resized,
                "width": width,
                "height": height,
            }],
        }
        downloaded += 1
        print("%s: %s (%dx%d, %.0f, %s, %s)"
              % (iata, title[:60], width, height, score, license_short, author[:30]))
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
                found = find_photos(
                    iata, names, catalog, args.delay, want=want,
                    exclude_urls=exclude_urls,
                )
            except RuntimeError as error:
                print("%s: Galerie-Suche fehlgeschlagen (%s)" % (iata, error))
            added = 0
            for page, info, score, license_url in found:
                position = len(gallery) + 1
                target = os.path.join(photos_dir, photo_filename(iata, position))
                metadata = info.get("extmetadata") or {}
                title = page.get("title", "")
                if title.startswith("File:"):
                    title = title[len("File:"):]
                author = strip_html((metadata.get("Artist") or {}).get("value", ""))
                license_short = (metadata.get("LicenseShortName") or {}).get("value", "").strip()
                try:
                    data = download(info["thumburl"])
                except RuntimeError as error:
                    print("%s: Galerie-Download fehlgeschlagen (%s)" % (iata, error))
                    time.sleep(args.delay)
                    continue
                width, height, resized = convert_to_webp(data, target)
                gallery.append({
                    "file": photo_filename(iata, position),
                    "author": author,
                    "license": license_short,
                    "licenseUrl": license_url,
                    "sourceUrl": source_url(title),
                    "title": title,
                    "modified": resized,
                    "width": width,
                    "height": height,
                })
                added += 1
                print("%s: Galerie %d/%d: %s (%dx%d, %.0f, %s, %s)"
                      % (iata, position, GALLERY_PHOTOS, title[:50], width, height,
                         score, license_short, author[:30]))
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
