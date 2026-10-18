package com.highfly.logbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isEmpty
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.highfly.logbook.databinding.FragmentAchievementsBinding
import com.highfly.logbook.databinding.ItemAchievementTimelineBinding
import com.highfly.logbook.databinding.ItemAchievementYearBinding
import java.time.format.DateTimeFormatter

/**
 * Seite "Achievements": oben die klassische Kopfleiste, darunter eine
 * hervorgehobene Kachel über die ganze Breite mit dem Fortschritt
 * ("x von y" samt Balken) und darunter der Zeitstrahl der bereits erreichten
 * Erfolge, der jüngste oben.
 *
 * Ein Tipp auf die Kachel öffnet die Übersicht aller Erfolge nach Kategorie,
 * ein Tipp auf eine Zeile des Zeitstrahls den Eintrag zum Bearbeiten. Der
 * Zeitstrahl wird bei jeder Rückkehr neu gezeichnet, damit er nach einer
 * Änderung an einem Eintrag sofort stimmt.
 */
class AchievementsFragment : Fragment() {

    private var _binding: FragmentAchievementsBinding? = null
    private val binding get() = _binding!!

    /** Zählt die Ladevorgänge, damit ein altes Ergebnis nicht mehr greift. */
    private var loadGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAchievementsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnAchievementsBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.cardAchievementsSummary.setOnClickListener {
            findNavController().navigate(R.id.action_achievements_to_grid)
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

    /**
     * Die Auswertung liest Datenbank und Flughäfen und läuft deshalb im
     * Hintergrund. Nach der Rückkehr zählt [loadGeneration]: Ein noch laufender
     * älterer Ladevorgang darf die schon neu gezeichnete Seite nicht
     * überschreiben.
     */
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
        val context = requireContext()
        binding.tvAchievementsSubtitle.text =
            getString(R.string.achievements_progress, result.reachedCount, result.total)
        binding.tvAchSummaryValue.text =
            getString(R.string.achievements_ratio, result.reachedCount, result.total)
        binding.tvAchSummaryPercent.text =
            getString(R.string.achievements_progress_percent, result.percent)
        binding.cardAchievementsSummary.contentDescription =
            getString(R.string.achievements_summary_desc, result.reachedCount, result.total)
        // Der Balken darf ohne Erfolge nicht in den unbestimmten Zustand
        // kippen, deshalb bleibt das Maximum bei mindestens 1.
        binding.achProgress.max = result.total.coerceAtLeast(1)
        binding.achProgress.setProgressCompat(result.reachedCount, true)

        val reached = result.reachedNewestFirst()
        val empty = reached.isEmpty()
        binding.tvAchTimelineTitle.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvAchTimelineEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.achTimeline.removeAllViews()

        // Wie die Monatsüberschriften der Einträge steht über der ersten Zeile
        // eines Jahres die Jahreszahl, links über dem Eintrag.
        var lastYear: Int? = null
        reached.forEachIndexed { index, item ->
            val year = item.date.year
            if (year != lastYear) {
                lastYear = year
                val header = ItemAchievementYearBinding.inflate(
                    layoutInflater, binding.achTimeline, false
                )
                // Ohne Tausendertrennzeichen, sonst stuende da "2.024".
                header.tvAchievementYear.text =
                    String.format(Settings.language(context), "%d", year)
                // Die erste Jahreszahl steht direkt unter dem Titel des
                // Zeitstrahls - da genuegt der Abstand des Containers.
                if (binding.achTimeline.isEmpty()) {
                    (header.root.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
                }
                binding.achTimeline.addView(header.root)
            }
            val row = ItemAchievementTimelineBinding.inflate(
                layoutInflater, binding.achTimeline, false
            )
            row.ivAchievementDot.setImageResource(item.definition.iconRes)
            row.tvAchievementTitle.text = item.definition.title(context)
            row.tvAchievementRoute.text = getString(
                R.string.achievements_timeline_route,
                item.entry.fromAirport.uppercase(),
                item.entry.toAirport.uppercase()
            )
            val date = item.date.format(DATE_LABEL_FORMAT)
            val number = listOfNotNull(item.entry.airline, item.entry.flightNumber)
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            row.tvAchievementMeta.text =
                if (number.isEmpty()) date else date + META_SEPARATOR + number
            row.root.setOnClickListener { openEditEntry(item.entry) }
            if (index == reached.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.achTimeline.addView(row.root)
        }
    }

    /**
     * Eintrag des Zeitstrahls bearbeiten: dieselbe Seite "Flug bearbeiten" wie
     * aus der Eintragliste, mit der Kennung des Eintrags als Argument.
     */
    private fun openEditEntry(entry: LogbookEntry) {
        findNavController().navigate(
            R.id.action_achievements_to_add_entry,
            bundleOf("entryId" to (entry.id ?: -1L))
        )
    }

    private companion object {
        /** Trenner zwischen Datum und Flugnummer auf dem Zeitstrahl. */
        const val META_SEPARATOR = " · "

        val DATE_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
