package com.highfly.logbook

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentRubbelkarteBinding
import com.highfly.logbook.databinding.ItemContinentTileBinding
import java.text.NumberFormat

/**
 * Rubbelkarte: Weltkugel mit allen Ländern, in die der Nutzer bereits geflogen
 * ist. Die Kugel ist auf die besuchten Länder zentriert und lässt sich mit dem
 * Finger drehen und zoomen.
 *
 * Der Stift oben rechts öffnet die Länderliste, auf der sich Länder von Hand
 * abhaken lassen. Diese gehören genauso zur Karte wie die angeflogenen.
 *
 * Über der Kugel steht ein Balken mit dem Gesamtstand: Wie viele Länder der
 * Welt besucht sind, als Anteil von allen.
 *
 * Unter der Karte steht eine Zeile Kontinent-Kacheln. Sie zeigt je Kontinent,
 * wie viele Länder besucht sind und wie viele es dort gibt - auch ohne jeden
 * Flug, denn die noch fehlenden Länder sind genauso interessant wie die
 * besuchten. Sie passen nicht alle nebeneinander und werden deshalb von
 * rechts nach links durchgestrichen. Ein Tipp auf eine Kachel öffnet die
 * Länderliste dieses Kontinents, damit sich die fehlenden Länder dort abhaken
 * lassen.
 *
 * Bewusst ohne Zurück-Button: Die Seite ist ein Tab der Navigationsleiste und
 * wird über den Tab oder die System-Zurück-Taste verlassen.
 */
class RubbelkarteFragment : Fragment() {

    private var _binding: FragmentRubbelkarteBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRubbelkarteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
            val summary = RubbelkarteStats.summarize(LogbookRepository.getEntries()) { iata ->
                AirportData.country(appContext, iata)
            }
            // Länder, die auf der Länderliste von Hand abgehakt wurden, gehören
            // genauso auf die Karte wie die angeflogenen.
            val marked = summary.plusCountries(Settings.getManualCountries(appContext))
            // Fehlt das Asset oder ist es unlesbar, bleibt die Karte leer. Das
            // ist im Bild nicht von einer noch leeren Karte zu unterscheiden,
            // deshalb wird der Fehler hier ausgewiesen.
            val shapes = CountryShapes.load(appContext)
            // Die Kacheln brauchen dieselbe Länderliste wie die Karte, sonst
            // wäre die Summe ihrer Nenner eine andere als die Zahl der Länder
            // auf der Länderliste. Dasselbe gilt für den Balken darüber.
            val tiles = shapes?.let { ContinentStats.build(it, marked.countries) }
            val world = shapes?.let { WorldProgress.build(it, marked.countries) }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                if (tiles == null || world == null) {
                    renderUnavailable()
                } else {
                    render(marked, tiles, world)
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
        world: WorldProgress.Progress
    ) {
        binding.tvRubbelkarteSubtitle.text = if (summary.isEmpty) {
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
            summary.airports
        )
        binding.rubbelkarte.contentDescription =
            getString(R.string.rubbelkarte_map_desc, summary.countries.size)

        val hasFlights = !summary.isEmpty
        binding.rubbelkarte.visibility = View.VISIBLE
        binding.tvRubbelkarteEmpty.setText(R.string.rubbelkarte_empty)
        binding.tvRubbelkarteEmpty.visibility = if (hasFlights) View.GONE else View.VISIBLE

        binding.rubbelkarteProgressRow.visibility = View.VISIBLE
        bindWorldProgress(world)
        binding.continentScroll.visibility = View.VISIBLE
        bindTiles(tiles)
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

    /**
     * Füllt die Kachelzeile. Es sind höchstens sieben Kacheln, dafür genügt
     * eine einfache Zeile und kein RecyclerView.
     *
     * Die vorhandenen Kacheln werden wiederverwendet: Wer von der Länderliste
     * zurückkommt, soll die Ringe von ihrem alten auf den neuen Stand laufen
     * sehen und nicht sieben neue Views aufbauen. Wiedererkannt wird über den
     * Kontinent im [View.getTag], deshalb bleibt die Reihenfolge der Kacheln
     * auch dann erhalten, wenn [ContinentStats.build] sie einmal anders
     * liefert.
     */
    private fun bindTiles(progress: List<ContinentStats.Progress>) {
        val row = binding.continentTiles
        val vorhanden = HashMap<String, View>(row.childCount)
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            (child.tag as? String)?.let { vorhanden[it] = child }
        }
        row.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (item in progress) {
            val view = vorhanden.remove(item.continent)
                ?: inflater.inflate(R.layout.item_continent_tile, row, false)
            view.tag = item.continent
            bindTile(view, item)
            row.addView(view)
        }
    }

    /**
     * Eine Kachel: Name des Kontinents, Ring mit dem Anteil und darunter die
     * absolute Zahl. Der [View.setContentDescription] ersetzt die drei Texte
     * für Screenreader, die hochkant nebeneinander sonst unlesbar vorkämen.
     *
     * Ein Tipp auf die Kachel öffnet die Länderliste dieses Kontinents - dieselbe
     * Seite wie hinter dem Stift, nur auf diesen Kontinent beschränkt. Der
     * Klick hängt am Kachel-View und nicht an den Texten darin, weil die Kachel
     * die auswechselbare Hülle ist und [bindTiles] sie weiterverwendet.
     */
    private fun bindTile(view: View, item: ContinentStats.Progress) {
        val name = getString(Continents.nameRes(item.continent))
        val tile = ItemContinentTileBinding.bind(view)
        tile.tvContinentName.text = name
        tile.tvContinentCount.text =
            getString(R.string.rubbelkarte_continent_count, item.visited, item.total)
        tile.vContinentProgress.setProgress(item.fraction)
        view.contentDescription = getString(
            R.string.rubbelkarte_continent_desc,
            name,
            item.visited,
            item.total,
            item.percent
        )
        view.setOnClickListener { openContinent(item.continent) }
    }

    private fun openContinent(continent: String) {
        findNavController().navigate(
            R.id.action_rubbelkarte_to_countries,
            bundleOf(CountriesFragment.ARG_CONTINENT to continent)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** [com.google.android.material.progressindicator.BaseProgressIndicator] zählt auf 100. */
        const val PERCENT_MAX = 100
    }
}
