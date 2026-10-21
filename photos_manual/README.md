# Manuelle Airline-Fotos

Fotos für die Detailseite *Fluggesellschaften* werden automatisch von
Wikimedia Commons geladen (`tools/build_airline_photos.py`, Top 250 nach
Flottengroesse). Wo die automatische Suche nichts Brauchbares findet oder ein
besseres Foto bekannt ist, wird es von Hand kuratiert:

1. Foto-Datei nach **IATA-Code** benennen, z. B. `LH.jpg`
   (png, jpg oder webp; wird nach webp konvertiert, max. 1280 px breit).
2. Attribution danebenlegen, z. B. `LH.json`:

   ```json
   {
     "author": "Matti Blume",
     "license": "CC BY-SA 4.0",
     "licenseUrl": "https://creativecommons.org/licenses/by-sa/4.0",
     "sourceUrl": "https://commons.wikimedia.org/wiki/File:...",
     "title": "Lufthansa Boeing 747-8 ...",
     "modified": false
   }
   ```

   Pflichtfelder: `author`, `license`, `licenseUrl`, `sourceUrl`. Erlaubt sind
   Public Domain, CC0, CC BY und GFDL (nicht NC/ND). `modified` ist true, wenn
   das Foto gegenüber dem Original verkleinert oder zugeschnitten wurde.
3. `python3 tools/build_airline_photos.py` ausführen.

Das Skript installiert die manuellen Fotos bei jedem Lauf (sie gewinnen gegen
den Download) und prüft danach die gesamte Zuordnung (fehlende Dateien,
unvollständige Attribution, verwaiste Dateien).

Quelle der Fotos: Wikimedia Commons.
