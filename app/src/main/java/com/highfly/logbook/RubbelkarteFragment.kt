package com.highfly.logbook

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentRubbelkarteBinding
import com.highfly.logbook.databinding.ItemContinentTileBinding
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Rubbelkarte: Weltkugel mit allen Ländern, in die der Nutzer bereits geflogen
 * ist. Die Kugel ist auf die besuchten Länder zentriert und lässt sich mit dem
 * Finger drehen und zoomen.
 *
 * In den USA und in Deutschland zeichnen sich zusätzlich die Grenzen der
 * Bundesstaaten/-länder als dünne Linien; gab es in das Land einen Flug, sind
 * die angeflogenen Staaten in derselben Farbe wie ein besuchtes Land gefüllt,
 * die übrigen deutlich abgeschwächt (aber nicht wie ein unbesuchtes Land).
 *
 * Der Stift oben rechts öffnet die Länderliste, auf der sich Länder von Hand
 * abhaken lassen. Diese gehören genauso zur Karte wie die angeflogenen;
 * angeflogene Länder lassen sich dort auch abwählen und fallen dann aus
 * Zählung und Färbung heraus.
 *
 * Über der Kugel steht ein Balken mit dem Gesamtstand: Wie viele Länder der
 * Welt besucht sind, als Anteil von allen.
 *
 * Unter der Karte steht eine Zeile Kacheln: je Kontinent (plus EU als eigene
 * Kachel mit Sternen im Kreis und EU-Flagge im Hintergrund) wie viele Länder
 * besucht sind, und ganz rechts - auch wenn davor ein Kontinent 0 % hat - die
 * Kacheln Deutschland und Vereinigte Staaten mit Flagge im Kachelhintergrund und
 * dem Anteil der angeflogenen Bundesstaaten/-länder. Ein Tipp auf eine Kachel
 * zoomt die Kugel auf die Region und hebt deren Grenzen hervor (Kontinent/EU:
 * alle Länder der Region; Länder-Kachel: das Land selbst) und öffnet unten auf
 * etwa der Hälfte des Bildschirms eine Seite mit der Liste: die Länder des
 * Kontinents als Checkliste (wie hinter dem Stift) bzw. die Staaten des Landes
 * mit Hauptstadt. Darüber bleibt die Kugel sichtbar; die Kachelzeile wird
 * dabei ausgeblendet, damit die Kugel oben nicht auf einen schmalen Streifen
 * schrumpft. Schließt der Nutzer die Seite wieder, zeigt die Kugel die Welt
 * wie am Anfang ([RubbelkarteView.resetToWorld]). Das Auge je Zeile (rechts von der Flagge,
 * links vom Namen) zoomt auf das einzelne Land bzw. den Staat und hebt dessen
 * Grenzen auf der Kugel hervor.
 *
 * Bewusst ohne Zurück-Button: Die Seite ist ein Tab der Navigationsleiste und
 * wird über den Tab oder die System-Zurück-Taste verlassen; ist die halbhohe
 * Seite offen, schließt die Zurück-Taste zuerst diese ([onBackPressed]).
 */
class RubbelkarteFragment : LogbookFragment(), BackPressAware, MapZoomHost {

    private var _binding: FragmentRubbelkarteBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    /** Geladene Umrisse und Auswertungen, siehe [refresh]. */
    private var shapes: List<CountryShapes.Country> = emptyList()
    private var stateShapes: List<StateShapes.State> = emptyList()
    private var stateSummary = StateStats.Summary(emptySet(), emptySet())

    /**
     * Steht, während [openPage] den Child-Backstack poppt, damit der
     * [backStackListener] beim leergefegten Stack nicht die Weltkugel
     * zurücksetzt - direkt danach öffnet ja schon die nächste Seite.
     */
    private var pageOpening = false

    /** Schließt die halbhohe Seite, wenn der Child-Backstack leer wird. */
    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        if (childFragmentManager.backStackEntryCount == 0 && _binding != null) {
            hidePage()
            // Wird die Seite geschlossen (Zurück-Taste/Pfeil), zeigt die Kugel
            // wieder die Welt wie am Anfang - nicht beim bloßen Kachelwechsel.
            if (!pageOpening) {
                binding.rubbelkarte.resetToWorld()
                binding.rubbelkarte.setHighlight(emptySet())
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRubbelkarteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Die Kachelzeile sitzt über der schwebenden Navigationsleiste.
        binding.continentScroll.addBottomNavMargin()
        childFragmentManager.addOnBackStackChangedListener(backStackListener)
        applyMapArea()
        binding.btnCountries.setOnClickListener {
            findNavController().navigate(R.id.action_rubbelkarte_to_countries)
        }
        refresh()
    }

    /**
     * Die Kugel füllt den Raum unter der Überschrift ganz, damit sie auch Platz
     * für die Bildschirmmitte hat.
     *
     * Ist das Gerät selbst quer, passt die volle Bildschirmbreite als Höhe nicht
     * mehr neben Überschrift und Kachelzeile. Dann nimmt die Karte nur den Rest
     * ein und zeichnet sich kleiner in diese Höhe - sonst schöbe sich die
     * Kachelzeile ganz aus dem Bild.
     */
    private fun applyMapArea() {
        val fuelltHoehe = !isQuerformat()
        val area = binding.rubbelkarteArea.layoutParams as LinearLayout.LayoutParams
        if (fuelltHoehe == (area.weight > 0f)) return
        area.height = if (fuelltHoehe) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
        area.weight = if (fuelltHoehe) 1f else 0f
        binding.rubbelkarteArea.layoutParams = area
    }

    /** Das Gerät liegt quer, der Platz in der Höhe ist knapp. */
    private fun isQuerformat(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext

        Thread {
            val entries = LogbookRepository.getFlownEntries()
            val summary = RubbelkarteStats.summarize(entries) { iata ->
                AirportData.country(appContext, iata)
            }
            // Länder, die auf der Länderliste von Hand abgehakt wurden, gehören
            // genauso auf die Karte wie die angeflogenen; abgewählte fallen
            // wieder heraus. Nur der gezählte Stand hängt daran, die Einträge
            // und damit auch die Flughafenpunkte bleiben unberührt.
            val marked = summary
                .plusCountries(Settings.getManualCountries(appContext))
                .minusCountries(Settings.getHiddenCountries(appContext))
            // Bundesstaaten/-länder: angeflogene Regionen je Flughafen, dazu die
            // Länder, in die es überhaupt einen Flug gab (damit ein Flughafen ohne
            // Regionseintrag das Land nicht unsichtbar macht).
            val stateSummary = StateStats.summarize(
                entries,
                { iata -> AirportData.region(appContext, iata) },
                marked.countries,
                Settings.getManualStates(appContext),
                Settings.getHiddenStates(appContext)
            )
            // Fehlt das Asset oder ist es unlesbar, bleibt die Karte leer. Das
            // ist im Bild nicht von einer noch leeren Karte zu unterscheiden,
            // deshalb wird der Fehler hier ausgewiesen.
            val shapes = CountryShapes.load(appContext)
            val states = StateShapes.load(appContext)
            // Die Kacheln brauchen dieselbe Länderliste wie die Karte, sonst
            // wäre die Summe ihrer Nenner eine andere als die Zahl der Länder
            // auf der Länderliste. Dasselbe gilt für den Balken darüber.
            val tiles = shapes?.let { ContinentStats.build(it, marked.countries) }
            val world = shapes?.let { WorldProgress.build(it, marked.countries) }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                if (tiles == null || world == null || states == null) {
                    renderUnavailable()
                } else {
                    this.shapes = shapes
                    this.stateShapes = states
                    this.stateSummary = stateSummary
                    render(
                        marked, tiles, world,
                        hasFlights = summary.flightsCount > 0 || summary.airports.isNotEmpty()
                    )
                }
            }
        }.start()
    }

    /** Die Länderumrisse fehlen: statt einer leeren Karte einen Grund zeigen. */
    private fun renderUnavailable() {
        binding.tvRubbelkarteSubtitle.text = ""
        binding.tvRubbelkarteEmpty.setText(R.string.rubbelkarte_error)
        binding.tvRubbelkarteEmpty.visibility = View.VISIBLE
        binding.rubbelkarte.visibility = View.GONE
        // Ohne Länderliste gäbe es keine Nenner für Balken und Kacheln.
        binding.rubbelkarteProgressRow.visibility = View.GONE
        binding.continentScroll.visibility = View.GONE
    }

    private fun render(
        summary: RubbelkarteStats.Summary,
        tiles: List<ContinentStats.Progress>,
        world: WorldProgress.Progress,
        hasFlights: Boolean
    ) {
        binding.tvRubbelkarteSubtitle.text = if (summary.isEmpty && !hasFlights) {
            getString(R.string.rubbelkarte_subtitle_empty)
        } else {
            getString(
                R.string.rubbelkarte_subtitle,
                summary.countries.size,
                summary.continents.size,
                summary.airports.size
            )
        }

        applyMapArea()
        binding.rubbelkarte.setContent(
            summary.countries.toSet(),
            summary.airports,
            RubbelkarteView.StateContent(stateSummary.states, stateSummary.countries)
        )
        binding.rubbelkarte.contentDescription =
            getString(R.string.rubbelkarte_map_desc, summary.countries.size)

        binding.rubbelkarte.visibility = View.VISIBLE
        binding.tvRubbelkarteEmpty.setText(R.string.rubbelkarte_empty)
        binding.tvRubbelkarteEmpty.visibility =
            if (!hasFlights && summary.isEmpty) View.VISIBLE else View.GONE

        binding.rubbelkarteProgressRow.visibility = View.VISIBLE
        bindWorldProgress(world)
        // Ist die halbhohe Seite offen, blendet sie die Kachelzeile aus (siehe
        // [openPage]); ein Neu Laden (z. B. nach dem Abhaken) darf sie dann
        // nicht wieder einblenden.
        binding.continentScroll.visibility =
            if (childFragmentManager.backStackEntryCount > 0) View.GONE else View.VISIBLE
        bindTiles(buildTileUis(tiles))
    }

    /**
     * Der Balken über der Kugel: der Anteil der besuchten Länder an allen, mit
     * der absoluten Zahl links und der Prozentzahl rechts. Beide Zahlen
     * stehen im Klartext in der Oberfläche, sie hängen deshalb nicht am
     * Balken - Screenreader lesen die beiden Felder ohnehin der Reihe nach.
     */
    private fun bindWorldProgress(progress: WorldProgress.Progress) {
        binding.tvRubbelkarteProgressLabel.text = getString(
            R.string.rubbelkarte_progress_label, progress.visited, progress.total
        )
        binding.tvRubbelkarteProgressPercent.text = formatPercent(progress.fraction)
        binding.progressRubbelkarteWorld.setProgressCompat(
            (progress.fraction * PERCENT_MAX).toInt(), true
        )
    }

    /**
     * Prozentzahl in der Sprache der App: deutsch mit Leerzeichen vor dem
     * Prozentzeichen, englisch ohne. Ohne Nachkommastellen, sonst stünde bei
     * einem Land von zweihundertfünfzig "0,4 %" neben dem Balken.
     */
    private fun formatPercent(fraction: Float): String =
        NumberFormat.getPercentInstance(resources.configuration.locales[0]).apply {
            maximumFractionDigits = 0
        }.format(fraction)

    /** Eine Kachel der Zeile: Kontinent/EU oder Land mit eigenen Staaten. */
    private data class TileUi(
        val key: String,
        val title: String,
        val visited: Int,
        val total: Int,
        val kind: Kind,
        /** Länder- und EU-Kacheln: Flagge als dezenter Hintergrund der Kachel. */
        val flag: String = "",
        /** Nur Länder-Kacheln: ISO-2 des Landes. */
        val countryIso2: String? = null,
        /** Nur Länder-Kacheln: Beschriftung der Einheit für Screenreader. */
        val unitRes: Int = 0,
        /** EU-Kachel: Sterne im Kreis statt Bogen. */
        val stars: Boolean = false
    ) {
        val fraction: Float get() = if (total <= 0) 0f else (visited.toFloat() / total).coerceIn(0f, 1f)
        val percent: Int get() = (fraction * 100f).roundToInt()

        enum class Kind { CONTINENT, COUNTRY }
    }

    /**
     * Baut die Kachelliste: die Kontinente - Europa als eigener Kontinent wie
     * [Continents.ORDER] vorsieht -, sortiert wie [ContinentStats.build] liefert,
     * und ganz rechts - auch wenn davor ein Kontinent 0 % hat - die Kacheln
     * Deutschland und Vereinigte Staaten mit ihren Bundesstaaten/-ländern.
     * Dahinter, als letzte Kachel, die EU mit Sternen im Kreis und EU-Flagge
     * dezent im Hintergrund.
     */
    private fun buildTileUis(progress: List<ContinentStats.Progress>): List<TileUi> {
        val german = useGermanNames()
        val out = mutableListOf<TileUi>()
        // Die EU-Kachel steht nicht bei den Kontinenten, sondern ganz rechts.
        val eu = progress.firstOrNull { it.continent == Continents.EU }
        for (p in progress) {
            if (p.continent == Continents.EU) continue
            out.add(
                TileUi(
                    key = p.continent,
                    title = getString(Continents.nameRes(p.continent)),
                    visited = p.visited,
                    total = p.total,
                    kind = TileUi.Kind.CONTINENT
                )
            )
        }
        for (country in StateStats.TILE_COUNTRIES) {
            val p = StateStats.tileProgress(stateShapes, country, stateSummary.states)
            val countryShape = shapes.firstOrNull { it.iso2 == country }
            val name = countryShape?.let { if (german) it.nameDe else it.nameEn } ?: country
            out.add(
                TileUi(
                    key = "country:$country",
                    title = name,
                    visited = p.visited,
                    total = p.total,
                    kind = TileUi.Kind.COUNTRY,
                    flag = AirportData.flagEmoji(country),
                    countryIso2 = country,
                    unitRes = if (country == "DE") {
                        R.string.rubbelkarte_state_unit_de
                    } else {
                        R.string.rubbelkarte_state_unit_us
                    }
                )
            )
        }
        // Ganz rechts, hinter Deutschland und den USA, die EU-Kachel.
        eu?.let { p ->
            out.add(
                TileUi(
                    key = p.continent,
                    title = getString(Continents.nameRes(p.continent)),
                    visited = p.visited,
                    total = p.total,
                    kind = TileUi.Kind.CONTINENT,
                    // Die EU-Kachel trägt die EU-Flagge dezent im Hintergrund.
                    flag = AirportData.flagEmoji(Continents.EU),
                    stars = true
                )
            )
        }
        return out
    }

    /**
     * Füllt die Kachelzeile. Es sind höchstens neun Kacheln, dafür genügt
     * eine einfache Zeile und kein RecyclerView.
     *
     * Die vorhandenen Kacheln werden wiederverwendet: Wer vom Bottom-Sheet
     * zurückkommt, soll die Ringe von ihrem alten auf den neuen Stand laufen
     * sehen und nicht neun neue Views aufbauen. Wiedererkannt wird über den
     * Schlüssel im [View.getTag], deshalb bleibt die Reihenfolge der Kacheln
     * auch dann erhalten, wenn [buildTileUis] sie einmal anders liefert.
     */
    private fun bindTiles(items: List<TileUi>) {
        val row = binding.continentTiles
        val vorhanden = HashMap<String, View>(row.childCount)
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            (child.tag as? String)?.let { vorhanden[it] = child }
        }
        row.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (item in items) {
            val view = vorhanden.remove(item.key)
                ?: inflater.inflate(R.layout.item_continent_tile, row, false)
            view.tag = item.key
            bindTile(view, item)
            row.addView(view)
        }
    }

    /**
     * Eine Kachel: Name, Ring (bei der EU Sterne im Kreis) mit dem Anteil und
     * darunter die absolute Zahl. Länder-Kacheln tragen ihre Flagge dezent im
     * Hintergrund. Der [View.setContentDescription] ersetzt die Texte für
     * Screenreader, die hochkant nebeneinander sonst unlesbar vorkämen.
     *
     * Ein Tipp zoomt die Kugel auf die Region und öffnet unten das Blatt mit
     * der Liste, siehe [openTile]. Der Klick hängt am Kachel-View und nicht an
     * den Texten darin, weil die Kachel die auswechselbare Hülle ist und
     * [bindTiles] sie weiterverwendet.
     */
    private fun bindTile(view: View, item: TileUi) {
        val tile = ItemContinentTileBinding.bind(view)
        tile.tvContinentName.text = item.title
        tile.tvContinentCount.text =
            getString(R.string.rubbelkarte_continent_count, item.visited, item.total)
        tile.vContinentProgress.starMode = item.stars
        tile.vContinentProgress.setProgress(item.fraction)
        // Länder- und EU-Kacheln tragen ihre Flagge als kompletten Hintergrund:
        // die TileFlagView zentriert das Emoji in der Kachelmitte und skaliert
        // es so, dass es über den Kachelrand hinausragt und die Fläche füllt.
        if (item.flag.isNotEmpty()) {
            tile.tvContinentFlag.flag = item.flag
            tile.tvContinentFlag.visibility = View.VISIBLE
        } else {
            tile.tvContinentFlag.visibility = View.GONE
        }
        view.contentDescription =
            if (item.kind == TileUi.Kind.COUNTRY) {
                getString(
                    R.string.rubbelkarte_state_tile_desc,
                    item.title, item.visited, item.total,
                    getString(item.unitRes), item.percent
                )
            } else {
                getString(
                    R.string.rubbelkarte_continent_desc,
                    item.title, item.visited, item.total, item.percent
                )
            }
        view.setOnClickListener { openTile(item) }
    }

    /**
     * Tipp auf eine Kachel: Die Kugel zoomt auf die Region und hebt ihre Grenzen
     * hervor (Kontinent/EU: alle Länder der Region; Länder-Kachel: das Land
     * selbst), und unten öffnet sich auf etwa der Hälfte des Bildschirms die
     * Seite mit der zugehörigen Liste - darüber bleibt die Kugel sichtbar.
     */
    private fun openTile(item: TileUi) {
        if (_binding == null) return
        when (item.kind) {
            TileUi.Kind.CONTINENT -> {
                val codes = Continents.countriesOf(item.key).toSet()
                val focus = MapFocus.forCountries(shapes) { it.iso2.uppercase() in codes }
                if (focus != null) {
                    binding.rubbelkarte.zoomTo(focus.lat, focus.lon, focus.zoom)
                }
                binding.rubbelkarte.setHighlight(codes)
            }

            TileUi.Kind.COUNTRY -> {
                val country = item.countryIso2 ?: return
                val focus = MapFocus.forStates(stateShapes.filter { it.iso2 == country })
                    ?: shapes.firstOrNull { it.iso2 == country }?.let { MapFocus.forCountry(it) }
                if (focus != null) {
                    binding.rubbelkarte.zoomTo(focus.lat, focus.lon, focus.zoom)
                }
                binding.rubbelkarte.setHighlight(setOf(country))
            }
        }
        openPage(item)
    }

    /**
     * Öffnet die halbhohe Seite unten im [R.id.page_host]: eine komplette
     * Seite (Länder-Checkliste bzw. Staatenliste), deren Höhe ein Anteil des
     * Bildschirms ist - die Hälfte bei umfangreichen Listen, sonst ein Drittel,
     * damit oben genug von der Kugel sichtbar bleibt.
     */
    private fun openPage(item: TileUi) {
        if (_binding == null) return
        val total = when (item.kind) {
            TileUi.Kind.CONTINENT -> Continents.countriesOf(item.key).size
            TileUi.Kind.COUNTRY -> stateShapes.count { it.iso2 == item.countryIso2 }
        }
        val fraction = if (total >= PAGE_HALF_MIN) PAGE_HALF else PAGE_THIRD
        // Eine bereits offene Seite wird ersetzt, nicht aufgestapelt: Erst poppen
        // - der Backstack-Listener schaltet das page_host dabei aus ([hidePage]) -
        // und erst danach wird es mit der neuen Höhe wieder sichtbar. Umgekehrt
        // bliebe das page_host nach dem popBackStackImmediate auf GONE stehen und
        // die ersetzte Seite wäre unsichtbar.
        pageOpening = true
        childFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        pageOpening = false
        val host = binding.pageHost
        host.visibility = View.VISIBLE
        val lp = host.layoutParams
        lp.height = (fraction * resources.displayMetrics.heightPixels).toInt()
        host.layoutParams = lp
        // Die Kugel zentriert sich auf den sichtbaren Streifen über der Seite
        // und rutscht etwas nach oben, statt in die verdeckte Bildschirmmitte.
        binding.rubbelkarte.setBottomCover(lp.height)
        // Die Kachelzeile weicht der Seite: sonst schrumpft die Kugel oben auf
        // einen schmalen Streifen und unter den Kacheln bleibt der Platz für die
        // schwebende Navigationsleiste als schwarzer Balken stehen.
        binding.continentScroll.visibility = View.GONE

        val args = Bundle().apply {
            putBoolean(CountriesFragment.ARG_EMBEDDED, true)
            when (item.kind) {
                TileUi.Kind.CONTINENT ->
                    putString(CountriesFragment.ARG_CONTINENT, item.key)
                TileUi.Kind.COUNTRY ->
                    putString(CountriesFragment.ARG_STATES, item.countryIso2)
            }
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.page_host, CountriesFragment::class.java, args)
            .addToBackStack(null)
            .commit()
    }

    /** Blendet die halbhohe Seite wieder aus. */
    private fun hidePage() {
        if (_binding == null) return
        binding.pageHost.visibility = View.GONE
        val lp = binding.pageHost.layoutParams
        lp.height = 0
        binding.pageHost.layoutParams = lp
        binding.rubbelkarte.setBottomCover(0)
        // Die Kachelzeile kommt zurück - außer wenn die Karte wegen fehlender
        // Länderumrisse ohnehin ausgeblendet ist ([renderUnavailable]).
        if (binding.rubbelkarte.visibility == View.VISIBLE) {
            binding.continentScroll.visibility = View.VISIBLE
        }
    }

    /**
     * Ist die halbhohe Seite offen, schließt die Zurück-Taste zuerst sie; erst
     * der zweite Druck verlässt die Rubbelkarte (an den NavController durch).
     */
    override fun onBackPressed(): Boolean {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            return true
        }
        return false
    }

    override fun zoomToCountry(iso2: String) {
        if (_binding == null) return
        val shape = shapes.firstOrNull { it.iso2 == iso2 } ?: return
        val focus = MapFocus.forCountry(shape)
        binding.rubbelkarte.zoomTo(focus.lat, focus.lon, focus.zoom)
        binding.rubbelkarte.setHighlight(setOf(iso2))
    }

    override fun zoomToState(code: String) {
        if (_binding == null) return
        val state = stateShapes.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: return
        val focus = MapFocus.forState(state)
        binding.rubbelkarte.zoomTo(focus.lat, focus.lon, focus.zoom)
        binding.rubbelkarte.setHighlight(setOf(state.code))
    }

    override fun onCountriesChanged() {
        // Abgehakt/angehakt auf der halbhohen Seite: Karte und Kacheln neu laden.
        refresh()
    }

    /** Die App ist deutschsprachig, values-en liefert die englischen Namen. */
    private fun useGermanNames(): Boolean {
        val language = resources.configuration.locales[0].language
        return language.isEmpty() || language == Settings.LANG_DE
    }

    override fun onDestroyView() {
        childFragmentManager.removeOnBackStackChangedListener(backStackListener)
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** [com.google.android.material.progressindicator.BaseProgressIndicator] zählt auf 100. */
        const val PERCENT_MAX = 100

        /** Ab dieser Listengröße nimmt die halbhohe Seite die Hälfte des Bildschirms. */
        const val PAGE_HALF_MIN = 20

        /** Seitenhöhe bei großen Listen. */
        const val PAGE_HALF = 0.5f

        /** Seitenhöhe bei kleinen Listen. */
        const val PAGE_THIRD = 1f / 3f
    }
}
