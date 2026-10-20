# Manuelle Airline-Logos

Logos für die Kachel *Fluggesellschaften* werden **nicht automatisch
heruntergeladen** — automatische Zuordnungen treffen zu oft die falsche
Airline (ICAO-Codes kollidieren, z. B. „BER" bei Air Berlin und Fly Air41
Airways; veraltete Katalog-Codes wie SN/DAT statt BEL). Sie werden von Hand
kuratiert:

1. Logo-Datei nach **IATA-Code** benennen, z. B. `SN.png`, `AB.webp`
   (png, jpg oder webp; Alpha/Transparenz bleibt erhalten).
2. `python3 tools/sync_airline_logos.py` ausführen.

Das Skript konvertiert die Datei nach webp (max. 96 px Höhe), legt sie als
`app/src/main/assets/airlines/liveries/<ICAO>.webp` ab (ICAO aus dem Katalog)
und trägt sie im `file`-Feld von `airlines.json` ein. Danach prüft es die
gesamte Zuordnung (fehlende Dateien, falsche Namen).

Quelle der aktuell hier liegenden Logos: Wikimedia Commons (aus den SVG
gerastert, alle Public Domain).
