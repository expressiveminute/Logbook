package com.highfly.logbook

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.FragmentAchievementsGridBinding

/**
 * Übersicht aller Erfolge, nach Kategorie geordnet: je Kategorie eine
 * Überschrift und darunter Zeilen mit fünf Kacheln nebeneinander. Jede Kachel
 * zeigt das Icon des Erfolgs und darunter seinen Titel. Erreichte Erfolge sind
 * farbig, noch offene grau.
 *
 * Die Zeilen und Kacheln entstehen im Code, weil die Zahl der Erfolge von den
 * Kontinenten abhängt und die Kategorien unterschiedlich viele Einträge haben.
 */
class AchievementsGridFragment : LogbookFragment() {

    private var _binding: FragmentAchievementsGridBinding? = null
    private val binding get() = _binding!!

    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAchievementsGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Platz unter der schwebenden Navigationsleiste.
        binding.achievementsGridScroll.addBottomNavPadding()
        binding.btnAchievementsGridBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        Thread {
            val result = Achievements.evaluate(appContext)
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                render(result)
            }
        }.start()
    }

    private fun render(result: Achievements.Result) {
        binding.tvAchievementsGridSubtitle.text =
            getString(R.string.achievements_progress, result.reachedCount, result.total)

        val container = binding.achGridContainer
        container.removeAllViews()
        // Erreichte Erfolge samt auslösendem Flug, für Farbcodierung und Klick.
        val reachedById = result.reached.associateBy { it.definition.id }
        Achievements.Category.entries.forEach { category ->
            val definitions = result.definitions.filter { it.category == category }
            if (definitions.isEmpty()) return@forEach
            container.addView(categoryHeading(getString(category.titleRes)))
            chunkGrid(definitions).forEach { chunk ->
                container.addView(gridRow(chunk, reachedById))
            }
        }
    }

    /**
     * Zeilen der Übersicht: höchstens [GRID_COLUMNS] Kacheln pro Zeile, und die
     * Hauptairline-Meilensteine („n. Flug mit X“) fangen in einer **neuen Zeile**
     * an, statt mit den normalen Flugmeilensteinen gemischt zu werden.
     */
    private fun chunkGrid(definitions: List<Achievements.Definition>): List<List<Achievements.Definition>> {
        val out = mutableListOf<List<Achievements.Definition>>()
        var current = mutableListOf<Achievements.Definition>()
        for (definition in definitions) {
            val newRowForMainAirline =
                definition.kind == Achievements.Kind.MAIN_AIRLINE &&
                    current.isNotEmpty() &&
                    current.last().kind != Achievements.Kind.MAIN_AIRLINE
            if (newRowForMainAirline || current.size >= GRID_COLUMNS) {
                out.add(current)
                current = mutableListOf()
            }
            current.add(definition)
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /** Überschrift einer Kategorie, mit Abstand zur vorigen Gruppe. */
    private fun categoryHeading(text: String): TextView =
        TextView(requireContext()).apply {
            setText(text)
            setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
            )
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HEADING_TOP_MARGIN_DP) }
        }

    /** Eine Zeile mit höchstens fünf Kacheln; fehlende Plätze bleiben leer. */
    private fun gridRow(
        definitions: List<Achievements.Definition>,
        reachedById: Map<String, Achievements.Reached>
    ): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        definitions.forEach { definition ->
            val reached = reachedById[definition.id]
            val tile = layoutInflater.inflate(R.layout.item_achievement_tile, row, false)
            val context = requireContext()
            val title = reached?.title(context) ?: definition.title(context)
            if (reached != null) {
                // Erreichte Kachel: das Icon selbst trägt die Farbe des
                // auslösenden Flugs - Primär (dienstlich) bzw. Tertiär (privat).
                val business = reached.isBusiness
                tile.background = null
                tile.findViewById<ImageView>(R.id.iv_ach_tile_icon).apply {
                    setImageResource(definition.iconRes)
                    imageTintList = ColorStateList.valueOf(
                        color(if (business) COLOR_PRIMARY else COLOR_TERTIARY)
                    )
                }
                tile.findViewById<TextView>(R.id.tv_ach_tile_title).apply {
                    text = title
                    setTextColor(color(COLOR_ON_SURFACE))
                }
                tile.alpha = 1f
                tile.isClickable = true
                tile.isFocusable = true
                // Druckeffekt wie auf den Zeitstrahl-Zeilen.
                val ripple = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                tile.foreground = androidx.core.content.ContextCompat.getDrawable(
                    context, ripple.resourceId
                )
                tile.setOnClickListener { openEditEntry(reached.entry) }
                tile.contentDescription = title + " · " + context.getString(
                    if (business) R.string.ach_badge_duty else R.string.ach_badge_private
                )
            } else {
                tile.background = null
                tile.findViewById<ImageView>(R.id.iv_ach_tile_icon).apply {
                    setImageResource(definition.iconRes)
                    imageTintList = ColorStateList.valueOf(color(COLOR_ON_SURFACE_VARIANT))
                }
                tile.findViewById<TextView>(R.id.tv_ach_tile_title).apply {
                    text = title
                    setTextColor(color(COLOR_ON_SURFACE_VARIANT))
                }
                // Offene Erfolge treten zurück, erreichte stehen farbig da.
                tile.alpha = DIMMED_ALPHA
                tile.isClickable = false
                tile.contentDescription = title
            }
            row.addView(
                tile,
                LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            )
        }
        // Fehlende Spalten bleiben leer, damit die Kacheln einer Zeile so breit
        // sind wie die einer vollen Zeile.
        repeat(GRID_COLUMNS - definitions.size) {
            row.addView(Space(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        }
        return row
    }

    /** Erreichter Erfolg antippen: auslösenden Eintrag zum Bearbeiten öffnen. */
    private fun openEditEntry(entry: LogbookEntry) {
        findNavController().navigate(
            R.id.action_achievements_grid_to_add_entry,
            androidx.core.os.bundleOf("entryId" to (entry.id ?: -1L))
        )
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(_binding!!.root, attr)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** So viele Kacheln stehen nebeneinander. */
        const val GRID_COLUMNS = 5

        const val HEADING_TOP_MARGIN_DP = 20

        /** Deckkraft eines noch nicht erreichten Erfolgs. */
        const val DIMMED_ALPHA = 0.45f

        val COLOR_PRIMARY = com.google.android.material.R.attr.colorPrimary
        val COLOR_TERTIARY = com.google.android.material.R.attr.colorTertiary
        val COLOR_ON_SURFACE = com.google.android.material.R.attr.colorOnSurface
        val COLOR_ON_SURFACE_VARIANT =
            com.google.android.material.R.attr.colorOnSurfaceVariant
    }
}
