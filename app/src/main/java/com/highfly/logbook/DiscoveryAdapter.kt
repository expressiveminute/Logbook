package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.highfly.logbook.databinding.ItemDiscoveryMonthBinding
import com.highfly.logbook.databinding.ItemDiscoveryRouteBinding
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * RecyclerView-Adapter der Discovery-Seite. Die ViewHolder werden recycelt,
 * damit nur die sichtbaren Zeilen existieren - die Liste scrollt so auch mit
 * vielen Einträgen flüssig, statt alle Kacheln des Jahres auf einmal im
 * Hauptthread aufzubauen.
 *
 * Die Liste ist nach Monaten gruppiert: Vor der ersten Kachel eines Monats
 * steht eine Monatsüberschrift, alle Kacheln desselben Monats folgen direkt
 * darunter.
 */
class DiscoveryAdapter(
    private val context: Context
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Eine Zeile der Liste: Monatsüberschrift oder Streckenkachel. */
    private sealed interface Row {
        data class MonthHeader(val month: YearMonth) : Row
        data class Route(val route: RouteDiscovery) : Row
    }

    private var rows: List<Row> = emptyList()

    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Logos sind oft dunkel; im Dunkelmodus werden sie aufgehellt. */
    private val darkLogoFilter by lazy {
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.5f, 0f, 0f, 0f, 45f,
                    0f, 1.5f, 0f, 0f, 45f,
                    0f, 0f, 1.5f, 0f, 45f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }

    /**
     * Übernimmt die Monate der Reihe nach: vor jeder Gruppe eine Überschrift,
     * darunter ihre Kacheln. Die Monate kommen bereits absteigend, deshalb muss
     * hier nichts sortiert werden.
     */
    fun submit(months: List<DiscoveryMonth>) {
        val flat = ArrayList<Row>(months.size * 8)
        for (month in months) {
            flat += Row.MonthHeader(month.month)
            for (route in month.routes) {
                flat += Row.Route(route)
            }
        }
        rows = flat
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.MonthHeader -> TYPE_MONTH
        is Row.Route -> TYPE_ROUTE
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_MONTH) {
            MonthViewHolder(ItemDiscoveryMonthBinding.inflate(inflater, parent, false))
        } else {
            RouteViewHolder(ItemDiscoveryRouteBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.MonthHeader -> (holder as MonthViewHolder).bind(row.month)
            is Row.Route -> (holder as RouteViewHolder).bind(row.route)
        }
    }

    /** Gruppenüberschrift, rechtsbündig über der ersten Kachel des Monats. */
    inner class MonthViewHolder(
        private val binding: ItemDiscoveryMonthBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(month: YearMonth) {
            binding.tvDiscoveryMonth.text = context.getString(
                R.string.discovery_month,
                month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                month.year
            )
        }
    }

    inner class RouteViewHolder(
        private val binding: ItemDiscoveryRouteBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(route: RouteDiscovery) {
            binding.tvDiscoveryDate.text = dateText(route)
            binding.tvDiscoveryAircraft.apply {
                text = aircraftText(route)
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }

            val logo = binding.ivDiscoveryAirline
            val livery = route.airline?.let { AirlineCatalog.loadLogo(context, it) }
            if (livery != null) {
                logo.setImageBitmap(livery)
                logo.colorFilter = if (isDarkMode) darkLogoFilter else null
                logo.visibility = View.VISIBLE
            } else {
                logo.setImageBitmap(null)
                logo.visibility = View.GONE
            }

            // Recycelte Zeilen können noch die Karte ihrer alten Strecke zeigen,
            // deshalb wird die Sichtbarkeit bei jeder Bindung neu gesetzt.
            val map = binding.discoveryMap
            val from = AirportData.location(context, route.from)
            val to = AirportData.location(context, route.to)
            if (from == null || to == null) {
                map.visibility = View.GONE
            } else {
                map.contentDescription = context.getString(
                    R.string.discovery_map_desc, route.from, route.to
                )
                map.setRoute(from, to, route.from, route.to)
                map.visibility = View.VISIBLE
            }
        }

        /**
         * Bei einem Hin- und Rückflug stehen beide Daten in der Zeile, sonst
         * nur das Datum des Erstdurchflugs. Fahren Hin- und Rückflug am selben
         * Tag, wird das Datum nur einmal angezeigt.
         */
        private fun dateText(route: RouteDiscovery): String {
            val outbound = route.date.format(dateFormatter)
            val back = route.returnDate?.format(dateFormatter) ?: return outbound
            if (back == outbound) return outbound
            return context.getString(R.string.discovery_date_range, outbound, back)
        }

        /**
         * Flugzeugtyp und Registrierung des Erstdurchflugs, jeweils nur wenn im
         * Eintrag hinterlegt. Ohne Angaben bleibt die Zeile leer.
         */
        private fun aircraftText(route: RouteDiscovery): String {
            val type = route.aircraftType
            val registration = route.registration
            return when {
                !type.isNullOrEmpty() && !registration.isNullOrEmpty() ->
                    context.getString(R.string.discovery_aircraft, type, registration)
                !type.isNullOrEmpty() -> type
                !registration.isNullOrEmpty() -> registration
                else -> ""
            }
        }
    }

    companion object {
        private const val TYPE_MONTH = 0
        private const val TYPE_ROUTE = 1

        val dateFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}