# Projektkonventionen Logbook

Android-App (`com.highfly.logbook`), Kotlin + Views/ViewBinding, Material 3, Navigation-Component.
`minSdk 29`, `compileSdk`/`targetSdk 37`. Code-Kommentare und Strings auf Deutsch, englische
Varianten in `values-en`.

## Bauen, Prüfen, Testen

```bash
./gradlew :app:assembleDebug        # Debug-APK
./gradlew :app:testDebugUnitTest    # Unit-Tests
./gradlew :app:lintDebug            # Android Lint
./gradlew :app:assembleRelease      # unsigned Release: app/build/outputs/apk/release/
```

Release-APKs sind mit `*.apk`/`*.idsig` in `.gitignore` und werden nicht committet.

## Releases

1. `versionCode` und `versionName` in `app/build.gradle.kts` erhöhen. Der `versionCode` muss
   monoton steigen, sonst lehnt Android ein Update ab bzw. Obtainium erkennt es nicht.
2. `./gradlew :app:assembleRelease` bauen.
3. Die **bereits signierte** APK **immer in den Ordner `Releases/`** legen, nicht in den
   Projektroot – `assembleRelease` signiert selbst (siehe Signatur unten):
   ```bash
   cp app/build/outputs/apk/release/app-release.apk Releases/app-release-vX.Y.Z.apk
   ```
   Name des Release-Ordners ist Plural: `Releases/`.
4. Commit-Message ist die reine Version (`v0.0.3`), Branch `main`.
5. Hochladen und Tag-Erstellung auf GitHub übernimmt der Nutzer.

**Signatur:** Seit v0.0.32 signiert Gradle die Release-APK selbst über den `signingConfigs`-Block
in `app/build.gradle.kts`; Keystore, Alias und Passwörter kommen aus `keystore.properties` im
Projektroot (in `.gitignore`, nie committen). Das Zertifikat lautet
`CN=Logbook, OU=highfly, O=highfly, L=Berlin, ST=Berlin, C=DE`, SHA-256 `1f269077…`, und muss
unbedingt dasselbe bleiben – sonst verweigert Android das Update bzw. meldet Obtainium
"Signing certificate mismatch". Die fertige APK **nicht** noch einmal mit `apksigner` und dem
Debug-Keystore überschreiben: `~/.android/debug.keystore` (`C=US, O=Android, CN=Android Debug`,
`9f8aff1a…`) war nur bis v0.0.31 in Gebrauch und überschreibt die richtige Signatur. Die
Ausgabe von `assembleRelease` heißt `app-release.apk` (signiert, ohne `-unsigned`), ein `.idsig`
entsteht nicht.

## Logo und Launcher-Icons

`tools/logo_gen.py` erzeugt alle Icons aus einer einzigen Geometrie (Aufruf
`python3 tools/logo_gen.py`, Vorschau-PNGs landen in `/tmp/opencode/logo/`). Es gibt die drei
Entwürfe `logbuch`, `orbit` und `steigflug`; aktiv ist `DEFAULT = "logbuch"`. Das Manifest
zeigt auf `@mipmap/ic_launcher` bzw. `@mipmap/ic_launcher_round`, beide `mipmap-anydpi`-XMLs
verweisen auf den aktiven Entwurf – `anydpi` hat Vorrang vor den alten `webp`-Icons in den
`mipmap-*dpi`-Ordnern, diese liegen nur noch als Fallback dort.

Der aktive Entwurf: das offene Buch steht in der Mitte (`BOOK_SHIFT`); darüber schwebt ein weißes
Flugzeug – verankert mit `side_plane_from_tail` (`PLANE_LEN`/`PLANE_ANG`/`PLANE_TAIL`) und über das
Buch gezeichnet. `center_plane` zentriert die fertige Silhouette mittig über dem Buch (horizontale
Mitte auf die Buchmitte, Unterkante auf Höhe der Buch-Oberkante); das Flugzeug steigt flach nach
rechts oben. Die ganze
Szene skaliert `SCENE`, damit das Icon die Maske gut füllt. Das Monochrom-Themen-Icon entsteht
aus derselben Geometrie ohne Falz und Seitenlinien. Die erzeugten `ic_launcher_*.xml` in
`drawable/` tragen den Hinweis „Nicht von Hand aendern" – immer das Skript neu laufen lassen.

Das Skript schreibt zusätzlich `drawable/ic_logo_round.xml`: dasselbe Icon als Vollkreis
(der sichtbare Kreis r=36 des adaptiven Icons auf r=54 gezogen). Es steht in den Einstellungen
über der Version (`iv_settings_logo` in `fragment_profile.xml`) und auf dem Begrüßungsbildschirm
folgt das Logo weiterhin `@mipmap/app_logo` (`values/app_logo.xml`).

## Flughäfen und geschlossene Flughäfen

Flughäfen sind reine Assets unter `app/src/main/assets/airports/`: `airports.json`
(IATA → `[lat,lon]`), `airports_countries.json` (IATA → ISO) und `airport_names.json`
(IATA → Name/Stadt). `AirportData` und `AirportNames` laden sie zur Laufzeit in Maps; ein
`LogbookEntry` speichert nur den Code als Text.

Ein **geschlossener** Flughafen bekommt ein `X` vor seinen damaligen Dreilettercode
(München-Riem → `XMUC`). Ein offener Flughafen behält seinen Code (der neue Flughafen München
bleibt `MUC`). Da ein echter IATA-Code immer genau drei Zeichen hat, kollidiert `X`+Code nie
mit einem offenen Flughafen. Die geschlossenen stehen in `airports_closed.json` und werden beim
Laden an die bestehenden Maps angehängt, sodass Koordinaten, Land, Name, Distanz, Karte und
Import automatisch funktionieren. Erzeugt wird die Datei von `tools/build_closed_airports.py`. Die damaligen Codes kommen
hauptsächlich aus dem `keywords`-Feld des OurAirports-Bestands – OurAirports hat die entfernten
IATA-Codes dorthin verschoben (Riem: `"EDDM, MUC, XMUC"`). Ein echter, ehemaliger Code ist
eindeutig, kommt also nur auf einer Anlage vor; Wörter wie `OIL`/`AFB` fallen dadurch heraus.
Weitervergebene Codes (der Code gehört heute einem anderen Flughafen, z. B. `MUC`) stehen in der
handgepflegten Liste `REASSIGNED`. Ein älterer OurAirports-Snapshot (z. B. von 2021), dessen
geschlossene Zeilen den damaligen Code direkt führten, lässt sich zusätzlich über `--old`
einspielen – der Generator ordnet ihn per `ident`, notfalls per Koordinate, zu. Die FROM/TO-
Felder in *Neuer Flug* erlauben vier Zeichen nur, wenn das erste `X` ist
(`AddEntryFragment.airportCodeLengthFilter`).

## Flughafen-Detailseite – Satellitenbilder

`AirportDetailFragment` zeigt zwischen den Kenndaten (IATA, ICAO, Höhe, Koordinaten) und der
Verbindungskarte eine eigene Kachel mit dem Satellitenbild des Flughafens. Es kommt als
vorgepacktes Asset aus `app/src/main/assets/airport_satellite/` (`<CODE>.webp` plus
`<CODE>.json` mit den Randkoordinaten `min_lat`/`min_lon`/`max_lat`/`max_lon`). Fehlt das Bild,
bleibt die ganze Kachel weg. `AirportSatellite` lädt Bild und Randkoordinaten (begrenzter
`LruCache`, nur wenige Bilder gleichzeitig), `AirportSatelliteView` legt das Bild in
Web-Mercator-Maßstab in die Ansicht, sodass der Flughafen mittig sitzt, und markiert ihn mit
einer Nadel; die Kachel blendet sich sanft ein. Der Quellennachweis
(`satellite_credit_cloudless`, englisch, `translatable="false"`) steht in der Kachel unter dem
Bild und ist die volle von EOX verlangte Namensnennung samt Link:
„Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus
Sentinel data 2016 & 2017, CC BY 4.0)“ – der Copernicus-Hinweis erfüllt zugleich den Quellenvermerk
der Sentinel-Datenlizenz (Weitergabe nur mit Quellenangabe).

Erzeugt werden die Bilder von `tools/build_airport_satellite.py` (einmalig lokal, die App lädt
nichts nach). Quelle sind die Sentinel-2-Cloudless-Kacheln von EOX IT Services GmbH; verwendet
werden **nur die 2016er und 2017er Ausgaben**, weil nur diese unter **CC BY 4.0** stehen (ab 2018
gilt CC BY-NC-SA 4.0). Keine der beiden deckt alles ab: Die 2017er-Kacheln sind an rund der
Hälfte der Ziele weiß, die 2016er-Ausgabe ist dort geschlossen, deshalb nimmt das Skript die
2017er-Ausgabe und weicht nur bei weißen Lücken auf 2016 aus. Der Ausschnitt ist pro Flughafen
verschieden: Ein quadratischer Kasten in Web-Mercator umfasst die Pisten-Endpunkte (OurAirports
`runways.csv`, Public Domain) samt Flughafenkoordinate plus 1,5 km Rand, gedeckelt auf 8–18 km
Kantenlänge, und liegt mittig auf diesem Gebiet – so bleibt auch bei Riesen wie DEN oder SVO der
ganze Flughafen im Bild. Die Ausgabe ist 12,5 m/px breit, 640–1280 px, Zoomstufe 14 (mehr als die
~10 m Auflösung von Sentinel-2 bringt ohnehin nichts). Das Skript holt die Koordinaten
aus `airports.json` (`FALLBACK_COORDS` für sehr neue Flughäfen, die dort noch fehlen). Der
Zuschnitt und die Projektion prüft `AirportSatelliteAssetTest`; das Skript muss nach jeder
Änderung neu laufen.

## Flughafen-Detailseite – Abflug-Animation der Verbindungskarte

Unter dem Satellitenbild zeigt `AirportDetailFragment` die Verbindungen des Flughafens auf einer
`CountryRouteMapView` (dieselbe Karte wie die Länderseite, hier dicht auf den Flughafen gezoomt,
`focusAirportClose`). Auf dieser Seite starten zusätzlich fortlaufend kleine Flugzeuge vom
hervorgehobenen Flughafen in Richtung jeder Verbindung und ziehen eine nach vorn kräftiger
werdende Spur hinter sich her. Eingeschaltet wird die Animation allein von
`AirportDetailFragment` über `playDepartureAnimation()`; auf der Länderseite bleibt sie aus.

Jede Verbindung ist eine eigene Bahn; `RouteDeparture.planeFraction` verteilt die Startzeiten über
`DEPARTURE_STAGGER`, sodass die Flugzeuge nacheinander starten statt alle zugleich, und lässt ein
Flugzeug nach `DEPARTURE_TRAVEL_FRACTION` des Umlaufs kurz pausieren. Die reine Rechnung –
Länge des projizierten Streckenzugs, Punkt und Fahrtrichtung darauf, Zeitplan – steht in
`RouteDeparture` ohne Android und wird von `RouteDepartureTest` geprüft. Der Streckenzug wird pro
Bild über `Projection.toPixels` in Bildschirmpunkte gelegt (ein `scratch`-Puffer wächst bei
Bedarf); ein Flugzeug legt höchstens `DEPARTURE_TRAVEL_SCREEN_FRACTION` der kleineren
Bildschirmkante zurück, die Spur ist `DEPARTURE_TRAIL_SCREEN_FRACTION` lang – so bleibt der Start
auch dicht am Flughafen sichtbar. Die Spur ist ein `LinearGradient` von durchsichtig (hinten) zur
Akzentfarbe (vorn); das Symbol wird wie bei `PlaneBackgroundView` in Fahrtrichtung gedreht
(`RouteMiniMapView.planeRotation`). Ist der hervorgehobene Flughafen aufgehoben (Tipp auf die
Karte, `zoomToFocus`), gibt es keine Bahn mehr.

Weil ein Animationsbild die ganze Karte samt Land- und Grenzüberlagerung neu zeichnet, stößt ein
`postDelayed`-Lauf nur rund `DEPARTURE_FRAME_MS` (etwa 30 Bilder in der Sekunde) ein
Neuzeichnen an; die Position kommt aus der Uhr (`SystemClock.uptimeMillis`), nicht aus der
Bildzahl, das Tempo bleibt also gleichmäßig. Der Lauf pausiert mit `onWindowVisibilityChanged`
und `onPause`, endet mit `onDetach` und schweigt, wenn der Nutzer Systemanimationen abgeschaltet
hat (`Settings.Global.ANIMATOR_DURATION_SCALE` = 0).

## Seite „Hangar" – Detailseite

Die Hangar-Kachel öffnet `TileDetailFragment` mit den Flugzeugtypen bzw. Registrierungen; ein Tipp
auf eine Zeile öffnet `AircraftFlightsFragment` (`fragment_flight_route_detail.xml`). Beide Ansichten
sind dieselbe Seite mit anderem `mode` (`MODE_TYPE`/`MODE_REG`); die Zahlen ranken nicht, denn
dieselbe Zahl gibt es nur einmal je Typ bzw. Registrierung.

Nur beim Flugzeugtyp (`MODE_TYPE`) steht **über** der Karte ein Abschnitt, der nicht vom Logbuch
abhängt: der Anzeigename des Typs, darunter ein Foto des Typs samt Namensnennung und darunter die
allgemeinen Stammdaten (Hersteller, Baubeginn, Erstauslieferung, Erstkunde, Wikipedia-Link) - siehe
Abschnitt *Flugzeugtypen – Stammdaten und Fotos*. Bei einer Registrierung bleibt der Abschnitt weg;
hat das Asset zu dem Typ weder Foto noch Stammdaten, bleibt er unsichtbar.

Darunter die animierte Karte mit allen Flügen dieses Typs bzw. dieser Registrierung, direkt darunter
die Kennzahlen (Flüge, Gesamtstrecke, Gesamtflugzeit, Zeitraum) - ohne freie Fläche dazwischen.

Unter den Kennzahlen steht zusätzlich ein Zeitstrahl **aller** Flüge dieser Registrierung bzw.
dieses Typs, der jüngste oben, im Aufbau der Layover-Detailseite: dieselbe Zeile
`item_layover_timeline` mit Datum, Dreiletterroute, Fluggesellschaft/-nummer, Reisebuddy und im
Punkt die Flugzeit (unter einer Stunde in Minuten, sonst in Stunden mit einer Nachkommastelle,
`aircraft_flight_dot_minutes`/`aircraft_flight_dot_hours`). Ein Tipp öffnet den Eintrag zum
Bearbeiten (`action_aircraft_flights_to_add_entry`). Titel und Liste bleiben unsichtbar, wenn es
keine passenden Flüge gibt. Gelesen wird die Liste bei jedem `onResume` neu aus
`DashboardStats.dashboardEntries`, damit ein gerade bearbeiteter Eintrag sofort richtig erscheint.
Die anderen Nutzer desselben Layouts (`FlightRouteDetailFragment`, `TravelBuddyDetailFragment`)
lassen Titel und Liste unsichtbar.

## Flugzeugtypen – Stammdaten und Fotos

Die Detailseite eines Flugzeugtyps (Hangar → Typ) zeigt über der Karte - wie die
Airline-Detailseite - allgemeine Stammdaten und ein Foto; beides hängt nicht vom Logbuch ab.
Grundlage sind zwei Assets unter `assets/aircraft/`:

- `aircraft_facts.json`: je kanonischem Typ-Code Anzeigename, Hersteller, Baubeginn (Jahr),
  Erstauslieferung (`YYYY-MM-DD`, `YYYY-MM` oder `YYYY`) und Erstkunde sowie der Link zur
  englischen Wikipedia-Seite, dazu `aliases` (normalisierte Codes → Typ). Erzeugt wird die Datei
  von `tools/build_aircraft_facts.py` aus der englischen Wikipedia (CC BY-SA 4.0), gelesen wird
  die Infobox `{{Infobox aircraft}}` im Wikitext; Artikelnamen werden über Redirects aufgelöst
  ("Sukhoi SuperJet 100" → "Yakovlev SJ-100"). Die 50 bekanntesten Typen sind die Familien aus
  `aircraft_seats.json` plus die klassischen Jets und Turboprops. Fünf Typen bekommen einen festen
  Hersteller-Override (`MANUFACTURER_OVERRIDE`), wo die Infobox die Fabrik statt des
  Konstruktionsbüros nennt (Tu-154 → Tupolev, Il-86/Il-96 → Ilyushin, Yak-42 → Yakovlev, Superjet →
  Sukhoi). **Abarten sind eigene Typen**: A320neo, A321neo, A330neo, B737MAX, E190E2 und E195E2
  stehen neben ihren Basisfamilien (A320, A321, A330, B737, E190, E195), damit ceo und neo nicht
  zusammenfallen; die Varianten-Codes (`20N`/`19N`, `21N`, `33X`/`338`/`339`, `7M7`-`7M9`/`37M`-`39M`,
  `E190E2`/`E195E2`) stehen als Aliase in `EXTRA_ALIASES` und gewinnen dort gegen
  `aircraft_seats.json`. Die Boeing-Kurzschreibweise „737-8" (MAX 8) hat bewusst keinen Alias:
  normalisiert „7378" ist ein Präfix von „737800" (737-800 NG) und würde die NG-Varianten falsch
  als MAX erkennen.
- `photos/<CODE>.webp` + `<CODE>_launch.webp` + `photos/photos.json`: **zwei Fotos je Typ** in
  einem `photos`-Array je Eintrag, je Foto mit `kind` (`manufacturer` oder `launch`) samt
  Attribution (Autor, Lizenz, Lizenz-URL, Quell-URL, Aufnahmedatum, Maße). Das Hersteller-Foto
  (`<CODE>.webp`) zeigt den Flieger in der Werks-/Hauslackierung (Airbus/Boeing ohne Airline),
  das Erstkunden-Foto (`<CODE>_launch.webp`) ein Flugzeug der Airline, die den Typ zuerst
  übernahm (`launchCustomer` aus den Stammdaten, z. B. Lufthansa für die A320neo, Malindo Air
  für die 737 MAX). Typen ohne Erstkunden-Foto haben nur eines. Erzeugt wird es von
  `tools/build_aircraft_photos.py` von Wikimedia Commons (Lizenz-Whitelist wie bei den
  Airline-Fotos: PD, CC0, CC BY, GFDL - nicht NC/ND; harte Filter: JPEG/PNG, mindestens
  1200×700 px, Querformat bis 2,5:1, bekannter Autor, Typ-Token im **Titel** - eine Erwähnung nur
  in der Beschreibung ist meist ein Foto eines anderen Flugzeugs; harte Ausschlüsse für
  Modell/Cockpit/Kabine/Karte/Unfall/Militär sowie Titel, die ein Triebwerk, Rumpfteil oder
  Denkmal nennen; Wertung mit Zuschlag für junge Fotos, Ziel höchstens etwa drei Jahre; beim
  Hersteller-Foto zusätzlich +Zuschlag, wenn keine Airline im Titel steht, beim Erstkunden-Foto
  +Zuschlag für den Namen des Erstkunden). Manuelle Vorgaben gewinnt über
  `aircraft_photos_manual/<CODE>.jpg` + `<CODE>.json` im Repo-Root.

`AircraftFacts` lädt die Stammdaten einmalig und löst den eingetragenen Typ über
`AircraftFacts.Catalog.resolve` auf den kanonischen Code auf (wie `AircraftSeats.Catalog.seatsFor`:
Familien, Varianten, Herstellerwörter, Alias-Codes; die Schlüsselschreibweise bleibt erhalten,
"Saab340"). **Reihenfolge: Alias exakt vor Code-Präfix** - sonst würde „A319neo" auf den Code
„A319" treffen statt auf den Alias „A319NEO" → „A320neo" (A319neo gehört zur A320neo-Familie,
nicht zur A319). `AircraftPhotos` lädt die Foto-Liste zum Code (begrenzter `LruCache`, RGB_565).
In der App blättert eine Galerie (`ViewPager2` mit Punkt-Indikatoren wie bei der Airline-Detailseite,
`vp_aircraft_photo` in `fragment_flight_route_detail.xml`) zwischen Hersteller- und
Erstkunden-Foto; unter dem Bild steht die Namensnennung `© Autor · Lizenz · bearbeitet · Wikimedia
Commons`, die mit der Seite wechselt, ein Tipp darauf öffnet die Commons-Dateiseite; die
Stammdaten-Zeilen entstehen aus `item_airport_fact.xml`, der Wikipedia-Link öffnet den Artikel
im Standardbrowser. Geprüft werden beide Assets von `AircraftFactsTest` und
`AircraftPhotosAssetTest`.

## Weltkarte – Layover-Animation und Filter-Zurücksetzen

Auf der Weltkarte (`WorldMapFragment`) startet mit dem Filter Layover = „Ja" eine Animation: Vom
Flughafen mit den meisten Layover-Abflügen (der Hub) starten kleine Flugzeuge (Icon `ic_flight`) in
Richtung jedes Layover-Ziels und ziehen eine nach vorn kräftiger werdende Spur hinter sich her
(`LinearGradient` von durchsichtig zur Akzentfarbe). Sind die Flugzeuge angekommen, bleiben die
Spuren nur noch kurz stehen und blenden sich dann aus - nur die Dreilettercodes der Ziele
bleiben sichtbar. Solange der Animationsplan aktiv ist, werden **keine** statischen Routenlinien
gezeichnet: Mit Layover = „Ja" bestehen die gefilterten Linien nur aus Layover-Strecken (auch
Rückflüge und Anschlussflüge, die nicht am Hub starten), und eine statische Linie wäre schon vor
der Animation sichtbar und ginge nach ihr nicht weg. Ein Knopf über dem Hineinzoomen-Knopf
(`btn_layover_trails`, `ic_routes`, in der rechten unteren Knopfspalte) blendet die Spuren erneut
ein. Die reine Planung (Hub-Wahl,
Zielmenge, Flugzeiten, Phasen) steht ohne Android-Abhängigkeiten in `LayoverAnimation.kt` (geprüft
von `LayoverAnimationTest`), das Zeichnen in der inneren Klasse `LayoverOverlay` des
`WorldMapFragment`; ein `postDelayed`-Lauf stößt nur rund `LAYOVER_FRAME_MS` (etwa 30 Bilder pro
Sekunde) ein Neuzeichnen an, die Position kommt aus der Uhr. Die Animation pausiert mit `onPause` und
`onWindowVisibilityChanged` und schweigt, wenn der Nutzer Systemanimationen abgeschaltet hat
(`Settings.Global.ANIMATOR_DURATION_SCALE` = 0).

Der Knopf zum Zurücksetzen des Filters (`btn_filter_reset`, Pfeil zurück) sitzt oben rechts **links
neben** dem Filterknopf und ist nur sichtbar, solange ein Filter aktiv ist; ein Tipp darauf setzt den
Filter zurück (`activeMapFilter = MapFilter()` samt `applyFilters()`).

## Seite „Fluggesellschaften"

Die Kachel *Fluggesellschaften* öffnet `AirlinesFragment` (`fragment_airlines.xml`), nicht mehr
`TileDetailFragment`: eine Rangliste im Aufbau der Reisebuddy-Liste (`item_airline.xml` +
`AirlineAdapter` mit `RankBadgeView` und `VisitedCountryBarView`), absteigend nach Flugzahl, der
Balken gemessen am Maximum der ungefilterten Liste. Oben rechts im Kopf sitzt eine Lupe wie bei
den Flughäfen: Sie klappt ein Suchfeld aus, das im gesamten Katalog (`AirlineCatalog.search` über
`assets/airlines/airlines.json`) nach IATA-Code, ICAO-Code und Name sucht - "LH", "DLH" und
"Lufthansa" finden dieselbe Airline. Auch nie geflogene Airlines erscheinen dann mit 0 Flügen.
Ein Tipp auf eine Zeile öffnet `AirlineDetailFragment` (`action_airlines_to_detail`); beim
Zurückkehren setzt die Liste an der gemerkten Scrollposition wieder auf (wie die Länderliste,
`savedScrollPosition` in `AirlinesFragment`).
In der Suche trägt das Rangzeichen keine Medaille, sondern den Rang der **ungefilterten Liste**
(`AirlineAdapter.showMedals` / `RankBadgeView.setRank(rank, showMedals)`; der Rang kommt über
`AirlineAdapter.submit(list, maxFlights, ranks)` aus der Gesamtliste, eine nie geflogene Airline
trägt keinen Rang) - dasselbe gilt für die Suche der Reisebuddies (`TravelBuddyAdapter`) und der
Kacheln Layover, Flughäfen und Hangar (`BarChartView.setShowMedals`, Rang fest über
`BarChartView.Item.rank` aus der Gesamtliste; die Länderliste rechnet ihn ohnehin über
`VisitedCountries.rankOf` aus der kompletten Liste). Die Suchergebnisse werden also nie nach
ihrer Position in der Trefferliste neu durchnummeriert.

Geöffnet zeigt die Suche ein X statt der Lupe (`ic_close`, Beschriftung `search_clear_desc`): Ein
Tipp darauf löscht die Eingabe und schliesst das Feld. Dieselbe Konvention gilt auf der Seite
*Einträge* (`btnEntrySearch`) und der Länderliste (`btnVisitedCountriesSearch`), die schon immer so
gewechselt haben.

Die Logos liegen als webp unter `assets/airlines/liveries/`, benannt nach dem **ICAO-Code**
(`<ICAO>.webp`); das `file`-Feld in `airlines.json` zeigt darauf. Heruntergeladen werden sie
**nicht automatisch**: ICAO-Codes kollidieren (Wikidata führt „BER" sowohl für Air Berlin als auch
für Fly Air41 Airways; ein früherer Auto-Download legte deshalb das air41-Logo unter AB) und der
Katalog enthielt veraltete Einträge (SN stand auf dem Vorgänger-Code „DAT" von SN Brussels
Airlines, VL auf der 2016 aufgelösten Air VIA, 4Y auf Airbus France). Kuratiert werden die Logos
von Hand im Ordner `liveries_manual/` (Datei nach IATA-Code benannt, z. B. `SN.png`, siehe README
dort); `tools/sync_airline_logos.py` konvertiert sie nach `liveries/<ICAO>.webp` (max. 96 px
Höhe), trägt das `file`-Feld ein und prüft die gesamte Zuordnung (fehlende Dateien, nicht nach
ICAO benannte Dateien, Verwaiste). Quelle der Logos ist Wikimedia Commons (Public Domain).
`AirlineLogosAssetTest` prüft die Zuordnung.

Die Gruppierung läuft über `AirlineStats` (ohne Android, getestet in `AirlineStatsTest`): Ein
gespeicherter Airline-Text wird über `AirlineCatalog.resolve` auf den kanonischen IATA-Code
aufgelöst, "LH" und "DLH" landen in derselben Zeile; unbekannte Codes bleiben als eigener Rohtext
stehen. Wie bei den Reisebuddies zählt die Flugzahl jeden Eintrag, Distanz und Flugzeit dagegen
nur echte Flüge (kein Ground Transfer).

Die Detailseite (`fragment_airline_detail.xml`) zeigt oben mittig das Logo aus
`assets/airlines/liveries/` (`AirlineCatalog.loadLogo`, im Dark Mode aufgehellt wie in der
Eintragsliste; ohne Logo steht der Code gross wie auf der Flughafen-Detailseite), darunter Name
und die Codes ohne Beschriftung - IATA, ICAO, dahinter das Rufzeichen in geraden
Anführungszeichen (`LH · DLH · "Lufthansa"`, `AirlineFacts.displayCallsign` macht aus den
gespeicherten Grossbuchstaben die Wortschreibweise). Ist die Airline insolvent, trägt sie rechts
neben dem Logo einen roten, schrägen **„Insolvent"-Stempel** (`tv_airline_insolvent`,
`?attr/colorError` mit Rahmen aus `bg_airline_insolvent_stamp.xml`). Darunter folgt ein
**Foto** eines Fliegers in der Bemalung (Top 250, Wikimedia Commons, offline mitgeliefert in
`assets/airlines/photos/`: `<IATA>.webp` plus die Attribution aller Fotos in `photos.json` mit
Autor, Lizenz, Lizenz-URL und Quell-URL; die Fotos sind nach dem **IATA-Code** benannt, nicht
nach ICAO wie die Logos, weil IATA-Codes nicht kollidieren). Unter dem Bild steht die
Namensnennung `© Autor · Lizenz · Wikimedia Commons` (bei verkleinerten Bildern mit dem Zusatz
*bearbeitet*, `airline_photo_edited`); ein Tipp darauf öffnet die Commons-Dateiseite. Gibt es
kein Foto, bleibt die ganze Kachel weg (`AirlinePhotos` lädt Bilder und Attribution, begrenzter
`LruCache` wie bei `AirportSatellite`; die Kachel blendet sich sanft ein). **Airlines der
Lufthansa Group** (`group` in `airline_facts.json`) haben eine **Galerie** aus drei Fotos
(`<IATA>.webp`, `<IATA>_2.webp`, `<IATA>_3.webp`), die in der Kachel seitlich gewischt werden
(`ViewPager2` mit Punkten unter dem Bild, `AirlineDetailFragment`); die Credit-Zeile wechselt
mit der Seite. In `photos.json` steht pro Airline ein `photos`-Array, jede Datei mit eigener
Attribution und ihrem `file`-Feld. Erzeugt werden die Fotos von
`tools/build_airline_photos.py`: Die Top-250-Liste rechnet das Skript zur Laufzeit aus
`airline_facts.json` (aktiv, also ohne `dissolved`, mit Flottengröße, mit ICAO im Katalog,
absteigend nach Flotte). Quelle ist die Commons-API (Dateinamensraum); andere Foto-Seiten
(planespotters.net, airliners.net) verbieten das Kopieren. Erlaubt sind nur Lizenzen aus der
Whitelist Public Domain, CC0, CC BY und GFDL (nicht NC/ND); die Auswahl sucht Name +
„aircraft" (Katalogname, Fallback englischer Wikipedia-Titel für veraltete Namen wie EU),
filtert hart (JPEG/PNG, mindestens 1200×700 px, Querformat, Lizenz, bekannter Autor, Airline
in Titel oder Beschreibung, keine andere Airline im Titel, keine Nicht-Außenaufnahme wie
Modell/Cockpit/Kabine/Karte/Unfallfoto/Militär) und wertet (Name im Titel, Flugzeugtyp,
Breite, Seitenverhältnis, Lizenz, Abzüge für Nahaufnahme/Triebwerk/Sitze). Stark zählt das
**Aufnahmedatum** (EXIF, Ersatz Datum im Titel bzw. Upload-Datum): Fotos höchstens etwa drei
Jahre alt sollen gewinnen, damit die Bemalung so aktuell wie möglich ist. Das Einzelfoto zeigt
die **Standardlackierung auf einem Flaggschiff-Typ** (Sonderlackierungen wie Star Alliance oder
Retro werden dafür ausgeschlossen, nur als Fallback erlaubt); in der **Galerie** sind
Sonderlackierungen willkommen und werden bevorzugt. `--refresh` prüft die vorhandenen Fotos
und ersetzt sie nur durch nachweislich jüngere (das Datum der gespeicherten Fotos wird dazu
bei Commons nachgezogen). Die Galerie ergänzt das Skript im selben Lauf inkrementell
(`--no-gallery` schaltet sie ab): Airlines der Gruppe mit weniger als drei Fotos bekommen
weitere, unterschiedliche Treffer (ausgeschlossen sind die schon vorhandenen Quell-URLs und
Crop-Varianten desselben Fotos).
Für Airlines, bei denen die Suche nur falsche Treffer findet (Absturzkontext, Militär, fremde
Bemalung), steht `NO_PHOTO` im Skript – sie bekommen kein Foto, ein früherer Eintrag wird
entfernt. Fotos von Hand nachreichen geht wie bei den Logos über
`photos_manual/` im Repo-Root (`<IATA>.jpg` plus `<IATA>.json` mit der Attribution; manuelle
Dateien gewinnen gegen den Download, beim Entfernen wird der Eintrag bereinigt).
`AirlinePhotosAssetTest` prüft die Zuordnung (vorhandene webp-Dateien, vollständige
Attribution, erlaubte Lizenz, Mindestmaße, keine Verwaisten, Abgleich mit dem Katalog,
verschiedene Galerie-Fotos, drei Fotos je aktiver Lufthansa-Group-Airline). Dann
folgen die **allgemeinen Stammdaten**
über der Karte - unabhängig vom Logbuch, also auch für eine noch nie geflogene Airline: die
**Airlinegruppe** (über der Gründung, z. B. „Lufthansa Group" bei LH, „International Airlines
Group" bei BA), Gründung, darunter direkt der **Besitz** („Staatlich", „Aktiengesellschaft" oder
„Privat"), dann das **Insolvenzjahr** (wenn vorhanden), Sitz, Hubs, Flottengröße, Allianz und ein
**Wikipedia-Link** (Artikel der englischen Wikipedia, `AirlineFacts.wikiTitle` zeigt den
Artikelnamen; ein Tipp öffnet die Seite per `Intent.ACTION_VIEW` im Standardbrowser) als Zeilen
aus `item_airport_fact.xml` unter dem Abschnitt *Allgemein*. Zuletzt eine `RouteMiniMapView` wie
bei Reisebuddy mit allen Strecken der Airline, darunter die Zahl der Flüge und Strecken und der
Abschnitt *Deine Flüge* mit den **eigenen** Kenndaten (Flüge, erster/letzter Flug, Ziele, Distanz,
Flugzeit, Flugzeugtypen). Ganz unten auf der Seite, klein (`LabelSmall`), steht der
Quellennachweis der Stammdaten. Ein Tipp auf die Karte öffnet die Weltkarte mit voreingestelltem
Airline-Filter (`filterAirline`-Argument, eigener Filter `airline` in der Weltkarte samt Feld in
`sheet_map_filter.xml`; der Vergleich läuft über `AirlineStats.keyOf`, damit "LH" auch Einträge
mit "DLH" trifft).

Die Stammdaten liegen als Asset unter `assets/airlines/airline_facts.json`, Schlüssel ist der
IATA-Code. Erzeugt wird die Datei von `tools/build_airline_facts.py`: Gründung (P571), Insolvenz
(P576, frühestes Jahr), Sitz (P159, deutsch und englisch), Hubs (P113 als IATA-Code), Allianz
(P114) und Rufzeichen (P432) kommen aus Wikidata (CC0) über den SPARQL-Endpunkt, dazu der
`wiki`-Link aus dem englischen Sitelink des gewählten Eintrags; die Flottengröße kommt aus der
Wikipedia-Infobox (`Flottenstärke`/`fleet_size`, CC BY-SA 4.0 - daher der Quellennachweis ganz
unten auf der Detailseite). Ebenfalls aus Wikidata kommen die **Airlinegruppe** (`group`, der
Mutterkonzern P749 - staatliche Eigentümer wie „Government of Dubai" zählen nicht als Gruppe,
ebenso wenig fachfremde Typen wie eine Bank; bei mehreren Eltern gewinnt der Konzern, den
mehrere Katalog-Airlines tragen, dann „Group"/„Holdings" im Namen) und der **Besitz**
(`ownership`: `state`, wenn ein Staat, Emirat oder Staatsfonds Eigentümer ist (P127/P31) oder
die Airline ein „state-owned enterprise" ist, sonst `public`, wenn Airline oder Eigentümer
börsennotiert sind, sonst `private` bei einem sonstigen Konzern-Eigentümer;
`AirlineFacts.ownershipLabelRes` übersetzt den Schlüssel in die App-Sprache). `AirlineFacts` lädt die Datei einmalig; die Hubs
werden als IATA-Codes gespeichert und über `AirportNames` als Städtenamen in der App-Sprache
angezeigt. Geprüft wird das Asset von `AirlineFactsTest`.

## Karte „CO₂-Ausgleich"

Die Seite zeigt die nötige Pflanzfläche als Kreis in **echtem Maßstab** (Radius in Metern aus
der Fläche) auf einer offline gezeichneten Karte. Damit auch das Umfeld zu sehen ist, füllt der
Wald nur rund 1/4 der kürzeren Bildschirmkante (`FOREST_ZOOM_MARGIN`). Weil der Ausschnitt trotzdem
sehr eng wird, liegen unter `assets/cities_near.json` Ankerorte im Umkreis von 100 km um die
Flughäfen Frankfurt (FRA) und München (MUC); `NearbyCities` lädt sie, die Karte zeichnet die
größten davon mit Namen. Orte außerhalb des Bildes erscheinen als Randhinweis mit Pfeil in
Blickrichtung und Entfernung am Bildrand (`CityEdgeHints`). Erzeugt wird die Datei von
`tools/build_cities_near.py` aus GeoNames `cities5000` (Stadtteile, feature code `PPLX`, fallen
heraus; deutsche/englische Schreibweise kommt aus `cities.json`). Unten links zeigt ein Maßband
(`ScaleBar`) die abgedeckte Entfernung an – nur diese eine Karte nutzt beides.

### Luftbilder (amtliche Orthophotos, umkehrbar)

Damit sich der Waldkreis auch ohne Straßen einordnen lässt, legt die Karte für **nur diese
beiden Ausschnitte** (FRA, MUC) ein amtliches Luftbild (DOP20) unter den Wald: `Orthophotos`
lädt `assets/orthophotos/<CODE>.webp` samt Randkoordinaten (`<CODE>.json`), `OrthophotoOverlay`
zeichnet es über Land und Grenzen, aber unter Wald, Orten und Kennzeichen. Bei kleiner
Zoomstufe (Landesansicht, `< OrthophotoOverlay.MIN_ZOOM`) ist der Ausschnitt nur ein Punkt und
bleibt aus. Liegt das Bild über dem ganzen Ausschnitt, entfällt der eingefärbte Landesumriss
(`ForestOverlay.drawCountry`), sonst läge ein Farbton über dem Foto. Andere Flughäfen haben kein
Bild und verhalten sich wie bisher.

Erzeugt werden die Bilder von `tools/build_orthophotos.py` (Parameter `--box-km`, `--pixels`,
`--only`): Hessen für FRA über `he_dop_rgb` (dl-de/zero-2.0, keine Nennung nötig), Bayern für MUC
über `by_dop20c` (CC BY 4.0, Nennung nötig – daher der Nachweis unter der Karte). Beide Dienste
drosseln schnelle Abfragen (HTTP 404/502); das Skript kachelt und wartet. Der hessische Dienst
bricht oberhalb von rund 1024 px pro Anfrage ab, deshalb dort 1024er-Kacheln. Der Ausschnitt ist
rund 12 km groß bei 2048 px (gut 6 m/px, bewusst grob). Quelle und Größe prüft
`OrthophotosAssetTest`.

**Rückbau** (das Merkmal ist bewusst in sich geschlossen): `Orthophotos.ENABLED` auf `false`
setzen schaltet es ohne weitere Änderung ab (kein Bild, kein Nachweis, keine Ladezeit). Zum
vollständigen Entfernen: `Orthophotos.kt`, `OrthophotoOverlay.kt` und den Ordner
`app/src/main/assets/orthophotos/` löschen, `tools/build_orthophotos.py` löschen, in
`Co2OffsetMapView.kt` die Zeilen mit `orthophotoOverlay` (Feld, `loadOrthophoto`, die Zeile in
`rebuildOverlays`, die Wache in `drawCountry`) entfernen, `OrthophotosAssetTest.kt` löschen und
im Layout/den Strings die drei `co2_offset_credit_*`-Zeilen bzw. `tv_co2_attribution`
zurücknehmen.

## Kachel „Höhenstrahlung"

Die Kachel *Höhenstrahlung* (Ikone `ic_radiation`, volle Breite, dieselbe Höhe wie die
CO₂-Kachel) zeigt die geschätzte effektive Dosis aus kosmischer Höhenstrahlung in mSv für das
**laufende Kalenderjahr**; rechts daneben steht die Gesamtsumme über alle Jahre. Ein Tipp öffnet
`RadiationFragment` mit der Dosis je Kalenderjahr (neuestes Jahr oben), das i oben rechts im
Kopf öffnet `RadiationInfoFragment` mit der Erklärung von Formel, Daten, Quellen und Grenzen.

Das Modell steht ohne Android-Abhängigkeiten in `RadiationCalculator` (geprüft von
`RadiationCalculatorTest`), die Jahresauswertung in `RadiationStats`:
**Dosis = Dosisleistung × Flugzeit**, Dosisleistung R(h, φ) = 3 µSv/h · e^(0,35·(h−10 km)) ·
(1 + sin²φ). Ankerpunkte sind die BfS-Richtwerte (am Boden ca. 300 µSv/a, in 11 km nördlich
von 60° 8–9 µSv/h, FRA–JFK hin und zurück ca. 120 µSv) und der FED-Standard (4 µSv/h in 10 km);
die Konstante 3 µSv/h in 10 km liegt dazwischen. Die Reiseflughöhe wird aus der
Grosskreis-Distanz geschätzt (9 km auf der Kurzstrecke, je 3000 km ein km mehr, gedeckelt bei
12 km), die Breite ist die des
Grosskreis-Mittelpunkts (45° als Rückfall ohne Koordinaten). Flugzeit und Strecke kommen aus
dem Eintrag, fehlt die Flugzeit, wird sie aus der Strecke geschätzt (`GeoMath.flightMinutes`,
805 km/h plus 25 Minuten); fehlt beides, entfällt die Dosis. Ground Transfers zählen nicht.

Die rechte Karte der Kachel ist glasig: halbtransparentes Indigo (`radiation_card_bg`
`#A63F51B5`) mit hellem 1dp-Rand (`radiation_card_stroke` `#4DFFFFFF`). In der linken Spalte
steht die Jahreszahl oben am Titel, die Dosis zentriert in der Kachelhöhe (FrameLayout,
`layout_gravity="center_vertical"`) und am unteren Rand eine Zeile mit Jahr, Balken und
Warndreieck. Der Balken (`RadiationLimitBar`, eigene View, 18dp hoch) zeigt den Anteil der
Jahresdosis am Grenzwert von 20 mSv (`RadiationCalculator.ANNUAL_LIMIT_USV` = 20_000 µSv,
Obergrenze für fliegendes Personal, Kategorie A der Strahlenschutzverordnung) als Prozent
(`RadiationCalculator.percentOfAnnualLimit`): unter 50 % steht die Zahl rechts neben der
Füllung auf der Spur, ab 50 % im ausgefüllten Balken (`RadiationLimitBar.labelInsideBar`,
getestet in `RadiationLimitBarTest`). Liegt die Dosis über dem Grenzwert, ist die Füllung rot
(`radiation_limit` `#FFD32F2F`, Balken gedeckelt bei 100 %) und ein rotes Warndreieck
(`iv_radiation_warning`) erscheint; ein Tipp darauf öffnet ein Pop-up
(`showRadiationLimitWarning` in `FirstFragment`, `MaterialAlertDialogBuilder`).

Dasselbe Layout gilt für die CO₂-Kachel: Titel oben, darunter die Beschriftung
*CO₂-Emissionen*, die Tonnenzahl zentriert in der Kachelhöhe. Weil die zentrierte Zahl den
oberen Rand der Kachel einnimmt, liegt das `paddingTop` nicht mehr auf der äußeren Zeile,
sondern auf dem Titelblock; die rechte Karte behält ihre Lage über `layout_marginTop="16dp"`
(8dp ehemaliges Zeilen-Padding plus 8dp Rand) und `layout_marginBottom="8dp"`.

Auf der Detailseite steht der Untertitel der Kachel nicht mehr im Kopf, sondern als Karte über
der Jahresliste (im Format der Achievements-Kachel, Text `radiation_detail_subtitle`). Auf der
Infoseite steht der Abschnitt *Grenzen der Schätzung* ganz oben, *Quellen und Richtwerte* ganz
unten; darunter ein Link zum Bundesamt für Strahlenschutz (`tv_radiation_bfs_link` in
`fragment_radiation_info.xml`, URL `radiation_info_bfs_url`), der über `RadiationInfoFragment`
unterstrichen dargestellt wird und im Standardbrowser des Handys öffnet.

Grundlage ist dieselbe Auswahl wie die Kachel selbst: der **Flugart-Filter** gilt
(`DashboardStats.flightTypeEntries`), der **Zeitraum-Filter des Dashboards nicht** – die Kachel
zeigt das laufende Jahr, die Detailseite listet alle Jahre. Die Kachel steht in
`DashboardPrefs.CATALOG` (`Tile("radiation", …, span = SPAN_FULL, heightDp = 104)`) und in
`DEFAULT_ROWS` nach der CO₂-Kachel; dafür wurde `KEY_TILES` auf `tiles_standard_v16` erhöht.

## Kachel „Passagiere"

Die Kachel *Passagiere* (Ikone `ic_passengers`, volle Breite, dieselbe Höhe wie die CO₂- und
Höhenstrahlungs-Kachel) ist eine reine Besatzungs-Kachel: `DashboardPrefs.isTileAvailable`
lässt sie nur bei der Rolle *Crew* zu (`role.isCrew`) - im Dashboard und im Bearbeiten-Dialog
gleichermaßen. Sie zeigt die geschätzte Gesamtzahl der Passagiere über **alle Jahre** (auf volle
Tausender gerundet); rechts daneben steht der Durchschnitt pro Flug. Ein Tipp öffnet
`PassengersFragment` mit der Passagierzahl je Kalenderjahr (neuestes Jahr oben, Balken zum
stärksten Jahr); das Adjust-Symbol oben rechts im Kopf (`ic_adjust`) öffnet einen Dialog, der
die Auslastung zwischen 50 und 100 % in 5er-Schritten einstellt (Standard 80 %, pro Nutzer
gespeichert in `Settings.getPassengerLoadFactor`).

Die Rechnung steht ohne Android-Abhängigkeiten in `PassengersCalculator` (geprüft von
`PassengersCalculatorTest`): **Passagiere = maximale Sitzplätze des Typs × Auslastung ÷ 100**.
Zählen tut nur die Reiseart **On Duty** (`PassengersCalculator.isOnDuty`, über
`ChartData.normalizeFlightType` sprachunabhängig erkannt): Deadhead, Dienstreise, Privat und
Ground Transfer werden nicht ausgewertet, ein Eintrag ohne Reiseart auch nicht.
Die Sitzplätze kommen aus `assets/aircraft/aircraft_seats.json` (`AircraftSeats.load`, geprüft
von `AircraftSeatsTest` und `AircraftSeatsAssetTest`): zertifizierte Single-Class-Maxima je
Familie (A320: 186, A380: 853, …) plus Alias-Codes („320", „738", „AT72`), die
`AircraftSeats.Catalog.seatsFor` per Präfix-Matching auf die Familie abbildet („A350-900",
„Boeing 737-800", „B738", „737 MAX"). Ein unbekannter oder fehlender Typ bekommt den
Durchschnitt des Datensatzes (≈ 273 Sitze).

Weil die Schätzung (Maximalbestuhlung × Auslastung) eine echte Ungenauigkeit trägt, werden
Summen auf volle Tausender gerundet (`PassengersCalculator.roundToThousands`) - Kachel und
Jahreswerte; der Durchschnitt pro Flug bleibt eine ganze Zahl. Grundlage ist dieselbe Auswahl
wie die Höhenstrahlung: der **Flugart-Filter** gilt (`DashboardStats.flightTypeEntries`), der
**Zeitraum-Filter des Dashboards nicht**; innerhalb der gewählten Flugart zählen nur die
On-Duty-Flüge, die Infozeile der Detailseite nennt beides („Flugart: … · nur On Duty").

Die rechte Karte der Kachel ist wie die Höhenstrahlung glasig: halbtransparentes Teal
(`passengers_card_bg` `#A600897B`) mit hellem 1dp-Rand (`passengers_card_stroke` `#4DFFFFFF`).
Die Kachel steht in `DashboardPrefs.CATALOG` (`Tile("passengers", …, span = SPAN_FULL,
heightDp = 104)`) und in `DEFAULT_ROWS` nach der Höhenstrahlung; dafür wurde `KEY_TILES` auf
`tiles_standard_v17` erhöht.

## Seite „Distanz"

Die Kachel *Distanz* öffnet `DistanceDetailFragment`. Oben stehen der längste und der kürzeste
Flug des Zeitraums als `RouteMiniMapView`, deren Strecke sich einmal aufbaut
(`playRouteAnimation`: Teilstrecke bis zum Fortschritt, Flugzeug an der Spitze). Danach fliegt das
Flugzeug die fertige Strecke wiederholt in der tatsächlichen Flugrichtung ab – von der Herkunft zum
Ziel, nie in die Gegenrichtung (`PlanePhase.CRUISING`), damit die Animation nur die wirkliche Strecke
zeigt. Ohne Animation bleibt die Karte das Standbild der Eintragsliste
(`fraction = null` setzt das Flugzeug in die Mitte).

Darunter zeigen drei `SpaceDistanceView`-Bilder Erdumrundungen (Erde mit kreisendem Flugzeug, ohne
Ende), Weg zum Mond (Erde und Mond, Flugzeug pendelt) und Weg zur
Sonne (Erde und Sonne). Erde und Mond bzw. Sonne sitzen in beiden Bildern an derselben Stelle und
sind gleich groß (`EARTH_CX/CY`, `PARTNER_CX/CY`). Alle vier Fluganimationen – der Dauerflug auf der
Strecke, Erdumrundung, Mond und Sonne – teilen sich dieselbe Dauer `PLANE_ANIM_MS`, laufen also
gleich schnell. Das
Flugzeug der Sonne wandert ehrlich nur bis zu dem Bruchteil, den die Flüge abdecken – der ist winzig,
deshalb stehen darunter die Prozente „erreicht/verbleibend" und die Kilometer „geflogen/verbleibend".
Die Kontinente sind ein grob
vereinfachter Umriss aus `assets/world_land.json` (dieselbe Datei wie die Streckenkarte), kein
feines Küstenbild.

Die Abschnitte werden nacheinander eingeblendet: erst nach der Strecke des längsten Flugs die des
kürzesten, danach die drei Grafiken; jede Zahl zählt erst beim Erscheinen ihres Abschnitts hoch. Die
Grafik-Abschnitte folgen einander im festen `REVEAL_MS`-Takt, unabhängig von den Animationsdauern.
Weil die Seite dabei nach unten wächst, führt `scrollIntoView` (aus `reveal`, erst nach dem
Neuvermessen per `doOnLayout`) jeweils so weit nach, dass der neue Abschnitt zu sehen ist – bei
einem Abschnitt, der höher als der Bildbereich ist, bleibt dessen Oberkante oben stehen.
Die
endlosen Animationen pausieren mit `onWindowVisibilityChanged`, wenn die Seite in den Hintergrund
geht.

Auf dem Kompass (`CompassView`) sitzen die vier Himmelsrichtungen bewusst weit außen: Der
Richtungsbuchstabe steht `LETTER_OFFSET_DP` vom Nadelende entfernt, der Dreilettercode
`CODE_GAP_DP` dahinter. Wird `LETTER_OFFSET_DP` erhöht, wandern Buchstabe und Code gemeinsam nach
außen, der Abstand zwischen beiden bleibt gleich. Damit der Code dabei nicht abgeschnitten wird,
holt `clampCodeRadius()` ihn so weit herein, wie der Platz es zulässt – bei **West und Ost**
gegen die halbe Breite, bei **Nord und Süd** gegen die halbe Höhe (der Code steht dort über bzw.
unter dem Buchstaben, nicht daneben). Ohne die Höhen-Schranke landete der Nord/Süd-Code knapp
außerhalb der Ansicht, obwohl er waagerecht mittig saß.

## Homebase

Die **Homebase** ist der meistgenutzte Flughafen des Logbuchs (jede Landung und jeder Start zählt
als eine Bewegung; Gleichstand gewinnt der alphabetisch erste Code). Sie steht ohne
Android-Abhängigkeiten in `Homebase.kt` (geprüft von `HomebaseTest`) und wird zentral in
`LogbookRepository.getEntries()` bei jedem Neuladen neu berechnet (`Homebase.current`; beim
Nutzerwechsel und in `close()` zurückgesetzt) – nicht pro Seite und nicht über den Zeitraum-Filter.
Angezeigt wird sie in der Mitte des Kompasses auf der Kachel *Flughäfen*: der Dreilettercode mit
der Beschriftung „Homebase" darunter (`compass_homebase`). In der Tagesordnung der Seite *Einträge*
gruppiert sie außerdem Hin- und Rückflüge zu Paaren (siehe dort).

## Hintergrund-Flugzeuge (Einträge, Discovery, Dashboard)

Die drei Seiten *Einträge*, *Discovery* und *Dashboard* legen als erste Kindansicht eine
`PlaneBackgroundView` unter ihren Inhalt. Sie zeichnet kleine Flugzeuge, die langsam in großen
Kurven über den Bildschirm ziehen und eine gestrichelte Spur hinterlassen, die verblasst. Die
Ansicht ist reine Unterlage und weder anklickbar noch fokussierbar – Tippen geht ungehindert an
die Liste bzw. die Kacheln. Sichtbar werden die Flugzeuge deshalb nur in den Lücken, Rändern und
Abständen zwischen den Kacheln.

Jede Bahn ist eine zufällige kubische Bézier-Kurve von einem Bildrand zum gegenüberliegenden, in
`PATH_SAMPLES` Stützstellen zerlegt. Start- und Zielrand liegen gegenüber (`(startEdge + 2) % 4`).
Die beiden Kontrollpunkte weichen unabhängig seitlich aus (`curveControls`, Ausschlag `CURVE_BEND`
der Sehnenlänge): gleiche Richtung ergibt einen Bogen, entgegengesetzte eine S-Kurve mit zwei
Bögen, sodass die Flieger deutlich geschwungen und aus jeder Richtung fliegen und das Bild an der
anderen Seite verlassen. Beim Öffnen der Seite starten `INITIAL_PLANE_COUNT` Flugzeuge verteilt
über alle vier Ränder (nur winzig versetzt); neue kommen später aus einem gerade freien Rand
(`pickEdge`), damit sie möglichst aus verschiedenen Richtungen einfliegen. Weil der Bahnanfang um
den Rand ausserhalb des Bildes liegt, bekommt jede Bahn in `addPlane` per `entryFraction` einen
Vorsprung: Die Startzeit wird so weit zurückgezogen, dass das Flugzeug rund
`ENTER_DELAY_MS` (eine Sekunde) nach dem Erscheinen sichtbar am Rand einfliegt statt erst
nach der ganzen Anflugstrecke. Höchstens `MAX_PLANES` = 6 sind gleichzeitig unterwegs, alle teilen
die Grundgeschwindigkeit `PLANE_SPEED_PX_PER_S`. Die Spur besteht aus zeitgestempelten Punkten und
wird in `TRAIL_CHUNKS` Blöcke geteilt gezeichnet, jeder Block mit dem Verblassungsgrad seines
jüngsten Punktes; nach `TRAIL_FADE_MS` ist ein Punkt verschwunden. Das Strichmuster ist über
`TrailPoint.dist` (Strecke vom Bahnanfang, `arcLengthAt`) am Anfang der Bahn verankert, sonst
begänne es an jeder wandernden Blockgrenze neu und die Striche würden mitlaufen. Weil die Flugzeuge
langsam sind, wird die Spur dadurch sehr lang und bleibt lange stehen.

Die Animation läuft nur, solange die Ansicht sichtbar ist (`onWindowVisibilityChanged`), und
schweigt, wenn der Nutzer Systemanimationen abgeschaltet hat
(`Settings.Global.ANIMATOR_DURATION_SCALE` = 0). Die reine Geometrie – `cubicPoint`, `sampleCubic`,
`pointAt`, `planeRotation`, `pathLength`, `cumulativeLengths`, `arcLengthAt`, `entryFraction` im
Companion – prüft `PlaneBackgroundViewTest`.

**Rückbau:** Die drei `<com.highfly.logbook.PlaneBackgroundView …>`-Einträge aus
`fragment_entries.xml`, `fragment_discovery.xml` und `fragment_first.xml` entfernen, dann
`PlaneBackgroundView.kt` und `PlaneBackgroundViewTest.kt` löschen. `ic_flight` wird auch
andernorts verwendet und bleibt.

## Seite „Einträge" – Tagesordnung

Die Kacheln kommen absteigend nach Datum aus der Datenbank (`date DESC, id DESC`); innerhalb eines
Tages steht der erste Flug des Tages unten, der letzte oben. Die Grundreihenfolge ist die
Eintragereihenfolge — beim CSV-Import die Reihenfolge der Datei (die erste Zeile ist der erste
Flug des Tages), ein manuell eingetragener Flug landet als Neuestes oben im Tag. Ein Tag bleibt
ein Block; `EntryDayOrder` (im `EntriesFragment.renderEntries`) ordnet nur innerhalb des Tages um.
Drei Regeln bestimmen die Reihenfolge: **Layover** steht immer ganz oben — auch wenn der Flug
zugleich der Rückflug des Vortags ist. Ganz unten steht der erste Flug des Tages: Endete der Vortag
mit einem Layover, ist es der **Rückflug** (die Umkehrung der Layover-Strecke, sofern nicht selbst
wieder ein Layover). Dazwischen werden **Hin- und Rückflüge ab der Homebase zu Paaren gruppiert**
(`pairUp`): Der Hinflug (Abflug ab der Homebase) steht unten, der Rückflug (die Umkehrung) direkt
darüber; die Paare sortieren sich nach der **Position des Hinflugs** in der Eintragereihenfolge.
Flüge ohne passenden Gegenflug (z. B. ein Anschlussflug an einem anderen Ort) behalten ihre
Position. Die Homebase kommt aus `Homebase.current` über das **ganze Logbuch**, nicht nur über den
gefilterten Ausschnitt (Fallback: `EntryDayOrder.busiestAirports`); die Vortag-Regeln dagegen
schauen auf den tatsächlich angezeigten Ausschnitt. Geprüft wird das von `EntryDayOrderTest`
(u. a. die Beispiele der Aufgabe).

## Seite „Achievements"

Die Dashboard-Kachel *Achievements* (Pokal `ic_achievement`, Standardreihe mit *Reiseart* und
*Reiseklasse*) öffnet `AchievementsFragment`: oben die klassische Kopfleiste, darunter eine
hervorgehobene Kachel über die ganze Breite im Format der beiden Layover-Kacheln (Fortschritt
„x / y" samt `LinearProgressIndicator` und Prozent) und darunter der Zeitstrahl der erreichten
Erfolge, der jüngste oben. Ein Tipp auf die Kachel öffnet `AchievementsGridFragment` mit allen
Erfolgen nach Kategorie, fünf Kacheln je Zeile (Icon oben, Titel darunter, erreicht farbig,
offen ausgegraut); eine Zeile des Zeitstrahls öffnet den auslösenden Eintrag zum Bearbeiten.

Die Erfolge selbst sind rein in `Achievements.kt` beschrieben und werden von
`Achievements.evaluate(entries, role, lookups)` über das **ganze** Logbuch ausgewertet – nicht
über den Zeitraum/Filter des Dashboards, denn ein erreichter Erfolg bleibt erreicht. Die
Auswertung ist ohne Android-Abhängigkeiten: Was sie von außen braucht – Land und Koordinaten
eines Flughafens, die Länderliste eines Kontinents – kommt als `Achievements.Lookups` herein
(alle drei mit Voreinstellung, damit Tests nur das Nötige übergeben); nur `Definition.title` und
die `Context`-Überladung von `evaluate` brauchen Android.
Der Zeitpunkt eines Erfolgs ist der auslösende Flug, deshalb kann der Zeitstrahl Datum,
Flugstrecke und Flugnummer zeigen.

Die Erfolge stehen in elf Kategorien: *Flüge* (1., 10., 50. … 5000. Flug), *Distanz* (40.075 km
zusammen, ein einzelner Flug über 10.000 km), *Zeit* (1.000 Stunden, erster Flug über 10 bzw. 15
Stunden, erster unter einer Stunde), *Strecken* (10× dieselbe Strecke), *Fluggesellschaften*
(10/25/50 verschiedene), *Flugzeugtypen* (erster A380/B747), *Geografie* (Äquator, Nullmeridian),
*Kontinente*, *Länder*, *Reiseklassen* und *Besatzung*. Ein „N. Flug" zählt jeden Eintrag wie die
Kachel *Flüge* (ein Ground Transfer also auch), Distanz und Zeit dagegen nur echte Flüge
(`DashboardStats.isFlying`), wie die Kacheln *Distanz* und *Zeit*. Kontinente und Länder zählen
Abflug und Ziel, wie die Rubbelkarte. „Alle Länder eines Kontinents" meint die Länderliste der
App (`CountryShapes`, über `countriesOfContinent` an `evaluate`), nicht die ISO-Liste von
`Continents` – so passt der Erfolg zu den Kontinent-Kacheln der Länder-Seite. Äquator und
Nullmeridian lösen beim ersten Flug aus, dessen Strecke die Linie quert (Breiten- bzw.
Längenvorzeichen wechselt; die Datumsgrenze ist ausgeschlossen). Ein Flugzeugtyp wird über ein
Stück im Typ erkannt (`aircraftToken` „380"/„747", deckt „Airbus A380-800" und „Boeing 747-400"
mit ab). Eine Fluggesellschaft zählt unabhängig von der Schreibweise (getrimmt, klein
geschrieben). Die Kategorie *Besatzung* erscheint nur bei der Rolle Besatzung (`Role.isCrew`);
die Bordfunktion wird sprachunabhängig über Wortstämme erkannt (`Achievements.crewFunctionOf`:
„Purser" mit „ii" → Purser II, sonst Purser I; „flugbegleit"/„flight attendant"/„cabin" →
Flugbegleiter). Eine Zahl im Titel wird mit dem Tausendertrennzeichen der App-Sprache eingesetzt
(`Definition.title`), daher „1.000. Flug" bzw. „40.075 km geflogen". Geprüft wird das von
`AchievementsTest`.

Der Platz der Kachel steht in `DashboardPrefs.DEFAULT_ROWS`; dafür wurde `KEY_TILES` auf
`tiles_standard_v15` erhöht – das setzt das vom Nutzer umsortierte Raster einmalig zurück, wie es
die Konvention bei einer neuen Kachel vorsieht.

## Story – Play (Dashboard) und Insights (Layover-Seite)

Zwei bisher funktionslose Knöpfe öffnen eine **Story** im Stil von Social Media:
`btn_dashboard_story` („Play", Toolbar des Dashboards, `activity_main.xml`) und
`btn_layover_overview` (Stern oben rechts auf der Layover-Detailseite, `fragment_tile_detail.xml`).
Beide starten die `StoryActivity` (`StoryActivity.start(context, MODE_PLAY/MODE_INSIGHTS)`,
Manifest-Eintrag ohne Export): oben segmentierte Fortschrittsbalken, darunter der Inhalt, Tippen
auf die rechte Zweidrittel-Seite geht weiter, auf die linke Drittelseite zurück, jede Seite läuft
zehn Sekunden (`PAGE_MS`) und die letzte schließt die Story. Der Timer pausiert mit `onPause` und
`onWindowFocusChanged`, der Bildschirm bleibt an (`FLAG_KEEP_SCREEN_ON`), die Seite ist
Edge-to-Edge mit dunklem Hintergrund (`story_scrim`). Daten lädt die Activity auf einem
Hintergrund-Thread (`LogbookRepository`, `Homebase.current`, `AircraftSeats`, `CountryShapes`).

**Play** zeigt Highlights aus dem ganzen Logbuch (Reihenfolge): Flüge gesamt und dieses Jahr,
Top-3-Ziele und Top-3-Länder (jeweils getrennt Privat / On Duty, On Duty nur bei Crew),
Top-Reisebuddies, häufigster und am weitesten geflogenes Flugzeug, die häufigste Strecke,
nur bei Crew die geschätzten Passagiere, die Reiseklassen-Verteilung, Gesamtstrecke, Gesamtflugzeit
und die nötige CO₂-Kompensation. **Insights** zeigt Layover-Auswertungen: häufigste
Layover-Ziele, Ziele mit den meisten Layover-Stunden, Top-5-Länder nach summierten
Layover-Stunden, häufigster Layover-Buddy, Top-5-Lieblingsziele und Top-5-unbeliebte Ziele.

Die reine Auswertung steht ohne Android in `StoryStats` (geprüft von `StoryStatsTest`, 19 Tests):
`playStats(entries, seatsCatalog, loadFactorPercent, homebase, today, lookups)` und
`insightsStats(entries, homebase, lookups)` mit `Lookups(countryOf, countryNameOf)`.
Konventionen: Die Flugzahl zählt jeden Eintrag wie die Kachel *Flüge*, Distanz/Zeit/Ziele/Länder/
Flugzeug/Strecken nur echte Flüge (`DashboardStats.isFlying`); die **Homebase zählt nicht** als
Ziel; Strecken sind ungerichtet (sortiertes Paar); der häufigste Buddy wie bei den Reisebuddies;
Passagiere wie die Kachel *Passagiere* (On Duty × Sitze × Auslastung, auf Tausender gerundet).
Lieblingsziele sind häufig + regelmäßig (Score = Flüge + verschiedene Monate, nur Ziele mit
mindestens zwei Flügen), unbeliebte selten + lange her (sortiert nach Flügen, Monaten, letztem
Datum aufsteigend) – beides über **alle** Ziele, nicht nur Layovers, sonst wäre Insights für
Passagiere ohne Layover leer. Layover-Ziel ist das `toAirport` des `layover`-Eintrags.

Die Seiten baut `StoryPages` (`StoryPages.play/insights`) aus drei Seitentypen (`StoryPage`):
`HeroNumber` (große Zahl mit Einheit, optional zweite Zahl und Fußzeile), `HeroText` (großer Text,
z. B. die Stammstrecke) und `Ranking` (gruppierte Zeilen mit Rang, Flagge, Label, Unterzeile und
Wert). Leere Abschnitte und die Crew-only-Passagiere-Seite werden ausgelassen; bei leerem
Logbuch gibt es eine einzelne Hinweisseite. Strings liegen unter `story_*` in `values` (deutsch)
und `values-en` (englisch).

**Flip-Uhr:** Zahlen erscheinen als alte Flughafen-Anzeigetafel. `FlipNumberView` ist eine
waagerechte Reihe von `FlipDigitView`-Karten (Ziffern) mit schlichten Trennzeichen dazwischen;
`setText` flippt nur geänderte Karten und lässt neue Karten bei wachsender Zahl von links
hinzukommen, `countTo` zählt beim Seitenauftritt von 0 hoch (1200 ms) und formatiert jeden
Zwischenwert neu. Eine Karte (`FlipDigitView`) ist in zwei Hälften geteilt und kippt zweiteilig:
Phase 1 (150 ms, ease-in) fällt das obere Flap der alten Ziffer 0° → -90° um die Mittelachse und
verdunkelt sich, sein Schatten wächst auf der unteren Hälfte; Phase 2 (150 ms,
OvershootInterpolator) landet das untere Flap der neuen Ziffer aus +90° → 0° mit leichtem Bounce
und wird heller. Naht und Faltlinien-Gradient (`story_fold`) bleiben ausgerichtet, die Kamera
steht 5× Kartenhöhe entfernt (`cameraDistance`). Gesamtdauer 300 ms. **Wichtig:** Die Kartenhälften
werden nicht in `onSizeChanged` eingebaut, sondern per `post { buildCard() }` – während des
Layouts (aus `onSizeChanged` heraus) eingefügte Kinder werden nicht gemessen, bleiben 0×0 und
unsichtbar. Sind Systemanimationen abgeschaltet (`Settings.Global.ANIMATOR_DURATION_SCALE` = 0),
erscheinen die Ziffern sofort ohne Flip und ohne Count-up, der Story-Timer läuft weiter.

Die Seite selbst zeichnet `StoryPageView`: Icon (64 dp, Akzentfarbe), Titel, Inhalt; der Auftritt
ist choreografiert (Icon-Pop mit Overshoot, Titel gleitet hoch, Inhalt gestaffelt, Zahlen rollen
hoch, Zeilenwerte flippen gestaffelt). Der Hero-Wert ist höchstens 84 dp hoch und wird so
klein, dass die breiteste Zahl in die Inhaltsbreite passt.

## Einstellungen – Aufbau nicht eigenmächtig ändern

Jede Einstellung ist eine Zeile in `fragment_settings_section.xml`: **links** steht immer die
Einstellung, **rechts** ihre Auswahl. Dafür gibt es Styles in `values/styles.xml`; neue Zeilen
entstehen mit `SettingsRow` + `SettingsRowLabel` + `SettingsRowValue`, Aktionen (Export, Crash-Log)
mit `SettingsActionButton`. Für Felder mit eigener Beschriftung (Fluggesellschaft, Zeiträume)
gehört `android:labelFor` der Zeilenbeschriftung auf das Feld.

**Auswahlen mit mehreren Werten** (Rolle, Funktion, bevorzugte Arbeitsposition, Erscheinungsbild,
Akzentfarbe, Sprache) sind keine Kacheln mehr, sondern ein **Regler** (`SegmentedOptionView`, wie
die Reiseklasse unter „Neuer Flug“): eine gerundete, umrandete Leiste (52 dp) mit gleich breiten
Zellen, deren Markierung zur gewählten Zelle gleitet (250 ms). Darüber steht die Beschriftung in
einem Block (`SettingsBlock` + `SettingsBlockLabel`). Eine Zelle trägt entweder eine Beschriftung
(mit Auto-Size 9–14 sp) oder einen Farbkreis (Akzentfarbe); die Markierungsfarbe ist je Option
festlegbar (die Arbeitsposition nutzt die flugspezifischen Klassenfarben, Beschriftung darauf in
Weiss, sonst `colorPrimaryContainer`). `SettingsSectionFragment` setzt die Optionen über
`setOptions(listOf(SegmentedOptionView.Option(...)), selected = index)` und hört auf
`onOptionSelected`; programmatische Updates laufen über `select(index)`. Die früheren Kachel-Styles
`SettingsTile`/`SettingsTileLabel` nutzt nur noch die Willkommens-Tour (`welcome_page_profile.xml`)
und bleiben deshalb stehen.

Die Punkte sind nach **Themen gruppiert**: innerhalb einer Gruppe 12 dp Abstand, zwischen zwei
Gruppen mit unterschiedlichem Inhalt 28 dp (Profil: Rolle+Funktion | Fluggesellschaft+Arbeitsposition
| Willkommens-Tour; Anpassung: Darstellung | Voreinstellungen; Daten: Sichern/Wiederherstellen |
Gefahrenzone mit Strich und rotem Titel).

Das Erscheinungsbild hat drei Werte (`Settings.THEME_MODE_SYSTEM/LIGHT/DARK`) und wird in
`MainActivity` über `AppCompatDelegate.setDefaultNightMode` gesetzt. `THEME_MODE_SYSTEM` folgt dem
Handy. Vor dem Umstauf lag hier nur ein Ja/Nein-Wert im Schlüssel `dark_mode`; solange der
Schlüssel gesetzt ist, hat er Vorrang, damit eine installierte App ihr Aussehen behält.

Die Sprache der App hat zwei Werte (`Settings.LANG_DE`/`LANG_EN`) und steht unter dem Schlüssel
`language`; Standard ist Deutsch, damit eine bestehende Installation ihre Sprache behält. Die
Anzeigetexte liegen vollständig in `values` (deutsch) und `values-en` (englisch). Angewandt wird
die Sprache in `LogbookApplication`, `MainActivity` und `WelcomeActivity` über
`LocaleUtils.applyLocale`, das zusätzlich `Locale.setDefault` setzt, damit auch Monatsnamen,
Ländernamen und Dezimal-/Tausendertrennzeichen mitgehen. Für Zahlen und Datumsformate im Code
gibt es `Settings.language(context)` (statt fest `Locale.GERMANY`). Beim Umschalten ruft
`SettingsSectionFragment.applyLanguage` zusätzlich `AppCompatDelegate.setApplicationLocales` auf;
damit das sofort greift, hat ein App Bundle die Sprachressourcen nicht aufzuteilen
(`bundle { language { enableSplit = false } }` in `app/build.gradle.kts`).

Die beiden Auswahlfelder *Standard-Zeitraum* und *Standard-Zeitanzeige* tragen bewusst
`android:saveEnabled="false"`, weil ihr Text bei jedem Aufbau neu aus den Einstellungen kommt.
Ohne das stellt die Ansicht beim Activity-Neubau (z. B. beim Sprachwechsel) ihren alten,
anderssprachigen Text wieder her.

## Navigationsleiste (BottomTabBar)

Die untere Navigationszeile ist eine eigene View (`BottomTabBar`). Sie **schwebt als Pill über dem
Seiteninhalt**: Sie liegt als Overlay in `content_host` (`activity_main.xml`, `layout_gravity=
"bottom"`, 12 dp Rand seitlich, 8 dp unten) und ist voll gerundet gezeichnet (`colorSurfaceContainer`,
3 dp Elevation, der Schatten folgt der Rundung über einen `ViewOutlineProvider`), keine Trennlinie.
Die Seite läuft bis zum unteren Bildschirmrand – rechts, links und **darunter** sieht man den
Inhalt neben/unter der schwebenden Leiste (`MainActivity` setzt kein unteres Padding, solange die
Leiste sichtbar ist).

Damit trotzdem nichts **hinter** der Leiste verschwindet, räumen die betroffenen Seiten unten
Platz ein über die Helfer in `LogbookFragment`: `addBottomNavPadding()` (Padding, für Listen und
Scroll-Seiten – der letzte Eintrag scrollt ganz über die Leiste hinaus) und `addBottomNavMargin()`
(Margin, nur für schwebende Bedienelemente). Gemessen wird der Platz an der echten Leiste
(`bottom_nav`: Höhe inklusive ihres Inset-Paddings plus unterer Abstand); Rückfall ist
`BOTTOM_NAV_SPACE_DP` (72 dp = 64 dp Leiste + 8 dp Rand) plus Systemleisten-Inset. Angewendet
wird er beim ersten Layout (`doOnNextLayout`), aber **gepostet**: Ein `requestLayout()` direkt
aus dem Layout-Callback heraus verläuft sich, weil die Elternkette (bis zur
`FragmentContainerView`) mitten im Layout noch das Force-Layout-Flag trägt und den Request
nicht bis zur ViewRootImpl durchlässt – der Platz würde nie wirksam (so geschah es bei den
Fragment-Wurzeln, bis die Anwendung gepostet wurde). Nach dem Posten läuft der Runnable
außerhalb des Layout-Passes, der Request kommt an und die nächste Traversale wendet den Platz
an. **Padding** bekommen alle Scroll-Container und Listen (Einträge, Entdecken, Airlines, Länder,
Reisebuddies, Achievements, Profil, die Einstellungsabschnitte, Import, Neuer Flug sowie die
Detailseiten); die ScrollViews tragen dafür `clipToPadding="false"`, damit der Inhalt sichtbar
unter die Leiste läuft. **Margin** bekommen nur schwebende Bedienelemente: `FirstFragment`
(`fab_add`), `RubbelkarteFragment` (`continent_scroll`, die Kachelzeile),
`DashboardEditFragment` (`edit_bottom_bar`, die Bedienleiste mit Skalierung und Aktionen – der
Kachel-Grid darüber bekommt Padding), `Co2OffsetFragment` (`ll_co2_stats`, die Kennzahlen-Zeile –
die Karte darüber endet über ihr und hebt die FABs mit an), `CountryDetailFragment`
(`tv_country_airport_detail`, ebenfalls mit Karte und FAB darüber) und `WorldMapFragment`
(`map_controls`, die Zoom-Knopfspalte, und `stats_card`). Keine Seite endet mehr fest über der
Leiste.

Die Tabs liegen als **gleichmäßig über die Leiste verteilte Zellen** (Prinzip der schwebenden
Toolbar): Der ausgewählte Tab ist die breite **Indikator-Pill** (`colorPrimaryContainer`, 48 dp
hoch) mit Icon und Beschriftung nebeneinander, die übrigen schmalen Icon-Zellen (48 dp). Zwischen
allen Zellen und an beiden Rändern liegt derselbe Abstand, sodass die Icons die ganze Leiste
gleichmäßig ausfüllen statt in der Mitte zusammengedrängt mit großen Lücken an den Seiten.
Reicht der Platz nicht, rücken die Zellen zusammen (Abstand 0) und die Zeile wird zentriert, damit
nichts am Rand abgeschnitten wird. Beim Wechsel gleitet der Indikator samt seiner Breite zum
neuen Tab (320 ms, Cosinus-Ease) und mischt Icon/Beschriftung weich (Crossfade). Inaktive Tabs
zeigen nur ihr Icon, gedimmt.

Der **Seitenwechsel** zwischen den Tabs ist ein Fade-Through (Crossfade): `MainActivity.
setupBottomNav` setzt in den `NavOptions` `nav_fade_in` (300 ms, decelerate) als enter/popEnter
und `nav_fade_out` (150 ms, accelerate) als exit/popExit.

Die **Auswahl bleibt erhalten**, solange die Leiste sichtbar ist: Öffnet man von einer Tab-Seite
eine Detailseite (z. B. Airline- oder Flughafen-Detail), bleibt der Tab der Ausgangsseite
markiert. `MainActivity.updateTabSelection` setzt die Auswahl nur bei Tab-Zielen neu, bei
Nicht-Tab-Zielen bleibt der zuletzt gewählte Tab stehen (früher wurde hier `-1` gesetzt und die
Markierung ausgeblendet).

Das Design ist angelehnt an die Bottom-Navigation von Tomato (nsh07/Tomato, **GPL-3.0**): schwebende
Leiste, Indikator mit Beschriftung, Crossfade beim Seitenwechsel. Wegen der Copyleft-Lizenz ist das
eine eigene Umsetzung des Designprinzips, kein übernommener Code.

## Tastatur/Insets – nicht regressieren

`MainActivity` verwaltet die Tastatur selbst, weil im Edge-to-Edge-Modus die Fenstergröße nicht
mehr angepasst wird. Die Höhe kommt **ausschließlich** aus den echten `ime()`-Insets, mit
`clampImeBottom()` als Rückfall auf den Animationsrahmen. Der Rahmen einer Insets-Animation
umfasst je nach Gerät den kompletten Fensterbereich (`lowerBound.top == 0`): ungeprüft ergab das
eine "Tastaturhöhe" in Fenstergröße, der Inhaltsbereich klappte auf 0 zusammen und die Seite
stand für die Dauer der Animation schwarz bzw. weiß im Hintergrund. `clampImeBottom()` darf nicht
entfernt oder gelockert werden.

Die Navigationsleiste (`bottom_nav`) wird bei offener Tastatur **ausgeblendet** (`GONE`), der
Inhaltsbereich bekommt dafür `imeBottom` als unteren Abstand. Über der Tastatur soll nichts
stehen. Sie erscheint mit geschlossener Tastatur wieder an ihrem üblichen Platz über der
Systemleiste. Das ist eine bewusste Umkehr der früheren Regel, die sie über die Tastaturhöhe
angehoben hat, damit man auf der Seite *Einträge* während der Eingabe wechseln konnte – der
Seitenwechsel muss jetzt bei geschlossener Tastatur erfolgen.

## Scroll-Position und Zurück-Taste – global

Alle Fragmente erben von der Basisklasse **`LogbookFragment`** (nicht von `Fragment` direkt). Sie
merkt sich in `onPause` die Scroll-Position jedes Scroll-Containers der Seite (RecyclerView,
ScrollView, HorizontalScrollView – Schlüssel ist die View-ID, deshalb tragen alle Scroll-Container
in den `fragment_*.xml` eine ID) und stellt sie beim Wiederaufbau der Ansicht in `onViewCreated`
wieder her, egal ob die Rückkehr über die Zurück-Taste, den Zurück-Pfeil oder die untere Leiste
läuft. Die Position überlebt als Instanzfeld die Navigation und als Instanzzustand Drehung und
Prozess-Neustart. Beim RecyclerView wartet die Wiederherstellung, bis der Adapter Einträge hat
(die Daten kommen asynchron), beim ScrollView, bis sich die Inhaltshöhe nicht mehr ändert (Kacheln
und Bilder wachsen erst nach dem ersten Layout); beides mit einer Frist von 2 s, danach wird die
Position auf das erreichbare Maximum begrenzt. Eine Seite, die selbst gezielt scrollt (z. B. zum
bearbeiteten Eintrag in `EntriesFragment`), ruft `cancelPendingScrollRestore()` auf. Die früheren
manuellen Lösungen (Dashboard, Fluggesellschaften, Länder) sind entfernt – nicht wieder
einführen.

Die **Zurück-Taste** leitet `MainActivity` über einen `OnBackPressedCallback` zuerst an das
aktuelle Navigationsziel weiter (`BackPressAware`): Ist auf der Seite ein Suchfeld offen (alle
fünf Listensuchen: Einträge, Fluggesellschaften, Reisebuddies, Länder, Kacheln samt
Flughafen-Popup), wird es geschlossen und die Eingabe gelöscht – wie das Schliessen-X – und erst
ein weiterer Druck navigiert zurück. Nimmt die Seite den Druck nicht, fällt er an den NavController
durch. Das X im Suchfeld verhält sich wie gehabt: Eingabe löschen und Feld schliessen.
