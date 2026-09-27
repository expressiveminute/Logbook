package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentRubbelkarteBinding
import com.highfly.logbook.databinding.ItemContinentTileBinding

/**
 * Rubbelkarte: Weltkarte mit allen Ländern, in die der Nutzer bereits geflogen
 * ist. Die Karte folgt der Einstellung "Weltkartenformat" - im Querformat die
 * flache Karte, im Hochformat ein Globus.
 *
 * Der Stift oben rechts öffnet die Länderliste, auf der sich Länder von Hand
 * abhaken lassen. Diese gehören genauso zur Karte wie die angeflogenen.
 *
 * Unter der Karte steht eine Zeile Kontinent-Kacheln. Sie zeigt je Kontinent,
 * wie viele Länder besucht sind und wie viele es dort gibt - auch ohne jeden
 * Flug, denn die noch fehlenden Länder sind genauso interessant wie die
 * besuchten. Sie passen nicht alle nebeneinander und werden deshalb von
 * rechts nach links durchgestrichen.
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
        applyMapArea(mapFormat(requireContext().applicationContext))
        binding.btnCountries.setOnClickListener {
            findNavController().navigate(R.id.action_rubbelkarte_to_countries)
        }
        refresh()
    }

    /**
     * Im Hochformat füllt die Karte den Raum unter der Überschrift ganz, damit
     * die Kugel auch Platz für die Bildschirmmitte hat. Im Querformat ist die
     * flache Karte nur knapp halb so hoch wie breit; der Rest darunter bleibt
     * frei und wird von den Kontinent-Kacheln eingenommen.
     *
     * Ist das Gerät selbst quer, passt die halbe Bildschirmbreite als Höhe
     * nicht mehr neben Überschrift und Kachelzeile. Dann nimmt die Karte nur
     * den Rest ein und zeichnet sich kleiner in diese Höhe - sonst schöbe sich
     * die Kachelzeile ganz aus dem Bild.
     */
    private fun applyMapArea(format: String) {
        val fuelltHoehe = format == RubbelkarteView.FORMAT_PORTRAIT || isQuerformat()
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
        val format = mapFormat(appContext)

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
            // auf der Länderliste.
            val tiles = shapes?.let { ContinentStats.build(it, marked.countries) }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                if (tiles == null) {
                    renderUnavailable()
                } else {
                    render(marked, format, tiles)
                }
            }
        }.start()
    }

    /**
     * Die Einstellung "Weltkartenformat" entscheidet, ob die Karte flach oder
     * als Kugel gezeichnet wird.
     */
    private fun mapFormat(context: Context): String =
        if (Settings.getMapOrientation(context) == Settings.MAP_ORIENTATION_PORTRAIT) {
            RubbelkarteView.FORMAT_PORTRAIT
        } else {
            RubbelkarteView.FORMAT_LANDSCAPE
        }

    /** Die Länderumrisse fehlen: statt einer leeren Karte einen Grund zeigen. */
    private fun renderUnavailable() {
        binding.tvRubbelkarteSubtitle.text = ""
        binding.tvRubbelkarteEmpty.setText(R.string.rubbelkarte_error)
        binding.tvRubbelkarteEmpty.visibility = View.VISIBLE
        binding.rubbelkarte.visibility = View.GONE
        // Ohne Länderliste gäbe es keine Nenner für die Kacheln.
        binding.continentScroll.visibility = View.GONE
    }

    private fun render(
        summary: RubbelkarteStats.Summary,
        format: String,
        tiles: List<ContinentStats.Progress>
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

        applyMapArea(format)
        binding.rubbelkarte.setContent(
            format,
            summary.countries.toSet(),
            summary.airports
        )
        binding.rubbelkarte.contentDescription =
            getString(R.string.rubbelkarte_map_desc, summary.countries.size)

        val hasFlights = !summary.isEmpty
        binding.rubbelkarte.visibility = View.VISIBLE
        binding.tvRubbelkarteEmpty.setText(R.string.rubbelkarte_empty)
        binding.tvRubbelkarteEmpty.visibility = if (hasFlights) View.GONE else View.VISIBLE

        binding.continentScroll.visibility = View.VISIBLE
        bindTiles(tiles)
    }

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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
