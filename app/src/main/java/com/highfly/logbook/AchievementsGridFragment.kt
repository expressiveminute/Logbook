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
        val reachedIds = result.reachedIds
        Achievements.Category.entries.forEach { category ->
            val definitions = result.definitions.filter { it.category == category }
            if (definitions.isEmpty()) return@forEach
            container.addView(categoryHeading(getString(category.titleRes)))
            definitions.chunked(GRID_COLUMNS).forEach { chunk ->
                container.addView(gridRow(chunk, reachedIds))
            }
        }
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
        reachedIds: Set<String>
    ): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        definitions.forEach { definition ->
            val reached = definition.id in reachedIds
            val tile = layoutInflater.inflate(R.layout.item_achievement_tile, row, false)
            val title = definition.title(requireContext())
            tile.findViewById<ImageView>(R.id.iv_ach_tile_icon).apply {
                setImageResource(definition.iconRes)
                imageTintList = ColorStateList.valueOf(
                    color(if (reached) COLOR_PRIMARY else COLOR_ON_SURFACE_VARIANT)
                )
            }
            tile.findViewById<TextView>(R.id.tv_ach_tile_title).apply {
                text = title
                setTextColor(color(if (reached) COLOR_ON_SURFACE else COLOR_ON_SURFACE_VARIANT))
            }
            // Offene Erfolge treten zurück, erreichte stehen farbig da.
            tile.alpha = if (reached) 1f else DIMMED_ALPHA
            tile.contentDescription = title
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
        val COLOR_ON_SURFACE = com.google.android.material.R.attr.colorOnSurface
        val COLOR_ON_SURFACE_VARIANT =
            com.google.android.material.R.attr.colorOnSurfaceVariant
    }
}
