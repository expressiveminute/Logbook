package com.highfly.logbook

import android.content.Intent
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import com.highfly.logbook.databinding.FragmentAirlineDetailBinding
import com.highfly.logbook.databinding.ItemAirportFactBinding
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Detailseite zu einer Airline der Kachel "Fluggesellschaften": oben mittig
 * zentriert das Logo (oder der Code gross, wenn keines hinterlegt ist), darunter
 * der Name, darunter die Codes ohne Beschriftung (IATA, ICAO) und das
 * Rufzeichen in Anführungszeichen. Eine insolvente Airline trägt rechts neben
 * dem Logo einen roten "Insolvent"-Stempel. Dann folgt ein Foto eines Fliegers
 * in der Bemalung (Top 250, Wikimedia Commons, offline mitgeliefert) mit
 * Namensnennung darunter - Airlines der Lufthansa Group haben eine Galerie
 * aus drei Fotos, seitlich gewischt; gibt es keines, bleibt die Kachel weg.
 * Darüber
 * hinaus die allgemeinen Stammdaten der Airline aus `AirlineFacts`
 * (Airlinegruppe, Gründung, Besitz, Insolvenz, Sitz, Hubs, Flottengröße,
 * Allianz, Wikipedia-Link) - sie hängen nicht vom Logbuch ab und stehen auch
 * für eine noch nie geflogene Airline. Über der Karte stehen diese Zeilen,
 * darunter die Karte wie bei
 * Reisebuddy mit allen Strecken, die mit der Airline geflogen wurden, und unter
 * der Karte die Kenndaten aus dem eigenen Logbuch (die Zeilen aus
 * item_airport_fact.xml). Ganz unten steht der Quellennachweis der Stammdaten.
 * Ein Tipp auf die Karte öffnet die Weltkarte mit voreingestelltem Filter auf
 * die Airline.
 */
class AirlineDetailFragment : LogbookFragment() {

    private var _binding: FragmentAirlineDetailBinding? = null
    private val binding get() = _binding!!

    /** Kanonischer Schlüssel der Airline: IATA-Code oder Rohtext, gross. */
    private var code: String = ""

    /** Verwirft das Ergebnis eines bereits verlassenen Ladevorgangs. */
    private var loadGeneration = 0

    /** Dasselbe für den Ladevorgang des Fotos. */
    private var photoGeneration = 0

    /** Seitenwechsel-Listener der Fotogalerie, zum Abmelden in onDestroyView. */
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAirlineDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Platz unter der schwebenden Navigationsleiste.
        binding.detailScroll.addBottomNavPadding()

        code = requireArguments().getString(ARG_CODE)?.trim()?.uppercase().orEmpty()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.tvDetailSubtitle.text = getString(
            R.string.tile_detail_period_flight_type,
            PeriodOptions.label(
                requireContext(),
                Settings.getDefaultPeriodKey(requireContext())
            ),
            FlightTypeOptions.label(
                requireContext(),
                Settings.getFlightTypeFilterKey(requireContext())
            )
        )

        // Klick auf die Karte öffnet die Weltkarte mit voreingestelltem Airline-Filter
        binding.airlineMap.setOnClickListener {
            findNavController().navigate(
                R.id.action_airline_detail_to_world_map,
                bundleOf("filterAirline" to code)
            )
        }

        load()
        loadPhoto()
    }

    /**
     * Liest den Katalogeintrag, das Logo und die Flüge der Airline im
     * Hintergrund. Grundlage ist dieselbe Auswahl wie auf der Rangliste:
     * Flugart und Zeitraum des Dashboards.
     */
    private fun load() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val target = code
        if (target.isBlank()) {
            binding.tvAirlineEmpty.visibility = View.VISIBLE
            return
        }

        Thread {
            val info = AirlineCatalog.info(appContext, target)
            val logo = AirlineCatalog.loadLogo(appContext, info?.iata ?: target)
            val facts = AirlineFacts.get(appContext, info?.iata ?: target)
            val entries = DashboardStats.dashboardEntries(appContext)
            val matching = entries.filter { entry ->
                AirlineStats.keyOf(entry.airline) {
                    AirlineCatalog.resolve(appContext, it)
                } == target
            }
            val airline = AirlineStats.build(matching, resolve = {
                AirlineCatalog.resolve(appContext, it)
            }).firstOrNull()
            val routes = matching.mapNotNull { entry ->
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
                bind(info, logo, facts, airline, routes)
            }
        }.start()
    }

    /**
     * Legt das mitgelieferte Foto der Bemalung (Top 250, Wikimedia Commons)
     * als eigene Kachel zwischen die Codes und die Stammdaten, falls es für
     * diese Airline existiert, und blendet sie sanft ein. Airlines der
     * Lufthansa Group haben bis zu drei Fotos; die Kachel zeigt sie als
     * Galerie, seitlich gewischt, mit Punkten unter dem Bild. Unter dem Bild
     * steht die Namensnennung des gerade angezeigten Fotos
     * (`© Autor · Lizenz · Wikimedia Commons`); ein Tipp darauf öffnet dessen
     * Commons-Dateiseite. Alles offline aus `assets/`.
     */
    private fun loadPhoto() {
        val generation = ++photoGeneration
        val appContext = requireContext().applicationContext
        val target = code
        binding.airlineImageCard.visibility = View.GONE
        Thread {
            // Die Fotos sind nach dem IATA-Code benannt; der Zielcode kann
            // auch ein ICAO-Code oder Rohtext sein.
            val iata = AirlineCatalog.info(appContext, target)?.iata
                ?: target
            val photos = AirlinePhotos.load(appContext, iata)
            view?.post {
                if (generation != photoGeneration || _binding == null) return@post
                if (photos.isEmpty()) return@post
                setupPhotoGallery(photos)
            }
        }.start()
    }

    /** Baut die Fotokachel samt Galerie (Pager, Punkte, Credit) auf. */
    private fun setupPhotoGallery(photos: List<AirlinePhoto>) {
        val pager = binding.vpAirlinePhoto
        pager.adapter = AirlinePhotoAdapter(
            photos,
            getString(R.string.airline_photo_desc)
        )
        // Punkte unter dem Bild - nur bei mehr als einem Foto.
        val indicator = binding.galleryIndicator
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
            pageCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    for (i in 0 until indicator.childCount) {
                        indicator.getChildAt(i).background =
                            galleryDotDrawable(i == position)
                    }
                    updatePhotoCredit(photos[position.coerceIn(photos.indices)])
                }
            }
            pager.registerOnPageChangeCallback(pageCallback!!)
        } else {
            indicator.visibility = View.GONE
        }
        updatePhotoCredit(photos[0])
        // Sanfter Übergang statt hartem Erscheinen.
        binding.airlineImageCard.alpha = 0f
        binding.airlineImageCard.visibility = View.VISIBLE
        binding.airlineImageCard.animate()
            .alpha(1f)
            .setDuration(PHOTO_FADE_MS)
            .start()
    }

    /** Namensnennung des gerade angezeigten Fotos samt Link auf die Datei. */
    private fun updatePhotoCredit(photo: AirlinePhoto) {
        val edited = if (photo.modified) {
            " · " + getString(R.string.airline_photo_edited)
        } else {
            ""
        }
        binding.tvAirlinePhotoCredit.text =
            "© ${photo.author} · ${photo.license}$edited · Wikimedia Commons"
        // Wie die Wikipedia-Zeile: unterstrichen und Akzentfarbe, ein
        // Tipp öffnet die Commons-Dateiseite im Standardbrowser.
        binding.tvAirlinePhotoCredit.setTextColor(
            MaterialColors.getColor(
                binding.tvAirlinePhotoCredit,
                com.google.android.material.R.attr.colorPrimary
            )
        )
        binding.tvAirlinePhotoCredit.paintFlags =
            binding.tvAirlinePhotoCredit.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.tvAirlinePhotoCredit.setOnClickListener {
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
    private class AirlinePhotoAdapter(
        private val photos: List<AirlinePhoto>,
        private val description: String
    ) : RecyclerView.Adapter<AirlinePhotoAdapter.PhotoHolder>() {

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

    private fun bind(
        info: AirlineCatalog.Info?,
        logo: android.graphics.Bitmap?,
        facts: AirlineFacts.Facts?,
        airline: AirlineStats.Airline?,
        routes: List<Triple<AirportData.GeoLocation, AirportData.GeoLocation, Pair<String, String>>>
    ) {
        val context = requireContext()
        val name = info?.name?.takeIf { it.isNotBlank() }
            ?: airline?.name
            ?: code

        // Logo mittig zentriert; gibt es keines, steht der Code gross an seiner
        // Stelle - wie der Dreilettercode auf der Flughafen-Detailseite.
        if (logo != null) {
            binding.ivAirlineLogo.setImageBitmap(logo)
            if (isDarkMode()) {
                binding.ivAirlineLogo.colorFilter = darkLogoFilter()
            }
            binding.ivAirlineLogo.visibility = View.VISIBLE
            binding.tvAirlineCode.visibility = View.GONE
            binding.tvAirlineName.visibility = View.VISIBLE
        } else {
            binding.tvAirlineCode.text = code
            binding.tvAirlineCode.visibility = View.VISIBLE
            binding.ivAirlineLogo.visibility = View.GONE
            // Ohne Logo steht der Code schon gross oben; der Name lohnt nur,
            // wenn er sich davon unterscheidet.
            binding.tvAirlineName.visibility =
                if (name != code) View.VISIBLE else View.GONE
        }
        binding.tvAirlineName.text = name

        // Insolvenz-Stempel rechts neben dem Logo (auch beim grossen Code).
        binding.tvAirlineInsolvent.visibility =
            if (facts?.dissolved != null) View.VISIBLE else View.GONE

        // Codes unter dem Namen, ohne Beschriftung: IATA und ICAO, dahinter
        // das Rufzeichen in geraden Anführungszeichen ("LH · DLH ·
        // "Lufthansa"").
        val codeParts = mutableListOf<String>()
        info?.iata?.takeIf { it.isNotBlank() }?.let { codeParts.add(it) }
        info?.icao?.takeIf { it.isNotBlank() && it != info.iata }?.let { codeParts.add(it) }
        AirlineFacts.displayCallsign(facts?.callsign)?.let { codeParts.add("\"$it\"") }
        if (codeParts.isNotEmpty()) {
            binding.tvAirlineCodes.text = codeParts.joinToString(" · ")
            binding.tvAirlineCodes.visibility = View.VISIBLE
        } else {
            binding.tvAirlineCodes.visibility = View.GONE
        }

        renderGeneralFacts(facts)
        renderMap(airline, routes)
        renderUserFacts(airline)

        // Nie geflogen (über die Suche aus dem Katalog geöffnet): Hinweis statt
        // Karte und Zahlen. Die allgemeinen Stammdaten bleiben sichtbar.
        binding.tvAirlineEmpty.visibility =
            if (airline == null) View.VISIBLE else View.GONE
    }

    /**
     * Allgemeine Stammdaten über der Karte - unabhängig vom Logbuch:
     * Airlinegruppe, Gründung, Besitzform, Insolvenzjahr, Sitz, Hubs,
     * Flottengröße, Allianz und ein Link zur englischen Wikipedia-Seite aus
     * `AirlineFacts`. Zeilen ohne Wert bleiben weg; ohne jeden Wert bleibt
     * der ganze Abschnitt unsichtbar (der Quellennachweis ganz unten hängt
     * daran).
     */
    private fun renderGeneralFacts(facts: AirlineFacts.Facts?) {
        val context = requireContext()
        binding.generalFactsContainer.removeAllViews()
        if (facts != null) {
            // Die Airlinegruppe steht über der Gründung.
            facts.group?.let {
                addFact(binding.generalFactsContainer, R.string.airline_fact_group, it)
            }
            facts.founded?.let {
                addFact(binding.generalFactsContainer, R.string.airline_fact_founded, it.toString())
            }
            // Der Besitz steht direkt unter der Gründung.
            AirlineFacts.ownershipLabelRes(facts.ownership)?.let { labelRes ->
                addFact(
                    binding.generalFactsContainer,
                    R.string.airline_fact_ownership,
                    getString(labelRes)
                )
            }
            facts.dissolved?.let {
                addFact(binding.generalFactsContainer, R.string.airline_fact_insolvency, it.toString())
            }
            facts.displayHq?.let {
                addFact(binding.generalFactsContainer, R.string.airline_fact_hq, it)
            }
            if (facts.hubs.isNotEmpty()) {
                addFact(
                    binding.generalFactsContainer,
                    R.string.airline_fact_hubs,
                    facts.hubs.joinToString(", ") { hubLabel(it) }
                )
            }
            facts.fleet?.let {
                addFact(
                    binding.generalFactsContainer,
                    R.string.airline_fact_fleet,
                    String.format(Settings.language(context), "%,d", it)
                )
            }
            facts.alliance?.let {
                addFact(binding.generalFactsContainer, R.string.airline_fact_alliance, it)
            }
            facts.wiki?.let { url ->
                addFactLink(
                    binding.generalFactsContainer,
                    R.string.airline_fact_wikipedia,
                    AirlineFacts.wikiTitle(url),
                    url
                )
            }
        }
        val visible = binding.generalFactsContainer.childCount > 0
        binding.tvAirlineGeneralTitle.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvAirlineFactsSource.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Kenndaten aus dem eigenen Logbuch unter der Karte: Flüge, erster und
     * letzter Flug, Ziele, Distanz, Flugzeit und Flugzeugtypen. Zeilen ohne
     * Wert bleiben weg; ohne geflogenen Flug bleibt der Abschnitt samt Titel
     * unsichtbar.
     */
    private fun renderUserFacts(airline: AirlineStats.Airline?) {
        val context = requireContext()
        binding.userFactsContainer.removeAllViews()
        if (airline != null) {
            addFact(
                binding.userFactsContainer,
                R.string.airline_fact_flights,
                String.format(Settings.language(context), "%,d", airline.flights)
            )
            airline.firstFlight?.let {
                addFact(binding.userFactsContainer, R.string.airline_fact_first, DATE_FORMAT.format(it))
            }
            airline.lastFlight?.let {
                addFact(binding.userFactsContainer, R.string.airline_fact_last, DATE_FORMAT.format(it))
            }
            if (airline.destinations > 0) {
                addFact(
                    binding.userFactsContainer,
                    R.string.airline_fact_destinations,
                    String.format(Settings.language(context), "%,d", airline.destinations)
                )
            }
            addFact(
                binding.userFactsContainer,
                R.string.airline_fact_distance,
                DashboardStats.kmText(context, airline.km)
            )
            if (airline.minutes > 0) {
                val time = DashboardStats.timeValue(
                    context, airline.minutes, DashboardStats.TimeUnit.HOURS
                )
                addFact(
                    binding.userFactsContainer,
                    R.string.airline_fact_time,
                    time.text + (time.unit?.let { " $it" }.orEmpty())
                )
            }
            if (airline.aircraftTypes > 0) {
                addFact(
                    binding.userFactsContainer,
                    R.string.airline_fact_types,
                    String.format(Settings.language(context), "%,d", airline.aircraftTypes)
                )
            }
            binding.tvAirlineUserTitle.visibility = View.VISIBLE
        }
    }

    /** Stadtname eines Hub-Flughafens in der App-Sprache, sonst der Code. */
    private fun hubLabel(code: String): String {
        val info = AirportNames.get(requireContext(), code)
        val city = if (Settings.getLanguage(requireContext()) == Settings.LANG_EN) {
            info?.city ?: info?.cityDe
        } else {
            info?.cityDe ?: info?.city
        }
        return city?.takeIf { it.isNotBlank() } ?: code
    }

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
     * Karte wie bei Reisebuddy: alle Strecken der Airline, statisch und ohne
     * Animation - die Aufbau-Animation zeichnete die Karte samt Land und
     * Grenzen jedes Frame neu, was das Scrollen der Seite spürbar ruckeln
     * liess. Unter der Karte steht, wie viele Flüge es auf wie vielen
     * verschiedenen Strecken waren. Ohne Strecke bleibt die Karte samt Titel
     * weg.
     */
    private fun renderMap(
        airline: AirlineStats.Airline?,
        routes: List<Triple<AirportData.GeoLocation, AirportData.GeoLocation, Pair<String, String>>>
    ) {
        if (airline == null || routes.isEmpty()) return
        binding.tvAirlineMapTitle.visibility = View.VISIBLE
        binding.airlineMapCard.visibility = View.VISIBLE
        binding.airlineMap.setRoutes(routes)
        val distinctRoutes = routes.map { it.third }.distinct().size
        binding.tvAirlineMapStats.text = getString(
            R.string.airline_map_stats, airline.flights, distinctRoutes
        )
        binding.tvAirlineMapStats.visibility = View.VISIBLE
        binding.airlineMap.contentDescription = getString(R.string.airline_map_desc)
    }

    /** Die Logos sind für helle Hintergründe gezeichnet; im Dark Mode aufhellen. */
    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun darkLogoFilter(): ColorMatrixColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                1.5f, 0f, 0f, 0f, 45f,
                0f, 1.5f, 0f, 0f, 45f,
                0f, 0f, 1.5f, 0f, 45f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        photoGeneration++
        if (_binding != null) {
            pageCallback?.let { binding.vpAirlinePhoto.unregisterOnPageChangeCallback(it) }
        }
        pageCallback = null
        _binding = null
    }

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_CODE = "code"

        /** Dauer der Einblendung des Fotos in Millisekunden. */
        private const val PHOTO_FADE_MS = 400L

        /** Durchmesser eines Punkts der Galerie-Anzeige. */
        private const val GALLERY_DOT_DP = 6

        /** Abstand zwischen zwei Punkten der Galerie-Anzeige. */
        private const val GALLERY_DOT_MARGIN_DP = 4

        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
