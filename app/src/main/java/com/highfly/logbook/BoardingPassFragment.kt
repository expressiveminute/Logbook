package com.highfly.logbook

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.FragmentBoardingPassBinding

/**
 * Bordkarte zu einem Eintrag: Tippt man auf die Kachel eines Flugeintrags
 * (Seite „Einträge“), öffnet sich diese Seite - ohne Kopfleiste, sie fängt
 * gleich oben an (Statusleiste als Padding). Aufbau siehe
 * `fragment_boarding_pass.xml`: Logo + Flugnummer oben, rechts die
 * Reiseart-Kachel auf mittiger Höhe der Flugnummer (rechts bündig); darunter
 * das Datum (bündig zur Flugnummer, gleiche Schriftgröße fett) mit der
 * Funktionskachel auf seiner mittigen Höhe - Reiseart- und Funktionskachel
 * in derselben Schriftgröße wie Flugnummer und Datum; dann links ein
 * quadratischer QR-Code, dessen rechter weißer Rand in der Bildschirmmitte
 * sitzt, rechts daneben oben die Reiseklasse in der Klassenfarbe mit dem
 * Sitzplatz in einer eigenen Kachel dahinter, darunter
 * die Strecke in einer Reihe groß und fett, dann die Reisebuddies; danach
 * die animierte Streckenkarte, darunter alles in fetter Schrift: Layover,
 * Distanz/Flugzeit, Flugzeug/Registrierung, und der Kommentar in
 * Handschrift. Nicht vorhandene Angaben bleiben weg.
 *
 * Hintergrund der Karte ist die Primärfarbe des aktiven Airline-Schemas -
 * außer bei Lufthansa, dort die Akzentfarbe (Gold) mit bewusst weißer Schrift.
 * Bei den klassischen Akzentfarben ohne Airline-Schema fällt die Karte auf
 * `colorPrimaryContainer` mit `colorOnPrimaryContainer`-Text zurück.
 *
 * Links über der Streckenkarte sitzt ein quadratischer QR-Code
 * ([QrPayload]) in halber Bildschirmbreite: er enthält alle Daten der
 * Karte außer den Reisebuddies (Datenschutz), inklusive Kommentar.
 * Scannt ein anderer Nutzer den Code in der App, landet der Flug
 * vorbefüllt auf der Seite „Neuer Flug“.
 */
class BoardingPassFragment : LogbookFragment() {

    private var _binding: FragmentBoardingPassBinding? = null
    private val binding get() = _binding!!

    private companion object {
        /** Auflösungsquadrat des QR-Bitmaps (das Layout skaliert es herunter). */
        const val QR_PX = 1024

        /** Schriftgrößen-Grenzen der Strecke neben dem QR-Code, in sp. */
        const val ROUTE_MAX_SP = 28f
        const val ROUTE_MIN_SP = 14f
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBoardingPassBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.boardingPassScroll.addBottomNavPadding()

        // Kein Header: oben Platz für die Statusleiste einräumen (gepostet wie
        // addBottomNavPadding, damit der Layout-Request ankommt).
        binding.boardingPassScroll.doOnNextLayout {
            binding.boardingPassScroll.post {
                val top = ViewCompat.getRootWindowInsets(binding.root)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                binding.boardingPassScroll.setPadding(
                    binding.boardingPassScroll.paddingLeft, top,
                    binding.boardingPassScroll.paddingRight, binding.boardingPassScroll.paddingBottom
                )
            }
        }

        // QR-Code quadratisch anzeigen; der rechte Rand des weißen Rahmens
        // soll in der Mitte des Bildschirms sitzen. Die Karte hängt zentriert
        // (16 dp Rand + 20 dp Polster je Seite), deshalb: halbe Inhaltsbreite
        // ab linker Inhaltskante landet die rechte Kante bei screenWidth/2.
        // Der weiße Rand sitzt als Quiet Zone mittig im quadratischen Bitmap.
        val insetPx = ((16 + 20) * resources.displayMetrics.density).toInt()
        val qrSize = (resources.displayMetrics.widthPixels - 2 * insetPx) / 2
        binding.qrFrame.layoutParams = binding.qrFrame.layoutParams.apply {
            width = qrSize
            height = qrSize
        }

        val entryId = requireArguments().getLong("entryId", -1L)
        val entry = if (entryId >= 0) LogbookRepository.getEntry(entryId) else null
        if (entry == null) {
            findNavController().navigateUp()
            return
        }
        bind(entry)

        // Streckenbeschriftung nach dem ersten Layout dynamisch so groß wie
        // möglich in die Spalte neben dem QR-Code einpassen (je nach
        // Bildschirmbreite).
        binding.qrInfo.doOnNextLayout {
            binding.qrInfo.post { fitRouteTextSize() }
        }
    }

    /**
     * Setzt die Schriftgröße von „FROM - TO“ so, dass der Text in einer
     * Zeile in die Spalte rechts neben dem QR-Code passt: Maßstab linear
     * aus der gemessenen Breite bei Maximalgröße, gedeckelt auf
     * [ROUTE_MAX_SP]..[ROUTE_MIN_SP].
     */
    private fun fitRouteTextSize() {
        val route = binding.tvRoute
        if (route.visibility != View.VISIBLE || binding.qrInfo.width <= 0) return
        val text = route.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val density = resources.displayMetrics.scaledDensity
        val maxPx = ROUTE_MAX_SP * density
        val minPx = ROUTE_MIN_SP * density
        val paint = route.paint
        paint.textSize = maxPx
        val needed = paint.measureText(text)
        if (needed <= 0f) return
        val size = (maxPx * binding.qrInfo.width / needed).coerceIn(minPx, maxPx)
        route.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    override fun onDestroyView() {
        binding.routeMap.cancelRouteAnimation()
        binding.layoverClock.stop()
        super.onDestroyView()
        _binding = null
    }

    private fun bind(entry: LogbookEntry) {
        val context = requireContext()

        // Hintergrund und Textfarbe der Bordkarte.
        val scheme = AirlineSchemes.byKey(Settings.getAccentColor(context))
        val backgroundColor: Int
        val textColor: Int
        if (scheme != null) {
            backgroundColor = ContextCompat.getColor(
                context,
                if (BoardingPassLogic.backgroundKind(scheme.key) == "accent") {
                    scheme.accentRes
                } else {
                    scheme.tileBackgroundRes
                }
            )
            // Bei LH steht die Schrift bewusst weiß auf dem Gold.
            textColor = BoardingPassLogic.textColorFor(scheme?.key, backgroundColor)
        } else {
            backgroundColor = MaterialColors.getColor(
                binding.passCard,
                com.google.android.material.R.attr.colorPrimaryContainer
            )
            textColor = MaterialColors.getColor(
                binding.passCard,
                com.google.android.material.R.attr.colorOnPrimaryContainer
            )
        }
        binding.passCard.setCardBackgroundColor(backgroundColor)
        val secondaryText = Color.argb(0xB3, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        listOf(
            binding.tvFlightNo, binding.tvDate, binding.tvRoute, binding.tvBuddies,
            binding.tvLayoverText, binding.tvDistance, binding.tvFlightTime,
            binding.tvAircraft, binding.tvRegistration, binding.tvComment
        ).forEach { it.setTextColor(textColor) }
        binding.tvLayoverText.setTextColor(secondaryText)

        // Logo + Flugnummer: IATA-Code auflösen, damit "DLH" als "LH" steht.
        val rawAirline = entry.airline?.trim().orEmpty()
        val resolved = rawAirline.takeIf { it.isNotEmpty() }?.let { AirlineCatalog.resolve(context, it) }
        val iata = resolved?.code ?: rawAirline.ifEmpty { null }
        val logo = iata?.let { AirlineCatalog.loadLogo(context, it) }
        if (logo != null) {
            binding.ivAirlineLogo.setImageBitmap(logo)
            binding.ivAirlineLogo.visibility = View.VISIBLE
            binding.logoCircle.visibility = View.VISIBLE
        } else {
            binding.ivAirlineLogo.visibility = View.GONE
            binding.logoCircle.visibility = View.GONE
        }
        val flightNo = listOfNotNull(iata, entry.flightNumber?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ")
        if (flightNo.isNotEmpty()) {
            binding.tvFlightNo.text = flightNo
            binding.tvFlightNo.visibility = View.VISIBLE
        } else {
            binding.tvFlightNo.visibility = View.GONE
        }

        // Reiseklasse als eigene Kachel in der Klassenfarbe.
        val letter = BoardingPassLogic.classLetter(entry.classType)
        val classIndex = BoardingPassLogic.classColorIndex(entry.classType)
        val classColors = ClassColorSchemes.colorsFor(Settings.getClassScheme(context))
        val classColor = classIndex?.let {
            ContextCompat.getColor(context, classColors[it.coerceIn(classColors.indices)])
        }
        if (letter != null && classColor != null) {
            binding.tvClassChip.text = letter
            binding.tvClassChip.setBackgroundColor(classColor)
            binding.tvClassChip.contentDescription = getString(
                R.string.boarding_pass_class_desc, entry.classType.orEmpty()
            )
            binding.tvClassChip.visibility = View.VISIBLE
        } else {
            binding.tvClassChip.visibility = View.GONE
        }

        // Datum, bündig zur Flugnummer (linke Kante wie tv_flight_no).
        binding.tvDate.text = EntryListAdapter.dateFormatter.format(entry.date)

        // Reiseart-Kachel oben rechts, Funktions-Kachel darunter.
        val typeColorRes = BoardingPassLogic.typeColorRes(entry.flightType)
        val typeColor = typeColorRes?.let { ContextCompat.getColor(context, it) }
        if (typeColor != null && !entry.flightType.isNullOrBlank()) {
            binding.tvTypeChip.text = entry.flightType
            binding.tvTypeChip.setBackgroundColor(typeColor)
            binding.tvTypeChip.visibility = View.VISIBLE
        } else {
            binding.tvTypeChip.visibility = View.GONE
        }
        val extraText = BoardingPassLogic.extraChipText(entry.flightType, entry.function)
        if (extraText != null && typeColor != null) {
            binding.tvExtraChip.text = extraText
            binding.tvExtraChip.setBackgroundColor(typeColor)
            binding.tvExtraChip.visibility = View.VISIBLE
        } else {
            binding.tvExtraChip.visibility = View.GONE
        }

        // Sitzplatz als eigene Kachel hinter der Reiseklasse - in der
        // Klassenfarbe, ohne Reiseklasse in der Farbe der Reiseart, sonst
        // in der Sekundärfarbe.
        val seat = entry.seat?.trim()?.takeIf { it.isNotEmpty() }
        if (seat != null) {
            binding.tvSeatChip.text = seat
            binding.tvSeatChip.contentDescription = getString(R.string.boarding_pass_seat_desc, seat)
            val seatBg = classColor ?: typeColor
            if (seatBg != null) {
                binding.tvSeatChip.setBackgroundColor(seatBg)
                binding.tvSeatChip.setTextColor(Color.WHITE)
            } else {
                binding.tvSeatChip.setBackgroundColor(
                    MaterialColors.getColor(
                        binding.passCard,
                        com.google.android.material.R.attr.colorSecondaryContainer
                    )
                )
                binding.tvSeatChip.setTextColor(
                    MaterialColors.getColor(
                        binding.passCard,
                        com.google.android.material.R.attr.colorOnSecondaryContainer
                    )
                )
            }
            binding.tvSeatChip.visibility = View.VISIBLE
        } else {
            binding.tvSeatChip.visibility = View.GONE
        }

        // Strecke rechts neben dem QR-Code, groß und fett.
        val routeCodes = listOf(entry.fromAirport.trim(), entry.toAirport.trim())
            .filter { it.isNotEmpty() }
        if (routeCodes.size == 2) {
            binding.tvRoute.text = routeCodes.joinToString(" - ")
            binding.tvRoute.visibility = View.VISIBLE
        } else {
            binding.tvRoute.visibility = View.GONE
        }

        // Reisebuddies darunter, nur wenn eingetragen (Freitext, durch
        // Komma getrennt).
        val buddies = entry.travelBuddy?.trim()?.takeIf { it.isNotEmpty() }
        if (buddies != null) {
            binding.tvBuddies.text = buddies
            binding.tvBuddies.visibility = View.VISIBLE
        } else {
            binding.tvBuddies.visibility = View.GONE
        }

        // QR-Code zum Teilen des Flugs: alle Daten der Bordkarte außer den
        // Reisebuddies (Datenschutz), inklusive Kommentar. Wird als Bitmap
        // gerendert und sitzt im weißen Quadrat links neben Reiseklasse
        // und Strecke.
        val qrBitmap = QrCode.qrBitmap(QrPayload.encode(entry), QR_PX)
        if (qrBitmap != null) {
            binding.ivQrCode.setImageBitmap(qrBitmap)
            binding.rowQr.visibility = View.VISIBLE
        } else {
            binding.rowQr.visibility = View.GONE
        }

        // Streckenkarte: Flieger zeichnet die Linie, Zielcode erscheint erst
        // kurz vor der Ankunft.
        binding.routeMap.contentDescription = getString(
            R.string.boarding_pass_map_desc, entry.fromAirport, entry.toAirport
        )
        val from = AirportData.location(context, entry.fromAirport)
        val to = AirportData.location(context, entry.toAirport)
        if (from != null && to != null) {
            binding.routeMap.revealDestinationOnArrival = true
            binding.routeMap.setRoute(from, to, entry.fromAirport, entry.toAirport)
            binding.routeMap.playRouteAnimation()
        } else {
            binding.routeMap.visibility = View.GONE
        }

        // Layover: animierte Uhr + Satz, nur mit eingetragener Dauer.
        val layoverHours = entry.layoverHours
        if (entry.layover && layoverHours != null) {
            binding.rowLayover.visibility = View.VISIBLE
            binding.tvLayoverText.text = resources.getQuantityString(
                R.plurals.boarding_pass_layover_hours, layoverHours, entry.toAirport, layoverHours
            )
            binding.layoverClock.setColors(secondaryText, textColor)
            binding.layoverClock.playOnce()
        } else {
            binding.rowLayover.visibility = View.GONE
        }

        // Distanz und Flugzeit.
        val distanceKm = entry.distanceKm
        if (distanceKm != null) {
            binding.tvDistance.text = DashboardStats.kmText(context, distanceKm)
            binding.tvDistance.visibility = View.VISIBLE
        } else {
            binding.tvDistance.visibility = View.GONE
        }
        val flightMinutes = entry.flightMinutes
        if (flightMinutes != null) {
            binding.tvFlightTime.text = BoardingPassLogic.flightTimeText(flightMinutes)
            binding.tvFlightTime.visibility = View.VISIBLE
        } else {
            binding.tvFlightTime.visibility = View.GONE
        }

        // Flugzeugmuster und Registrierung.
        val aircraft = entry.aircraftType?.trim()?.takeIf { it.isNotEmpty() }
        if (aircraft != null) {
            binding.tvAircraft.text = aircraft
            binding.tvAircraft.visibility = View.VISIBLE
        } else {
            binding.tvAircraft.visibility = View.GONE
        }
        val registration = entry.registration?.trim()?.takeIf { it.isNotEmpty() }
        if (registration != null) {
            binding.tvRegistration.text = registration
            binding.tvRegistration.visibility = View.VISIBLE
        } else {
            binding.tvRegistration.visibility = View.GONE
        }

        // Kommentar in Handschrift (Patrick Hand, OFL).
        val comment = entry.comment?.trim()?.takeIf { it.isNotEmpty() }
        if (comment != null) {
            binding.tvComment.text = comment
            binding.tvComment.visibility = View.VISIBLE
        } else {
            binding.tvComment.visibility = View.GONE
        }
    }
}
