package com.highfly.logbook

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.FragmentDistanceDetailBinding

/**
 * Detailseite der Kachel "Distanz". Oben der längste und der kürzeste Flug des
 * Zeitraums als Karte, deren Strecke sich animiert aufbaut; darunter die
 * Erdumrundungen, der Weg zum Mond und der Weg zur Sonne als kleine 2D-Grafiken
 * mit hochzählender Zahl.
 *
 * Die Abschnitte werden nacheinander eingeblendet: erst wenn die Strecke des
 * längsten Flugs gezeichnet ist, erscheint der kürzeste, danach die drei
 * Grafiken. Ground Transfers zählen nicht mit, wie überall bei der Distanz.
 */
class DistanceDetailFragment : LogbookFragment() {

    private companion object {
        /**
         * Abstand, in dem die drei Grafik-Abschnitte nacheinander erscheinen.
         * Bewusst unabhängig von den Animationsdauern: die Seite soll zügig
         * aufgehen, auch wenn die Animationen länger laufen.
         */
        const val REVEAL_MS = 2200L
    }

    private var _binding: FragmentDistanceDetailBinding? = null

    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())

    /** Alle geplanten Einblendschritte, damit sie die Seite nicht überleben. */
    private val pending = mutableListOf<Runnable>()

    private var loadGeneration = 0

    /** Eine Strecke mit den aufgelösten Koordinaten für die Karte. */
    private class RouteInfo(
        val fromCode: String,
        val toCode: String,
        val from: AirportData.GeoLocation?,
        val to: AirportData.GeoLocation?,
        val km: Int
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDistanceDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvDetailTitle.text = getString(R.string.tile_distance)
        binding.tvDetailSubtitle.text = getString(
            R.string.tile_detail_period_flight_type,
            PeriodOptions.label(requireContext(), Settings.getDefaultPeriodKey(requireContext())),
            FlightTypeOptions.label(
                requireContext(),
                Settings.getFlightTypeFilterKey(requireContext())
            )
        )

        loadData()
    }

    /**
     * Liest Flüge und Flughafenkoordinaten im Hintergrund; die Assets sind beim
     * ersten Aufruf noch nicht im Speicher und würden den Aufbau sonst spürbar
     * verzögern.
     */
    private fun loadData() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        Thread {
            val entries = DashboardStats.flyingEntries(DashboardStats.dashboardEntries(appContext))
            val distance = DashboardStats.distanceDetails(appContext, entries)
            val withDistance = entries.filter { (it.distanceKm ?: 0) > 0 }
            val longest = withDistance.maxByOrNull { it.distanceKm ?: 0 }
            val shortest = withDistance.minByOrNull { it.distanceKm ?: 0 }
            val longestInfo = longest?.let { routeInfo(appContext, it) }
            val shortestInfo = shortest?.let { routeInfo(appContext, it) }
            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                bind(entries, distance, longestInfo, shortestInfo)
            }
        }.start()
    }

    private fun routeInfo(
        context: android.content.Context,
        entry: LogbookEntry
    ): RouteInfo = RouteInfo(
        fromCode = entry.fromAirport.uppercase(),
        toCode = entry.toAirport.uppercase(),
        from = AirportData.location(context, entry.fromAirport),
        to = AirportData.location(context, entry.toAirport),
        km = entry.distanceKm ?: 0
    )

    private fun bind(
        entries: List<LogbookEntry>,
        distance: DashboardStats.DistanceDetails,
        longest: RouteInfo?,
        shortest: RouteInfo?
    ) {
        if (entries.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }

        bindRoute(
            binding.tvLongestRoute,
            binding.mapLongest,
            longest
        )
        bindRoute(
            binding.tvShortestRoute,
            binding.mapShortest,
            shortest
        )

        val context = requireContext()
        val orbitsText = factorValue(context, distance.earthOrbits)
        val moonText = factorValue(context, distance.moonTrips)
        val sunText = factorValue(context, distance.sunTrips)

        val reached = DashboardStats.sunPercentReached(distance.totalKm)
        val locale = Settings.language(context)
        binding.tvSunProgress.text = getString(
            R.string.distance_detail_sun_progress,
            String.format(locale, "%,.3f", reached),
            String.format(locale, "%,.3f", 100.0 - reached)
        )
        val remainingKm = DashboardStats.sunRemainingKm(distance.totalKm)
        binding.tvSunKm.text = getString(
            R.string.distance_detail_sun_km,
            DashboardStats.kmText(context, distance.totalKm),
            DashboardStats.kmText(context, remainingKm)
        )

        startSequence(longest, shortest, distance.sunTrips.toFloat(), orbitsText, moonText, sunText)
    }

    /** Beschriftet die Strecke und übergibt sie der Karte, falls Koordinaten da sind. */
    private fun bindRoute(routeLabel: TextView, map: RouteMiniMapView, info: RouteInfo?) {
        val context = requireContext()
        if (info == null) {
            routeLabel.text = context.getString(R.string.distance_none)
            map.visibility = View.GONE
            return
        }
        routeLabel.text = getString(
            R.string.distance_detail_route,
            "${info.fromCode}–${info.toCode}",
            DashboardStats.kmText(context, info.km)
        )
        val from = info.from
        val to = info.to
        if (from == null || to == null) {
            map.visibility = View.GONE
            return
        }
        map.setRoute(from, to, info.fromCode, info.toCode)
    }

    /**
     * Reiht die Abschnitte hintereinander auf: Jeder erscheint, wenn die
     * Animation des vorigen durch ist, und startet dann seine eigene.
     */
    private fun startSequence(
        longest: RouteInfo?,
        shortest: RouteInfo?,
        sunFraction: Float,
        orbitsText: CharSequence,
        moonText: CharSequence,
        sunText: CharSequence
    ) {
        val mapMs = RouteMiniMapView.ROUTE_ANIM_MS
        schedule(0L) {
            reveal(binding.sectionLongest)
            if (longest?.from != null && longest.to != null) {
                binding.mapLongest.playRouteAnimation()
            }
        }
        schedule(mapMs) {
            reveal(binding.sectionShortest)
            if (shortest?.from != null && shortest.to != null) {
                binding.mapShortest.playRouteAnimation()
            }
        }
        // Die Zahl zählt erst hoch, wenn der Abschnitt sichtbar ist; sonst wäre
        // sie beim Einblenden längst fertig.
        schedule(2 * mapMs) {
            reveal(binding.sectionOrbits)
            animateValue(binding.tvOrbitsValue, orbitsText)
            binding.viewOrbits.start(SpaceDistanceView.Mode.ORBITS)
        }
        schedule(2 * mapMs + REVEAL_MS) {
            reveal(binding.sectionMoon)
            animateValue(binding.tvMoonValue, moonText)
            binding.viewMoon.start(SpaceDistanceView.Mode.MOON)
        }
        schedule(2 * mapMs + 2 * REVEAL_MS) {
            reveal(binding.sectionSun)
            animateValue(binding.tvSunValue, sunText)
            binding.viewSun.start(
                SpaceDistanceView.Mode.SUN,
                sunFraction
            )
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        val runnable = Runnable { if (_binding != null) block() }
        pending += runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun reveal(view: View) {
        if (view.visibility == View.VISIBLE) return
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(300).start()
        scrollIntoView(view)
    }

    /**
     * Schiebt einen gerade eingeblendeten Abschnitt ins Bild. Die Seite wächst
     * beim Öffnen Stück für Stück nach unten; ohne das Nachführen bliebe der
     * untere Teil ausserhalb des Bildschirms. Nach dem Einblenden (also nach dem
     * Neuvermessen) wird so weit gescrollt, dass der Abschnitt vollständig zu
     * sehen ist. Ist er höher als der Bildbereich, bleibt seine Oberkante oben
     * stehen, damit Überschrift und Grafik sichtbar sind.
     */
    private fun scrollIntoView(view: View) {
        val scroll = binding.scrollView
        view.doOnLayout {
            if (_binding == null) return@doOnLayout
            val viewport = scroll.height
            if (viewport == 0) return@doOnLayout
            // Schon komplett sichtbar: nicht unnötig nachführen.
            if (view.bottom <= scroll.scrollY + viewport) return@doOnLayout
            val sectionHeight = view.bottom - view.top
            val target = if (sectionHeight <= viewport) {
                view.bottom - viewport
            } else {
                view.top
            }
            scroll.smoothScrollTo(0, target.coerceAtLeast(0))
        }
    }

    /**
     * Faktorwert in der Gestalt der Kachel: die Zahl, dahinter klein und
     * andersfarbig die Einheit "×". [TileValueAnimator] blendet den gestylten
     * Anhang erst nach dem Hochzählen ein.
     */
    private fun factorValue(context: android.content.Context, value: Double): CharSequence {
        val builder = SpannableStringBuilder()
        builder.append(DashboardStats.factorText(value, Settings.language(context)))
        val unitColor = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        val start = builder.length + 1
        builder.append(" ×")
        builder.setSpan(
            ForegroundColorSpan(unitColor),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            RelativeSizeSpan(0.55f),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }

    private fun animateValue(view: TextView, text: CharSequence) {
        TileValueAnimator.animate(view, text)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        pending.forEach { handler.removeCallbacks(it) }
        pending.clear()
        binding.mapLongest.cancelRouteAnimation()
        binding.mapShortest.cancelRouteAnimation()
        binding.viewOrbits.stop()
        binding.viewMoon.stop()
        binding.viewSun.stop()
        TileValueAnimator.cancelAll()
        _binding = null
    }
}
