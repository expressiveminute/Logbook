package com.highfly.logbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.highfly.logbook.databinding.DialogBuddyAvatarBinding
import com.highfly.logbook.databinding.DialogBuddyAvatarEditorBinding
import com.highfly.logbook.databinding.DialogBuddyRenameBinding
import com.highfly.logbook.databinding.FragmentFlightRouteDetailBinding
import com.highfly.logbook.databinding.ItemAvatarChoiceBinding
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding
import com.highfly.logbook.databinding.ItemStatTileBinding
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Detailseite eines Reisebuddys: oben die animierte Karte mit allen gemeinsam
 * geflogenen Strecken, darunter zwei Kacheln mit dem ersten und letzten
 * gemeinsamen Flug (Strecke, Datum, Flugnummer) und drei Kacheln mit der Zahl
 * der gemeinsamen Flüge, der gemeinsamen Flugzeit und der gemeinsamen
 * Distanz. Ein Stift in der Kopfzeile benennt den Buddy in allen Einträgen um.
 * Geöffnet wird die Seite durch einen Tipp auf eine Zeile der Reisebuddy-Liste
 * ([TravelBuddyFragment]).
 */
class TravelBuddyDetailFragment : LogbookFragment() {

    private var _binding: FragmentFlightRouteDetailBinding? = null
    private val binding get() = _binding!!

    /** Name des Buddys, so wie er in der Liste steht. */
    private var buddyName: String = ""

    /** Verwirft das Ergebnis eines bereits verlassenen Ladevorgangs. */
    private var loadGeneration = 0

    /** Einheit der Kachel "Zeit zusammen", per Tipp auf die Kachel zyklisch. */
    private var timeUnit = DashboardStats.TimeUnit.HOURS

    /** Gemeinsame Flugzeit in Minuten, für den Klick-Zyklus der Zeit-Kachel. */
    private var buddyMinutes = 0

    /** Kachel "Zeit zusammen", damit der Zyklus ihren Text direkt neu setzt. */
    private var timeTile: ItemStatTileBinding? = null

    /**
     * Fotopicker für den runden Avatar links vom Namen im Kopf: öffnet die
     * Galerie (Fotopicker, braucht keine Berechtigung), das gewählte Bild
     * läuft danach durch den Zuschnitt-Dialog ([showCropDialog]).
     */
    private val pickBuddyPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) startCrop(uri)
    }

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

        buddyName = requireArguments().getString(ARG_NAME).orEmpty()

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.tvRouteTitle.text = buddyName
        binding.tvRouteMapTitle.setText(R.string.buddy_map_title)

        // Runder Avatar links vom Namen im Kopf (dadurch wird die Kopfzeile
        // höher). Ohne Foto zeigt er einen Avatar aus den Presets; ein Tipp
        // öffnet die Auswahl (Presets oder Foto aus der Galerie).
        binding.ivBuddyAvatar.visibility = View.VISIBLE
        binding.ivBuddyAvatar.contentDescription =
            getString(R.string.buddy_photo_change_desc, buddyName)
        binding.ivBuddyAvatar.setOnClickListener { showBuddyAvatarDialog() }
        loadBuddyAvatar()

        // Der Stift rechts im Kopf benennt den Buddy um. Er nimmt den Platz
        // des Platzhalters ein, damit der Titel mittig bleibt.
        binding.headerSpacer.visibility = View.GONE
        binding.btnEdit.visibility = View.VISIBLE
        binding.btnEdit.setOnClickListener { showRenameDialog() }

        // Klick auf die Karte öffnet die Weltkarte mit voreingestelltem Reisebuddy-Filter
        binding.routeMap.setOnClickListener {
            val bundle = bundleOf(
                "filterTravelBuddy" to buddyName
            )
            findNavController().navigate(R.id.action_buddy_detail_to_world_map, bundle)
        }

        if (buddyName.isBlank()) {
            binding.ivBuddyAvatar.visibility = View.GONE
            binding.routeMap.visibility = View.GONE
            binding.routeStatsContainer.visibility = View.GONE
            binding.buddyExtremes.visibility = View.GONE
            binding.buddySummary.visibility = View.GONE
            binding.tvFlightListTitle.visibility = View.GONE
            binding.flightListRoot.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }
        loadStats()
    }

    /**
     * Liest die gemeinsamen Flüge im Hintergrund. Ein gemeinsamer Flug ist ein
     * Eintrag, in dessen Reisebuddy-Feld der Name steht; die Schreibweise
     * spielt keine Rolle, siehe [TravelBuddyStats.parse].
     */
    private fun loadStats() {
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        val target = buddyName
        Thread {
            val entries = DashboardStats.dashboardEntries(appContext)
            val filtered = entries.filter { entry ->
                TravelBuddyStats.parse(entry.travelBuddy).any { it.equals(target, ignoreCase = true) }
            }

            val first = filtered.minByOrNull { it.date }
            val last = filtered.maxByOrNull { it.date }

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
                bind(first, last, routes, filtered)
            }
        }.start()
    }

    private fun bind(
        first: LogbookEntry?,
        last: LogbookEntry?,
        routes: List<Triple<AirportData.GeoLocation, AirportData.GeoLocation, Pair<String, String>>>,
        allEntries: List<LogbookEntry>
    ) {
        if (routes.isNotEmpty()) {
            binding.routeMap.visibility = View.VISIBLE
            binding.routeMap.setRoutes(routes)
            binding.routeMap.playRouteAnimation()
        } else {
            binding.routeMap.visibility = View.GONE
        }

        // Die Kennzahl-Zeilen der Strecken-Detailseite (route_stats_container)
        // bleiben auf dieser Seite ungenutzt: Stattdessen zwei Kacheln mit dem
        // ersten und letzten gemeinsamen Flug und drei Kacheln mit Zahl, Zeit
        // und Distanz der gemeinsamen Flüge.
        binding.routeStatsContainer.visibility = View.GONE
        binding.buddyExtremes.removeAllViews()
        binding.buddySummary.removeAllViews()
        timeTile = null
        if (first != null) {
            binding.buddyExtremes.visibility = View.VISIBLE
            binding.buddySummary.visibility = View.VISIBLE

            addTile(
                binding.buddyExtremes,
                R.string.buddy_first_flight,
                routeOf(first),
                caption(first)
            )
            if (last != null && last.id != first.id) {
                addTile(
                    binding.buddyExtremes,
                    R.string.buddy_last_flight,
                    routeOf(last),
                    caption(last)
                )
            }
            applyTileGaps(binding.buddyExtremes)

            // Wie die Kacheln "Flüge", "Zeit" und "Distanz" des Dashboards: Die
            // Zahl zählt jeden gemeinsamen Flug, Zeit und Distanz dagegen nur
            // echte Flüge (ein Ground Transfer zählt nicht mit).
            buddyMinutes = DashboardStats.flightMinutes(allEntries)
            val distanceKm = DashboardStats.flyingEntries(allEntries)
                .sumOf { it.distanceKm ?: 0 }
            addTile(
                binding.buddySummary,
                R.string.buddy_stat_flights,
                allEntries.size.toString(),
                resources.getQuantityString(
                    R.plurals.buddy_stat_flights_unit, allEntries.size, allEntries.size
                ),
                valueSizeSp = 20f
            )
            // Die Zeit-Kachel ist anklickbar: Ein Tipp schaltet die Einheit
            // weiter (Stunden, Tage, Monate, wieder Stunden).
            timeTile = addTile(
                binding.buddySummary,
                R.string.buddy_stat_time,
                "",
                null,
                valueSizeSp = 20f
            )
            timeTile?.root?.apply {
                isClickable = true
                isFocusable = true
                val ripple = TypedValue()
                requireContext().theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true
                )
                foreground = ContextCompat.getDrawable(requireContext(), ripple.resourceId)
                setOnClickListener { cycleTimeUnit() }
            }
            // Finger-Tap-Hinweis unten rechts: macht klar, dass die Kachel
            // anklickbar ist (Einheit wechseln).
            timeTile?.ivStatTileHint?.visibility = View.VISIBLE
            showTimeTile()
            addTile(
                binding.buddySummary,
                R.string.buddy_stat_distance,
                String.format(Settings.language(requireContext()), "%,d", distanceKm),
                getString(R.string.flight_distance_hint),
                valueSizeSp = 20f
            )
            applyTileGaps(binding.buddySummary)
        } else {
            binding.buddyExtremes.visibility = View.GONE
            binding.buddySummary.visibility = View.GONE
        }

        // Timeline mit allen gemeinsamen Flügen
        renderTimeline(allEntries)

        if (first == null) {
            binding.tvFlightListTitle.visibility = View.GONE
            binding.flightListRoot.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
        } else {
            binding.tvFlightListTitle.visibility = View.VISIBLE
            binding.flightListRoot.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
        }
    }

    /**
     * Eine Kachel mit Beschriftung, grosser Zahl und kleiner Einheit darunter,
     * im Stil der Extrem-Kacheln der Layover-Seite. [valueSizeSp] schrumpft die
     * Zahl bei drei Kacheln nebeneinander, damit längere Werte hineinpassen.
     */
    private fun addTile(
        row: LinearLayout,
        labelRes: Int,
        value: CharSequence,
        caption: String?,
        valueSizeSp: Float = 24f
    ): ItemStatTileBinding {
        val tile = ItemStatTileBinding.inflate(layoutInflater, row, false)
        tile.tvStatTileLabel.setText(labelRes)
        tile.tvStatTileValue.text = value
        tile.tvStatTileValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSizeSp)
        tile.tvStatTileCaption.text = caption.orEmpty()
        tile.tvStatTileCaption.visibility =
            if (caption.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.addView(tile.root)
        return tile
    }

    /** Gleiche 4dp-Lücke zwischen den Kacheln einer Reihe, aussen bündig. */
    private fun applyTileGaps(row: LinearLayout) {
        val gap = (4 * resources.displayMetrics.density).toInt()
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val params = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            params.marginStart = if (i == 0) 0 else gap
            params.marginEnd = if (i == row.childCount - 1) 0 else gap
            child.layoutParams = params
        }
    }

    /** Nächste Einheit der Zeit-Kachel: Stunden, Tage, Monate, wieder Stunden. */
    private fun cycleTimeUnit() {
        timeUnit = when (timeUnit) {
            DashboardStats.TimeUnit.HOURS -> DashboardStats.TimeUnit.DAYS
            DashboardStats.TimeUnit.DAYS -> DashboardStats.TimeUnit.MONTHS
            else -> DashboardStats.TimeUnit.HOURS
        }
        showTimeTile()
    }

    /** Setzt Zahl und Einheit der Zeit-Kachel auf die gewählte Einheit. */
    private fun showTimeTile() {
        val tile = timeTile ?: return
        val value = DashboardStats.timeValue(requireContext(), buddyMinutes, timeUnit)
        tile.tvStatTileValue.text = value.text
        tile.tvStatTileCaption.text = value.unit.orEmpty()
        tile.tvStatTileCaption.visibility =
            if (value.unit.isNullOrEmpty()) View.GONE else View.VISIBLE
        tile.root.contentDescription = getString(
            R.string.buddy_stat_time_desc,
            tile.tvStatTileLabel.text,
            value.text,
            value.unit.orEmpty()
        )
    }

    /**
     * Lädt Foto oder Avatar des Buddys im Hintergrund und setzt den runden
     * Avatar im Kopf. Das Foto hat Vorrang; ohne Foto wird der Avatar gezeigt:
     * die eigene Config aus dem Editor, sonst das Preset (explizite Wahl oder
     * automatischer Zufalls-Avatar, siehe [BuddyAvatars.avatarDrawable]).
     */
    private fun loadBuddyAvatar() {
        val target = buddyName
        val app = requireContext().applicationContext
        Thread {
            val bmp = BuddyPhotos.load(app, target)
            val avatar = if (bmp == null) BuddyAvatars.avatarDrawable(app, target) else null
            view?.post {
                if (_binding == null || buddyName != target) return@post
                if (bmp != null) {
                    binding.ivBuddyAvatar.setPhoto(bmp)
                } else {
                    binding.ivBuddyAvatar.setPreset(avatar)
                }
            }
        }.start()
    }

    /**
     * Dialog zur Avatar-Wahl: oben die Vorschau des Avatars (120 dp), darunter
     * das Auswahlfeld „Avatar anpassen" (öffnet die Editor-Seite,
     * [showBuddyAvatarEditor]), dann die Standard-Avatare
     * ([BuddyAvatars.presets]) und der Galerie-Upload. Alles arbeitet auf einem
     * Entwurf ([AvatarDraft]): „Abbrechen“ verwirft alles, „Übernehmen“
     * speichert (beide Knöpfe gleich breit). Ein hochgeladenes Foto hat auf der
     * Seite Vorrang und bleibt hier unberührt; „Foto entfernen“ löscht es.
     */
    private fun showBuddyAvatarDialog() {
        val context = requireContext()
        val app = context.applicationContext
        val target = buddyName

        // Anfangszustand = gespeicherter Stand: eigene Config > Preset-Wahl/Default.
        val savedConfig = BuddyAvatars.config(app, target)
        val initial: AvatarDraft = savedConfig?.let { AvatarDraft.Custom(it) }
            ?: AvatarDraft.Preset(BuddyAvatars.presetFor(app, target))
        var draft = initial
        // Config des Editors; wird von der Editor-Seite gemeldet.
        var cfgDraft = savedConfig ?: AvatarConfig.random()

        val dialogBinding = DialogBuddyAvatarBinding.inflate(layoutInflater)
        lateinit var dialog: AlertDialog

        val refreshPreview: () -> Unit = {
            when (val d = draft) {
                is AvatarDraft.Preset -> dialogBinding.ivAvatarPreview.setPreset(
                    AppCompatResources.getDrawable(context, d.preset.iconRes)
                )
                is AvatarDraft.Custom -> dialogBinding.ivAvatarPreview.setPreset(
                    BuddyAvatars.buildDrawable(context, d.config)
                )
            }
            dialogBinding.tvAvatarMode.setText(
                when (draft) {
                    is AvatarDraft.Preset -> R.string.buddy_avatar_mode_preset
                    is AvatarDraft.Custom -> R.string.buddy_avatar_mode_custom
                }
            )
        }

        // --- Standard-Avatare ---------------------------------------------------

        val presetCells = mutableMapOf<String, ItemAvatarChoiceBinding>()

        fun refreshGrid() {
            val selectedId = (draft as? AvatarDraft.Preset)?.preset?.id
            presetCells.forEach { (id, cell) ->
                val selected = id == selectedId
                cell.cardAvatarChoice.isSelected = selected
                cell.cardAvatarChoice.strokeWidth = dp(if (selected) 2 else 1)
                cell.ivAvatarChoiceCheck.visibility = if (selected) View.VISIBLE else View.GONE
            }
        }

        fun buildGrid() {
            dialogBinding.llAvatarGrid.removeAllViews()
            presetCells.clear()
            BuddyAvatars.presets.chunked(AVATAR_GRID_COLUMNS).forEach { rowPresets ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                rowPresets.forEach { preset ->
                    val cell = ItemAvatarChoiceBinding.inflate(layoutInflater, row, false)
                    cell.cardAvatarChoice.contentDescription =
                        getString(R.string.buddy_avatar_title)
                    cell.ivAvatarChoice.setImageResource(preset.iconRes)
                    // Preset farbig zeigen, nicht wie beim Profil getintet.
                    cell.ivAvatarChoice.imageTintList = null
                    cell.cardAvatarChoice.setOnClickListener {
                        draft = AvatarDraft.Preset(preset)
                        refreshGrid()
                        refreshPreview()
                    }
                    presetCells[preset.id] = cell
                    row.addView(cell.root)
                }
                dialogBinding.llAvatarGrid.addView(row)
            }
        }

        // --- Auswahlfeld "Avatar anpassen": Editor-Seite öffnen -------------------

        dialogBinding.btnCustomize.setOnClickListener {
            showBuddyAvatarEditor(cfgDraft) { result ->
                if (result != null) {
                    cfgDraft = result
                    draft = AvatarDraft.Custom(result)
                    refreshGrid()
                    refreshPreview()
                }
            }
        }

        // --- Foto-Knöpfe -----------------------------------------------------------

        dialogBinding.btnAvatarUpload.setOnClickListener {
            dialog.dismiss()
            pickBuddyPhoto.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        dialogBinding.btnAvatarRemovePhoto.visibility =
            if (BuddyPhotos.hasPhoto(app, target)) View.VISIBLE else View.GONE
        dialogBinding.btnAvatarRemovePhoto.setOnClickListener {
            Thread {
                BuddyPhotos.delete(app, target)
                view?.post {
                    if (!isAdded) return@post
                    dialogBinding.btnAvatarRemovePhoto.visibility = View.GONE
                }
            }.start()
        }

        // --- Aktionsknöpfe (gleich breit): abbrechen / übernehmen ------------------

        dialogBinding.btnAvatarCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnAvatarApply.setOnClickListener {
            val toSave = draft
            Thread {
                when (toSave) {
                    is AvatarDraft.Preset ->
                        BuddyAvatars.setSelection(app, target, toSave.preset.id)
                    is AvatarDraft.Custom ->
                        BuddyAvatars.saveConfig(app, target, toSave.config)
                }
                view?.post {
                    if (_binding == null) return@post
                    dialog.dismiss()
                    loadBuddyAvatar()
                    Snackbar.make(
                        binding.root, R.string.buddy_avatar_saved, Snackbar.LENGTH_SHORT
                    ).show()
                }
            }.start()
        }

        // --- Aufbau ------------------------------------------------------------------

        buildGrid()
        refreshGrid()
        refreshPreview()

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.buddy_avatar_title)
            .setView(dialogBinding.root)
            .show()
    }

    /**
     * Editor-Seite zum Zusammenstellen eines eigenen Avatars: Vorschau oben,
     * darunter Frisur (10 Stile), Bart, Accessoire (jeweils nach rechts
     * scrollbar) und die Farben Haare/Haut/Shirt. **Jede Änderung malt die
     * Vorschau sofort neu** – die Vorschau wird immer aus der aktuellen Config
     * gebaut, nie aus einem zwischengespeicherten Stand. Knöpfe gleich breit:
     * „Zurücksetzen“ stellt [initial] wieder her, „Abbrechen“ meldet null,
     * „Übernehmen“ meldet die Config.
     */
    private fun showBuddyAvatarEditor(
        initial: AvatarConfig,
        onResult: (AvatarConfig?) -> Unit
    ) {
        val context = requireContext()
        var cfg = initial

        val binding = DialogBuddyAvatarEditorBinding.inflate(layoutInflater)
        lateinit var dialog: AlertDialog

        val refreshPreview: () -> Unit = {
            binding.ivEditorPreview.setPreset(BuddyAvatars.buildDrawable(context, cfg))
        }

        // Wird von den Reglern aufgerufen und baut Leisten + Farben + Vorschau
        // neu auf. Muss var bleiben, weil fillRow/setupColorRow sich über sync
        // gegenseitig aufrufen (lokale Funktionen nicht vorwärts referenzierbar).
        var sync: (() -> Unit)? = null

        // --- Teil-Leisten (Frisur, Bart, Accessoire) -------------------------------

        val fillRow: (
            row: LinearLayout,
            labelRes: Int,
            keys: List<String>,
            selectedKey: String,
            configOf: (String) -> AvatarConfig
        ) -> Unit = { row, labelRes, keys, selectedKey, configOf ->
            row.removeAllViews()
            keys.forEach { key ->
                val icon = ImageView(context).apply {
                    val size = dp(44)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = dp(8)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    setImageDrawable(BuddyAvatars.buildDrawable(context, configOf(key)))
                    contentDescription = getString(labelRes)
                    setOnClickListener {
                        cfg = configOf(key)
                        sync?.invoke()
                    }
                }
                val selected = key == selectedKey
                val strokeColor = MaterialColors.getColor(
                    icon,
                    if (selected) {
                        com.google.android.material.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorOutlineVariant
                    },
                    0
                )
                icon.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(if (selected) 2 else 1), strokeColor)
                    if (selected) {
                        setColor(
                            MaterialColors.getColor(
                                icon,
                                com.google.android.material.R.attr.colorPrimaryContainer,
                                0
                            )
                        )
                    }
                }
                row.addView(icon)
            }
        }

        // --- Farbpaletten (Label links, Slider rechts) ------------------------------
        // Hair Color und Skin Tone mit fester Palette (Farben bleiben erhalten),
        // Shirt Color mit komplettem Farbspektrum. Live-Callback beim Verschieben.

        binding.sliderHairColor.setPalette(AvatarConfig.HAIR_COLORS)
        binding.sliderSkin.setPalette(AvatarConfig.SKIN_COLORS)
        binding.sliderShirt.setSpectrum()
        binding.sliderHairColor.onColorChanged = { c ->
            cfg = cfg.copy(hairColor = c); sync?.invoke()
        }
        binding.sliderSkin.onColorChanged = { c ->
            cfg = cfg.copy(skin = c); sync?.invoke()
        }
        binding.sliderShirt.onColorChanged = { c ->
            cfg = cfg.copy(shirt = c); sync?.invoke()
        }

        sync = {
            fillRow(
                binding.rowHair, R.string.avatar_editor_hair,
                AvatarConfig.HAIR_STYLES, cfg.hair
            ) { key -> cfg.copy(hair = key) }
            fillRow(
                binding.rowBeard, R.string.avatar_editor_beard,
                AvatarConfig.BEARD_STYLES, cfg.beard
            ) { key -> cfg.copy(beard = key) }
            fillRow(
                binding.rowAccessory, R.string.avatar_editor_accessory,
                AvatarConfig.ACCESSORIES, cfg.accessory
            ) { key -> cfg.copy(accessory = key) }
            // Knöpfe nur neu setzen, wenn sich die Farbe von aussen geändert
            // hat (z. B. Zurücksetzen) – beim Ziehen sind Slider-Farbe und cfg
            // gleich, dann darf der Knopf nicht auf die næchste Stützstelle
            // zurückspringen (sonst ruckelt die Bewegung).
            if (binding.sliderHairColor.currentColor != cfg.hairColor) {
                binding.sliderHairColor.setColor(cfg.hairColor)
            }
            if (binding.sliderSkin.currentColor != cfg.skin) {
                binding.sliderSkin.setColor(cfg.skin)
            }
            if (binding.sliderShirt.currentColor != cfg.shirt) {
                binding.sliderShirt.setColor(cfg.shirt)
            }
            refreshPreview()
        }

        // --- Knöpfe (gleich breit) ------------------------------------------------------

        binding.btnEditorReset.setOnClickListener {
            cfg = initial
            sync?.invoke()
        }
        binding.btnEditorCancel.setOnClickListener {
            dialog.dismiss()
            onResult(null)
        }
        binding.btnEditorApply.setOnClickListener {
            dialog.dismiss()
            onResult(cfg)
        }

        sync?.invoke()

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.buddy_customize_avatar)
            .setView(binding.root)
            .show()
    }

    /** Ein Avatar-Entwurf im Auswahl-Dialog, siehe [showBuddyAvatarDialog]. */
    private sealed interface AvatarDraft {
        /** Standard-Avatar aus [BuddyAvatars.presets]. */
        data class Preset(val preset: BuddyAvatars.Preset) : AvatarDraft

        /** Selbst zusammengestellter Avatar aus dem Editor. */
        data class Custom(val config: AvatarConfig) : AvatarDraft
    }

    /**
     * Dekodiert das aus der Galerie gewählte Bild im Hintergrund (begrenzt
     * auf [MAX_DECODE_DIM], damit große Fotos nicht den Speicher sprengen)
     * und öffnet danach den Zuschnitt-Dialog.
     */
    private fun startCrop(uri: Uri) {
        val app = requireContext().applicationContext
        Thread {
            val bmp = decodeSampled(app, uri, MAX_DECODE_DIM)
            view?.post {
                if (_binding == null) return@post
                if (bmp != null) {
                    showCropDialog(bmp)
                } else {
                    Toast.makeText(
                        requireContext(), R.string.buddy_photo_error, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    /** Dekodiert [uri] auf höchstens [maxDim] Kantenlänge heruntergerechnet. */
    private fun decodeSampled(app: android.content.Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim &&
            bounds.outHeight / (sample * 2) >= maxDim
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /**
     * Dialog mit der Zuschnitt-Vorschau: das Foto liegt unter einem festen
     * Kreis, der genau den Ausschnitt zeigt, der als runder Avatar gespeichert
     * wird. Rein-/Rauszoomen und Verschieben ändern den Ausschnitt. "Übernehmen"
     * speichert das Ergebnis ([saveBuddyPhoto]).
     */
    private fun showCropDialog(bitmap: Bitmap) {
        val cropView = BuddyPhotoCropView(requireContext()).apply { setImage(bitmap) }
        val frame = FrameLayout(requireContext()).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(
                cropView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (320 * resources.displayMetrics.density).toInt()
                )
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.buddy_photo_crop_title)
            .setView(frame)
            .setPositiveButton(R.string.buddy_photo_apply) { _, _ ->
                val cropped = cropView.cropBitmap()
                if (cropped != null) {
                    saveBuddyPhoto(cropped)
                } else {
                    Toast.makeText(
                        requireContext(), R.string.buddy_photo_error, Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    /** Speichert den Zuschnitt im Hintergrund und setzt den Avatar neu. */
    private fun saveBuddyPhoto(cropped: Bitmap) {
        val target = buddyName
        val app = requireContext().applicationContext
        Thread {
            // Das Foto liegt als Datei vor und hat Vorrang vor jedem Avatar;
            // eine gespeicherte Avatar-Wahl bleibt dabei unberührt.
            BuddyPhotos.save(app, target, cropped)
            val bmp = BuddyPhotos.load(app, target)
            view?.post {
                if (_binding == null) return@post
                binding.ivBuddyAvatar.setPhoto(bmp)
                Snackbar.make(binding.root, R.string.buddy_photo_saved, Snackbar.LENGTH_SHORT)
                    .show()
            }
        }.start()
    }

    /**
     * Dialog zum Umbenennen des Reisebuddies. Der neue Name wird in jedem
     * Eintrag ersetzt, in dem der Buddy steht - so muss kein Flug einzeln
     * bearbeitet werden. Das Feld sitzt als umrandete Box mit schwebender
     * Beschriftung im Dialog, damit der Titel und der bisherige Name nicht
     * zusammenknautscht wirken.
     */
    private fun showRenameDialog() {
        val dialogBinding = DialogBuddyRenameBinding.inflate(layoutInflater)
        val input = dialogBinding.buddyRenameInput
        input.setText(buddyName)
        input.setSelection(input.text?.length ?: 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.buddy_rename_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.buddy_rename_confirm) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && !newName.equals(buddyName, ignoreCase = true)) {
                    renameBuddy(newName)
                }
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    /** Benennt den Buddy in der Datenbank um und zeichnet die Seite neu. */
    private fun renameBuddy(newName: String) {
        val oldName = buddyName
        val app = requireContext().applicationContext
        Thread {
            LogbookRepository.renameTravelBuddy(oldName, newName)
            // Foto und Avatar-Wahl folgen dem neuen Namen mit.
            BuddyAvatars.rename(app, oldName, newName)
            view?.post {
                if (_binding == null) return@post
                buddyName = newName
                binding.tvRouteTitle.text = newName
                binding.ivBuddyAvatar.contentDescription =
                    getString(R.string.buddy_photo_change_desc, newName)
                loadBuddyAvatar()
                loadStats()
                Snackbar.make(binding.root, R.string.buddy_renamed, Snackbar.LENGTH_SHORT)
                    .show()
            }
        }.start()
    }

    /**
     * Zeitstrahl mit allen gemeinsamen Flügen, ähnlich wie bei Layover.
     * Der jüngste Flug steht oben. Rechts in der Zeile steht der Kommentar
     * des Flugs, falls einer eingetragen ist - dort, wo sonst der Reisebuddy
     * stünde, der auf dieser Seite ja schon der Titel ist.
     */
    private fun renderTimeline(entries: List<LogbookEntry>) {
        binding.flightList.removeAllViews()
        if (entries.isEmpty()) return

        // Sortieren: jüngste zuerst (absteigend nach Datum)
        val sortedEntries = entries.sortedByDescending { it.date }

        sortedEntries.forEachIndexed { index, entry ->
            val row = ItemLayoverTimelineBinding.inflate(
                layoutInflater, binding.flightList, false
            )
            row.root.setOnClickListener {
                findNavController().navigate(
                    R.id.action_buddy_detail_to_add_entry,
                    bundleOf("entryId" to (entry.id ?: -1L))
                )
            }
            row.tvDate.text = DateTimeFormatter.ofPattern("dd.MM.yyyy").format(entry.date)
            row.timelineDot.text = entry.flightMinutes?.let { minutes ->
                if (minutes < 60) "$minutes min" else "${minutes / 60}h"
            }.orEmpty()
            row.tvRoute.text = getString(
                R.string.import_review_summary_route,
                entry.fromAirport.uppercase(),
                entry.toAirport.uppercase()
            )
            val number = listOfNotNull(entry.airline, entry.flightNumber)
                .joinToString(" ")
            if (number.isNotBlank()) {
                row.tvMeta.visibility = View.VISIBLE
                row.tvMeta.text = number
            }
            // Reisebuddy nicht anzeigen, da wir bereits auf der Reisebuddy-Seite sind
            row.ivBuddy.visibility = View.GONE
            row.tvBuddy.visibility = View.GONE
            // Kommentar des Flugs rechts in der Zeile, falls vorhanden
            row.bindTimelineComment(entry.comment, showBuddy = false)

            if (index == sortedEntries.lastIndex) {
                (row.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }
            binding.flightList.addView(row.root)
        }

        binding.flightListRoot.post {
            if (_binding != null) alignTimelineLine()
        }
    }

    /**
     * Die senkrechte Linie des Zeitstrahls verbindet nur die Punkte.
     */
    private fun alignTimelineLine() {
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

    private fun routeOf(entry: LogbookEntry): String =
        entry.fromAirport.uppercase() + " \u2013 " + entry.toAirport.uppercase()

    /** Datum und, wenn vorhanden, Airline und Flugnummer als Zeile unter dem Flug. */
    private fun caption(entry: LogbookEntry): String {
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy").format(entry.date)
        val number = listOfNotNull(
            entry.airline?.takeIf { it.isNotBlank() },
            entry.flightNumber?.takeIf { it.isNotBlank() }
        ).joinToString(" ").takeIf { it.isNotBlank() }
        return if (number != null) "$date \u00b7 $number" else date
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadGeneration++
        if (_binding != null) binding.routeMap.cancelRouteAnimation()
        TileValueAnimator.cancelAll()
        timeTile = null
        _binding = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        /** Name des Navigationsarguments, siehe `nav_graph.xml`. */
        const val ARG_NAME = "name"

        /** Maximale Kantenlänge, auf die ein Galerie-Foto vor dem Zuschnitt dekodiert wird. */
        const val MAX_DECODE_DIM = 2048

        /** Spalten im Avatar-Raster des Auswahl-Dialogs. */
        const val AVATAR_GRID_COLUMNS = 5
    }
}
