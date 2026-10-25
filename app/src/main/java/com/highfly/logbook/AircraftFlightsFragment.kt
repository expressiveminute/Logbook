package com.highfly.logbook

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.FragmentFlightRouteDetailBinding
import com.highfly.logbook.databinding.ItemAirportFactBinding
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding
import com.highfly.logbook.databinding.ItemRouteStatBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Detailseite für einen Flugzeugtyp oder eine Registrierung aus dem Hangar:
 * oben die animierte Karte mit allen Flügen dieses Typs bzw. dieser
 * Registrierung, darunter die Kennzahlen - wie oft, wie weit und wie lange
 * damit geflogen wurde (beim Flugzeugtyp zusätzlich der Zeitraum vom ersten bis
 * zum letzten Flug) - und zuletzt der Zeitstrahl aller dieser Flüge, der
 * jüngste oben, im Aufbau der Layover-Detailseite. Aufgerufen wird die Seite aus
 * der Hangar-Kachel, siehe [TileDetailFragment].
 *
 * Anders als die Detailseite einer Strecke ranken die Kennzahlen hier nicht:
 * Dieselbe Zahl gibt es nur einmal je Typ bzw. Registrierung.
 */
class AircraftFlightsFragment : LogbookFragment() {

    private var _binding: FragmentFlightRouteDetailBinding? = null
    private val binding get() = _binding!!

    /** Ob nach Flugzeugtyp ([MODE_TYPE]) oder Registrierung ([MODE_REG]) gefiltert wird. */
    private var mode: String = MODE_TYPE

    /** Der gesuchte Typ bzw. die Registrierung. */
    private var value: String = ""

    /** Verwirft das Ergebnis eines bereits verlassenen Ladevorgangs. */
    private var loadGeneration = 0

    /** Verwirft Foto und Stammdaten eines bereits verlassenen Ladevorgangs. */
    private var infoGeneration = 0

    /** Seitenwechsel-Listener der Fotogalerie, zum Abmelden in onDestroyView. */
    private var photoPageCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFlightRouteDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Platz unter der schwebenden Navigationsleiste.
        binding.scrollView.addBottomNavPadding()

        mode = requireArguments().getString(ARG_MODE) ?: MODE_TYPE
        value = requireArguments().getString(ARG_VALUE).orEmpty()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.tvRouteTitle.text = value
        binding.tvRouteMapTitle.setText(
            if (mode == MODE_REG) R.string.aircraft_map_title_reg
            else R.string.aircraft_map_title_type
        )

        if (value.isBlank()) {
            binding.routeMap.visibility = View.GONE
            binding.routeStatsContainer.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }

        // Nur der Flugzeugtyp bekommt Foto und Stammdaten über der Karte; bei
        // einer Registrierung bleibt der Abschnitt weg.
        if (mode == MODE_TYPE) {
            loadTypeInfo()
        }
    }

    /**
     * Lädt Fotos und Stammdaten des Typs im Hintergrund (beides liegt als
     * Asset vor und ist zwischen den Aufrufen gecacht; das Dekodieren der
     * Fotos würde den Aufbau sonst spürbar verzögern). Wie auf der
     * Airline-Detailseite steht der Abschnitt über der Streckenkarte.
     */
    private fun loadTypeInfo() {
        val generation = ++infoGeneration
        val appContext = requireContext().applicationContext
        val target = value
        binding.tvAircraftName.visibility = View.GONE
        binding.aircraftPhotoCard.visibility = View.GONE
        binding.tvAircraftGeneralTitle.visibility = View.GONE
        binding.tvAircraftFactsSource.visibility = View.GONE
        binding.aircraftFactsContainer.removeAllViews()
        Thread {
            val facts = AircraftFacts.get(appContext, target)
            val code = AircraftFacts.load(appContext).resolve(target)
            val photos = code?.let { AircraftPhotos.load(appContext, it) }.orEmpty()
            view?.post {
                if (generation != infoGeneration || _binding == null) return@post
                bindTypeInfo(facts, photos)
            }
        }.start()
    }

    /** Zeigt Anzeigename, Fotogalerie und Stammdaten des Typs, soweit vorhanden. */
    private fun bindTypeInfo(facts: AircraftFacts.Facts?, photos: List<AircraftPhoto>) {
        if (facts != null) {
            binding.tvAircraftName.text = facts.name
            binding.tvAircraftName.visibility = View.VISIBLE
        }
        if (photos.isNotEmpty()) {
            setupPhotoGallery(photos)
        }
        renderGeneralFacts(facts)
    }

    /** Baut die Fotokachel samt Galerie (Pager, Punkte, Credit) auf. */
    private fun setupPhotoGallery(photos: List<AircraftPhoto>) {
        val pager = binding.vpAircraftPhoto
        pager.adapter = AircraftPhotoAdapter(
            photos,
            getString(R.string.aircraft_photo_desc)
        )
        // Punkte unter dem Bild - nur bei mehr als einem Foto.
        val indicator = binding.aircraftGalleryIndicator
        indicator.removeAllViews()
        if (photos.size > 1) {
            indicator.visibility = View.VISIBLE
            val dotSize = dp(GALLERY_DOT_DP)
            val dotMargin = dp(GALLERY_DOT_MARGIN_DP)
            photos.forEachIndexed { index, _ ->
                val dot = View(requireContext())
                dot.background = galleryDotDrawable(index == pager.currentItem)
                val params = LinearLayout.LayoutParams(dotSize, dotSize)
                params.setMargins(dotMargin, 0, dotMargin, 0)
                indicator.addView(dot, params)
            }
            photoPageCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    for (i in 0 until indicator.childCount) {
                        indicator.getChildAt(i).background =
                            galleryDotDrawable(i == position)
                    }
                    updatePhotoCredit(photos[position.coerceIn(photos.indices)])
                }
            }
            pager.registerOnPageChangeCallback(photoPageCallback!!)
        } else {
            indicator.visibility = View.GONE
        }
        updatePhotoCredit(photos[0])
        // Sanfter Übergang statt hartem Erscheinen.
        binding.aircraftPhotoCard.alpha = 0f
        binding.aircraftPhotoCard.visibility = View.VISIBLE
        binding.aircraftPhotoCard.animate()
            .alpha(1f)
            .setDuration(PHOTO_FADE_MS)
            .start()
    }

    /** Namensnennung des gerade angezeigten Fotos samt Link auf die Datei. */
    private fun updatePhotoCredit(photo: AircraftPhoto) {
        val edited = if (photo.modified) {
            " · " + getString(R.string.airline_photo_edited)
        } else {
            ""
        }
        binding.tvAircraftPhotoCredit.text =
            "© ${photo.author} · ${photo.license}$edited · Wikimedia Commons"
        // Wie die Wikipedia-Zeile: unterstrichen und Akzentfarbe, ein
        // Tipp öffnet die Commons-Dateiseite im Standardbrowser.
        binding.tvAircraftPhotoCredit.setTextColor(
            MaterialColors.getColor(
                binding.tvAircraftPhotoCredit,
                com.google.android.material.R.attr.colorPrimary
            )
        )
        binding.tvAircraftPhotoCredit.paintFlags =
            binding.tvAircraftPhotoCredit.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.tvAircraftPhotoCredit.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.sourceUrl)))
        }
    }

    /** Punkt der Galerie-Anzeige: aktive oder inaktive Seite. */
    private fun galleryDotDrawable(active: Boolean) = ContextCompat.getDrawable(
        requireContext(),
        if (active) R.drawable.bg_gallery_dot_active else R.drawable.bg_gallery_dot
    )!!

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    /** Adapter der Fotogalerie: ein Bild pro Seite, mittig beschnitten. */
    private class AircraftPhotoAdapter(
        private val photos: List<AircraftPhoto>,
        private val description: String
    ) : RecyclerView.Adapter<AircraftPhotoAdapter.PhotoHolder>() {

        class PhotoHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoHolder {
            val image = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            return PhotoHolder(image)
        }

        override fun onBindViewHolder(holder: PhotoHolder, position: Int) {
            holder.image.setImageBitmap(photos[position].bitmap)
            holder.image.contentDescription = description
        }

        override fun getItemCount(): Int = photos.size
    }

    /**
     * Allgemeine Stammdaten über der Karte - unabhängig vom Logbuch:
     * Hersteller, Baubeginn, Erstauslieferung, Erstkunde und ein Link zur
     * englischen Wikipedia-Seite aus `AircraftFacts`. Zeilen ohne Wert
     * bleiben weg; ohne jeden Wert bleibt der ganze Abschnitt unsichtbar
     * (der Quellennachweis hängt daran).
     */
    private fun renderGeneralFacts(facts: AircraftFacts.Facts?) {
        binding.aircraftFactsContainer.removeAllViews()
        if (facts != null) {
            facts.manufacturer?.let {
                addFact(
                    binding.aircraftFactsContainer,
                    R.string.aircraft_fact_manufacturer,
                    it
                )
            }
            facts.productionStart?.let {
                addFact(
                    binding.aircraftFactsContainer,
                    R.string.aircraft_fact_production_start,
                    it.toString()
                )
            }
            facts.firstDelivery?.let {
                addFact(
                    binding.aircraftFactsContainer,
                    R.string.aircraft_fact_first_delivery,
                    formatDeliveryDate(it)
                )
            }
            facts.launchCustomer?.let {
                addFact(
                    binding.aircraftFactsContainer,
                    R.string.aircraft_fact_launch_customer,
                    it
                )
            }
            facts.wiki?.let { url ->
                addFactLink(
                    binding.aircraftFactsContainer,
                    R.string.airline_fact_wikipedia,
                    AirlineFacts.wikiTitle(url),
                    url
                )
            }
        }
        val visible = binding.aircraftFactsContainer.childCount > 0
        binding.tvAircraftGeneralTitle.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvAircraftFactsSource.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Erstauslieferung zur Anzeige: "1988-04-18" wird zu "18.04.1988",
     * "1983-04" (Monat ohne Tag) zu "04.1983", ein blosses Jahr bleibt.
     */
    private fun formatDeliveryDate(value: String): String = when (value.length) {
        10 -> "${value.substring(8, 10)}.${value.substring(5, 7)}.${value.substring(0, 4)}"
        7 -> "${value.substring(5, 7)}.${value.substring(0, 4)}"
        else -> value
    }

    /** Eine Stammdaten-Zeile aus `item_airport_fact`: Beschriftung, Wert. */
    private fun addFact(container: ViewGroup, labelRes: Int, value: String) {
        val row = ItemAirportFactBinding.inflate(layoutInflater, container, false)
        row.tvFactLabel.setText(labelRes)
        row.tvFactValue.text = value
        container.addView(row.root)
    }

    /**
     * Zeile wie [addFact], aber als Link: der Wert ist der Artikeltitel, ein
     * Tipp öffnet die Wikipedia-Seite direkt im Standardbrowser des Systems.
     */
    private fun addFactLink(container: ViewGroup, labelRes: Int, title: String, url: String) {
        val row = ItemAirportFactBinding.inflate(layoutInflater, container, false)
        row.tvFactLabel.setText(labelRes)
        row.tvFactValue.text = title
        row.tvFactValue.setTextColor(
            MaterialColors.getColor(row.root, com.google.android.material.R.attr.colorPrimary)
        )
        row.tvFactValue.paintFlags = row.tvFactValue.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        row.root.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        container.addView(row.root)
    }

    /**
     * Die Flüge werden bei jedem Erscheinen neu gelesen: nach dem Bearbeiten
     * eines Eintrags (die Liste öffnet ihn) soll die Seite den geänderten
     * Eintrag sofort zeigen, ohne dass man sie erneut öffnen muss. Der erste
     * Aufruf kommt direkt nach [onViewCreated].
     */
    override fun onResume() {
        super.onResume()
        if (_binding == null || value.isBlank()) return
        loadStats()
    }

    /**
     * Liest die Flüge und ihre Kennzahlen im Hintergrund; die Kartendaten des
     * ersten Aufrufs würden den Aufbau sonst spürbar verzögern.
     */
    private fun loadStats() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val targetValue = value
        val targetMode = mode
        Thread {
            val entries = DashboardStats.dashboardEntries(appContext)
            val filtered = entries.filter { entry ->
                if (targetMode == MODE_REG) {
                    entry.registration.equals(targetValue, ignoreCase = true)
                } else {
                    entry.aircraftType.equals(targetValue, ignoreCase = true)
                }
            }

            val totalKm = filtered.sumOf { it.distanceKm ?: 0 }
            val totalMinutes = filtered.sumOf { it.flightMinutes ?: 0 }
            val count = filtered.size
            val firstDate = filtered.minOfOrNull { it.date }
            val lastDate = filtered.maxOfOrNull { it.date }
            // Der jüngste Flug steht oben, wie in der Eintragliste. Innerhalb
            // eines Tages entscheidet die Kennung, damit die Reihenfolge fest
            // bleibt.
            val newestFirst = filtered.sortedWith(
                compareByDescending<LogbookEntry> { it.date }
                    .thenByDescending { it.id ?: -1L }
            )

            val routes = filtered.mapNotNull { entry ->
                val from = AirportData.location(appContext, entry.fromAirport.uppercase())
                val to = AirportData.location(appContext, entry.toAirport.uppercase())
                if (from != null && to != null) {
                    Triple(
                        from,
                        to,
                        entry.fromAirport.uppercase() to entry.toAirport.uppercase()
                    )
                } else {
                    null
                }
            }

            view?.post {
                if (generation != loadGeneration || _binding == null) return@post
                bind(count, totalKm, totalMinutes, firstDate, lastDate, routes, newestFirst)
            }
        }.start()
    }

    private fun bind(
        count: Int,
        totalKm: Int,
        totalMinutes: Int,
        firstDate: LocalDate?,
        lastDate: LocalDate?,
        routes: List<Triple<AirportData.GeoLocation, AirportData.GeoLocation, Pair<String, String>>>,
        entries: List<LogbookEntry>
    ) {
        val context = requireContext()
        val locale = Settings.language(context)

        if (routes.isNotEmpty()) {
            binding.routeMap.visibility = View.VISIBLE
            binding.routeMap.setRoutes(routes)
            binding.routeMap.playRouteAnimation()
        } else {
            binding.routeMap.visibility = View.GONE
            binding.tvRouteMapTitle.visibility = View.GONE
        }

        binding.routeStatsContainer.removeAllViews()

        val time = DashboardStats.timeValue(context, totalMinutes, DashboardStats.TimeUnit.HOURS)
        val minutesCaption = if (totalMinutes >= 60) {
            getString(R.string.flight_route_stat_minutes, formatInt(locale, totalMinutes))
        } else {
            null
        }

        val flights = addStat(
            R.drawable.ic_flight,
            R.string.aircraft_stat_flights,
            formatInt(locale, count),
            unit = null,
            caption = null
        )
        val distance = addStat(
            R.drawable.ic_distance_arrow,
            R.string.aircraft_stat_distance,
            formatInt(locale, totalKm),
            unit = getString(R.string.flight_distance_hint),
            caption = null
        )
        val duration = addStat(
            R.drawable.ic_time,
            R.string.aircraft_stat_time,
            time.text,
            unit = time.unit,
            caption = minutesCaption
        )
        // Bei einer Registrierung entfällt der Zeitraum: Er steht schon im
        // Zeitstrahl der Flüge darunter. Beim Flugzeugtyp bleibt die Kennzahl.
        if (mode != MODE_REG) {
            val period = periodText(firstDate, lastDate)
            if (period.isNotEmpty()) {
                addStat(
                    R.drawable.ic_timeline_dot,
                    R.string.aircraft_stat_period,
                    period,
                    unit = null,
                    caption = null
                )
            }
        }

        // Die Zahlen zählen sichtbar von 0 auf ihren Zielwert hoch; die Karte
        // zeichnet währenddessen ihre Strecken auf.
        TileValueAnimator.animate(flights.tvRouteStatValue, flights.tvRouteStatValue.text)
        TileValueAnimator.animate(duration.tvRouteStatValue, duration.tvRouteStatValue.text)
        TileValueAnimator.animate(distance.tvRouteStatValue, distance.tvRouteStatValue.text)

        renderFlightList(entries)
    }

    /**
     * Liste aller Flüge dieser Registrierung bzw. dieses Typs, der jüngste oben,
     * im Aufbau des Layover-Zeitstrahls: Datum, Dreiletterroute, Flugnummer und
     * der Reisebuddy; im Punkt steht die Flugzeit. Ein Tipp öffnet den Eintrag
     * zum Bearbeiten. Dieselbe Zeile wie auf der Layover-Detailseite
     * (`item_layover_timeline`), damit beide Listen gleich aussehen.
     */
    private fun renderFlightList(entries: List<LogbookEntry>) {
        val context = requireContext()
        binding.flightList.removeAllViews()
        if (entries.isEmpty()) {
            binding.tvFlightListTitle.visibility = View.GONE
            binding.flightListRoot.visibility = View.GONE
            return
        }
        binding.tvFlightListTitle.visibility = View.VISIBLE
        binding.flightListRoot.visibility = View.VISIBLE
        entries.forEachIndexed { index, entry ->
            val row = ItemLayoverTimelineBinding.inflate(
                layoutInflater, binding.flightList, false
            )
            row.root.setOnClickListener { openEditEntry(entry) }
            row.tvDate.text = entry.date.format(DATE_LABEL_FORMAT)
            row.timelineDot.text = entry.flightMinutes
                ?.takeIf { it > 0 }
                ?.let { dotTime(context, it) }
                .orEmpty()
            val from = entry.fromAirport.uppercase()
            val to = entry.toAirport.uppercase()
            if (from.isNotBlank() && to.isNotBlank()) {
                row.tvRoute.text = getString(R.string.import_review_summary_route, from, to)
            } else {
                row.tvRoute.visibility = View.GONE
            }
            val number = listOfNotNull(entry.airline, entry.flightNumber)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (number.isNotBlank()) {
                row.tvMeta.visibility = View.VISIBLE
                row.tvMeta.text = number
            }
            val buddy = entry.travelBuddy?.takeIf { it.isNotBlank() }
            if (buddy != null) {
                row.ivBuddy.visibility = View.VISIBLE
                row.tvBuddy.visibility = View.VISIBLE
                row.tvBuddy.text = buddy
            }
            if (index == entries.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.flightList.addView(row.root)
        }
        binding.flightListRoot.post { if (_binding != null) alignFlightListLine() }
    }

    /**
     * Die senkrechte Linie des Zeitstrahls verbindet nur die Punkte: Sie beginnt
     * auf halber Höhe der obersten Zeile und endet auf halber Höhe der
     * untersten, wie auf der Layover-Detailseite.
     */
    private fun alignFlightListLine() {
        val rows = binding.flightList
        val first = rows.getChildAt(0) ?: return
        val last = rows.getChildAt(rows.childCount - 1)
        val start = first.height / 2
        val end = rows.height - last.height / 2
        val params = binding.flightListLine.layoutParams as? ViewGroup.MarginLayoutParams
            ?: return
        params.height = (end - start).coerceAtLeast(0)
        params.topMargin = start
        binding.flightListLine.layoutParams = params
    }

    /**
     * Flugzeit im Punkt des Zeitstrahls: unter einer Stunde in Minuten, sonst
     * in Stunden mit einer Nachkommastelle - kurz genug für den kleinen Punkt.
     * Die Zahl kommt aus derselben Formatierung wie überall sonst.
     */
    private fun dotTime(context: Context, minutes: Int): String {
        val value = DashboardStats.timeValue(context, minutes, DashboardStats.TimeUnit.HOURS)
        return if (minutes < 60) {
            context.getString(R.string.aircraft_flight_dot_minutes, value.text)
        } else {
            context.getString(R.string.aircraft_flight_dot_hours, value.text)
        }
    }

    /** Eintrag der Liste bearbeiten: dieselbe Seite "Flug bearbeiten". */
    private fun openEditEntry(entry: LogbookEntry) {
        findNavController().navigate(
            R.id.action_aircraft_flights_to_add_entry,
            bundleOf("entryId" to (entry.id ?: -1L))
        )
    }

    /** Erster bis letzter Flug als Datumstext; an einem Tag nur ein Datum. */
    private fun periodText(first: LocalDate?, last: LocalDate?): String {
        val format = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        return when {
            first != null && last != null && first == last -> format.format(first)
            first != null && last != null -> format.format(first) + " \u2013 " + format.format(last)
            first != null -> format.format(first)
            last != null -> format.format(last)
            else -> ""
        }
    }

    /**
     * Eine Kennzahl als Zeile aus `item_route_stat`: Bild links, Beschriftung
     * und hochzählende Zahl in der Mitte. Ein Rangzeichen gibt es hier nicht.
     */
    private fun addStat(
        iconRes: Int,
        labelRes: Int,
        valueText: CharSequence,
        unit: String?,
        caption: String?
    ): ItemRouteStatBinding {
        val row = ItemRouteStatBinding.inflate(
            layoutInflater, binding.routeStatsContainer, false
        )
        row.ivRouteStatIcon.setImageResource(iconRes)
        row.tvRouteStatLabel.setText(labelRes)
        row.tvRouteStatValue.text = valueText
        row.tvRouteStatUnit.text = unit.orEmpty()
        row.tvRouteStatUnit.visibility = if (unit.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.tvRouteStatCaption.text = caption.orEmpty()
        row.tvRouteStatCaption.visibility =
            if (caption.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.badgeRouteStatRank.visibility = View.GONE
        binding.routeStatsContainer.addView(row.root)
        return row
    }

    private fun formatInt(locale: Locale, value: Int): String =
        String.format(locale, "%,d", value)

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        infoGeneration++
        if (_binding != null) {
            photoPageCallback?.let { binding.vpAircraftPhoto.unregisterOnPageChangeCallback(it) }
            binding.routeMap.cancelRouteAnimation()
        }
        photoPageCallback = null
        TileValueAnimator.cancelAll()
        _binding = null
    }

    companion object {
        /** Name der Navigationsargumente, siehe `nav_graph.xml`. */
        const val ARG_MODE = "mode"
        const val ARG_VALUE = "value"

        /** Filter nach Flugzeugtyp. */
        const val MODE_TYPE = "type"

        /** Filter nach Registrierung. */
        const val MODE_REG = "registration"

        /** Datumsformat der Flugliste, wie auf der Layover-Detailseite. */
        val DATE_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        /** Dauer des sanften Einblendens der Fotokachel. */
        private const val PHOTO_FADE_MS = 400L

        /** Durchmesser eines Punkts der Galerie-Anzeige. */
        private const val GALLERY_DOT_DP = 6

        /** Abstand zwischen zwei Punkten der Galerie-Anzeige. */
        private const val GALLERY_DOT_MARGIN_DP = 4
    }
}
