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
