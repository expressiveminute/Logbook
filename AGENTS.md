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
3. Die signierte APK **immer in den Ordner `Releases/`** legen, nicht in den Projektroot:
   ```bash
   apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
     --ks-key-alias androiddebugkey --v3-signing-enabled true --v4-signing-enabled true \
     --out Releases/app-release-vX.Y.Z.apk app/build/outputs/apk/release/app-release-unsigned.apk
   ```
   Die `.idsig` entsteht daneben. Name des Release-Ordners ist Plural: `Releases/`.
4. Commit-Message ist die reine Version (`v0.0.3`), Branch `main`.
5. Hochladen und Tag-Erstellung auf GitHub übernimmt der Nutzer.

**Signatur:** Die bisherigen Releases sind mit dem Debug-Zertifikat `9f8aff1a…` signiert
(`C=US, O=Android, CN=Android Debug`). Es muss dasselbe bleiben, sonst verweigert Android das
Update ("inkompatible Signatur"). Ein Wechsel auf `backups/logbook/logbook-release.keystore` wäre
sinnvoller, ist aber nur als großer Versionssprung möglich.

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

Der Abstand zwischen dem Richtungsbuchstaben und dem Dreilettercode auf dem Kompass (`CompassView`)
wird vergrößert, ohne den Code abzuschneiden: `clampCodeRadius()` holt den Code auf schmalen
Geräten so weit herein, wie der Platz es zulässt.

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

## Einstellungen – Aufbau nicht eigenmächtig ändern

Jede Einstellung ist eine Zeile in `fragment_settings_section.xml`: **links** steht immer die
Einstellung, **rechts** ihre Auswahl. Dafür gibt es Styles in `values/styles.xml`; neue Zeilen
entstehen mit `SettingsRow` + `SettingsRowLabel` + `SettingsRowValue`, Auswahlkacheln mit
`SettingsTile` + `SettingsTileLabel`, Aktionen (Export, Crash-Log) mit `SettingsActionButton`.
Die Kacheln sind mit 40dp Höhe und 8–12sp so klein, dass mehrere Kacheln in eine Zeile passen.

Weil alle Kacheln einer Zeile gleich breit sind, aber unterschiedlich lange Beschriftungen
haben, gleicht `SettingsSectionFragment.fitTileLabels()` die Schriftgrösse einer Zeile nach dem
ersten Layout an - ohne das schrumpft nur die längste Beschriftung und die Zeile wirkt unruhig.
Für Felder mit eigener Beschriftung (Fluggesellschaft, Zeiträume) gehört `android:labelFor` der
Zeilenbeschriftung auf das Feld.

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
