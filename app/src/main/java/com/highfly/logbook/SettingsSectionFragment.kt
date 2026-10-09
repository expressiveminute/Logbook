package com.highfly.logbook

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import androidx.fragment.app.Fragment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentSettingsSectionBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.min

class SettingsSectionFragment : Fragment() {

    private companion object {
        /** Schriftgrösse der Kachelbeschriftung, siehe [fitTileLabels]. */
        const val TILE_TEXT_MIN_SP = 8
        const val TILE_TEXT_MAX_SP = 12
    }

    private var _binding: FragmentSettingsSectionBinding? = null

    private val binding get() = _binding!!

    private var sectionKey: String = "profile"

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val csv = LogbookCsv.toCsv(LogbookRepository.getEntries())
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), R.string.export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.export_error, Toast.LENGTH_SHORT).show()
        }
    }

    private val crashLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val content = CrashLogger.currentContent(requireContext())
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), R.string.crash_log_success, Toast.LENGTH_SHORT).show()
            showCrashLogContent(content)
        } catch (e: Exception) {
            CrashLogger.logError("CrashLogExport", "Export fehlgeschlagen", e)
            Toast.makeText(requireContext(), R.string.crash_log_error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsSectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sectionKey = requireArguments().getString("sectionKey") ?: "profile"

        binding.btnSettingsSectionBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.tvSettingsSectionTitle.text = requireContext().getString(sectionTitleRes(sectionKey))

        when (sectionKey) {
            "profile" -> {
                binding.contentProfile.visibility = View.VISIBLE
                setupProfile()
            }
            "customization" -> {
                binding.contentThemes.visibility = View.VISIBLE
                binding.contentCustomization.visibility = View.VISIBLE
                setupThemes()
                setupCustomization()
            }
            "data" -> {
                binding.contentData.visibility = View.VISIBLE
                setupData()
            }
            "development" -> {
                binding.contentDevelopment.visibility = View.VISIBLE
                setupDevelopment()
            }
        }

        fitTileLabels()
    }

    /**
     * Alle Kacheln einer Zeile bekommen dieselbe Schriftgrösse.
     *
     * Die Kacheln einer Zeile sind gleich breit, ihre Beschriftungen aber
     * unterschiedlich lang: Ohne diese Angleichung schrumpft nur die längste
     * Beschriftung - "Flugbegleiter" auf 11sp, während "Purser I" 12sp behält -
     * und die Zeile wirkt unruhig. Die Angleichung braucht die gemessene
     * Breite der Kacheln und läuft deshalb nach dem ersten Layout.
     */
    private fun fitTileLabels() {
        binding.root.doOnLayout {
            fitTileLabels(
                binding.roleCrewLabel,
                binding.rolePassengerLabel
            )
            fitTileLabels(
                binding.crewFunctionPurserIiLabel,
                binding.crewFunctionPurserILabel,
                binding.crewFunctionFlightAttendantLabel
            )
            fitTileLabels(
                binding.preferredClassEconomyLabel,
                binding.preferredClassPremiumEconomyLabel,
                binding.preferredClassBusinessLabel,
                binding.preferredClassFirstLabel
            )
            fitTileLabels(
                binding.darkModeLabel,
                binding.lightModeLabel,
                binding.systemModeLabel
            )
        }
    }

    private fun fitTileLabels(vararg labels: TextView) {
        val density = resources.displayMetrics.scaledDensity
        var needed = Float.MAX_VALUE
        labels.forEach { label ->
            val available = label.width - label.paddingLeft - label.paddingRight - dp(2)
            val measured = label.paint.measureText(label.text, 0, label.text.length)
            if (available <= 0 || measured <= 0f) return@forEach
            needed = min(needed, label.textSize * available / measured)
        }
        if (needed == Float.MAX_VALUE) return
        val sizeSp = floor(needed / density).toInt()
            .coerceIn(TILE_TEXT_MIN_SP, TILE_TEXT_MAX_SP)
        labels.forEach { label ->
            label.setAutoSizeTextTypeUniformWithConfiguration(
                TILE_TEXT_MIN_SP, sizeSp, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    private fun sectionTitleRes(key: String): Int = when (key) {
        "profile" -> R.string.settings_section_profile
        "customization" -> R.string.settings_section_customization
        "data" -> R.string.settings_section_data
        else -> R.string.settings_section_development
    }

    private fun setupProfile() {
        binding.roleCrewTile.setOnClickListener { confirmRoleSwitch(Role.CREW, binding.roleCrewLabel) }
        binding.rolePassengerTile.setOnClickListener {
            confirmRoleSwitch(Role.PASSENGER, binding.rolePassengerLabel)
        }

        binding.crewFunctionPurserIiTile.setOnClickListener {
            applyCrewFunction(Settings.CREW_FUNCTION_PURSER_II)
        }
        binding.crewFunctionPurserITile.setOnClickListener {
            applyCrewFunction(Settings.CREW_FUNCTION_PURSER_I)
        }
        binding.crewFunctionFlightAttendantTile.setOnClickListener {
            applyCrewFunction(Settings.CREW_FUNCTION_FLIGHT_ATTENDANT)
        }

        binding.preferredClassEconomyTile.setOnClickListener { applyPreferredClass(0) }
        binding.preferredClassPremiumEconomyTile.setOnClickListener { applyPreferredClass(1) }
        binding.preferredClassBusinessTile.setOnClickListener { applyPreferredClass(2) }
        binding.preferredClassFirstTile.setOnClickListener { applyPreferredClass(3) }

        binding.profileAirlineInput.setText(Settings.getAirline(requireContext()))
        binding.profileAirlineInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                val upper = text.uppercase()
                if (upper != text) s?.replace(0, s.length, upper)
                Settings.setAirline(requireContext(), upper)
            }
        })

        updateRoleTiles(Settings.getRole(requireContext()))
        updateCrewFunctionTiles(Settings.getCrewFunction(requireContext()))
        updatePreferredClassTiles(Settings.getPreferredClassIndex(requireContext()))
    }

    private fun setupThemes() {
        binding.darkModeTile.setOnClickListener { applyThemeMode(Settings.THEME_MODE_DARK) }
        binding.lightModeTile.setOnClickListener { applyThemeMode(Settings.THEME_MODE_LIGHT) }
        binding.systemModeTile.setOnClickListener { applyThemeMode(Settings.THEME_MODE_SYSTEM) }

        binding.accentBrownTile.setOnClickListener { applyAccent(Settings.ACCENT_BROWN) }
        binding.accentMagentaTile.setOnClickListener { applyAccent(Settings.ACCENT_MAGENTA) }
        binding.accentTurquoiseTile.setOnClickListener { applyAccent(Settings.ACCENT_TURQUOISE) }

        updateModeTiles(Settings.getThemeMode(requireContext()))
        updateAccentTiles(Settings.getAccentColor(requireContext()))
    }

    private fun setupCustomization() {
        updateLanguageTiles()
        setupDefaultPeriodDropdown()
        setupDefaultTimeUnitDropdown()
        setupCompactText()
    }

    /**
     * Die Schriftgrösse wird in [com.highfly.logbook.MainActivity.attachBaseContext]
     * gesetzt, deshalb ist nach dem Umschalten ein Neustart der Activity nötig.
     */
    private fun setupCompactText() {
        binding.compactTextSwitch.isChecked = Settings.isCompactText(requireContext())
        binding.rowCompactText.setOnClickListener { binding.compactTextSwitch.toggle() }
        binding.compactTextSwitch.setOnCheckedChangeListener { _, checked ->
            if (Settings.isCompactText(requireContext()) == checked) return@setOnCheckedChangeListener
            Settings.setCompactText(requireContext(), checked)
            requireActivity().recreate()
        }
    }

    private fun setupData() {
        binding.btnExport.setOnClickListener { confirmExport() }
        binding.btnImport.setOnClickListener {
            findNavController().navigate(R.id.action_settings_section_to_import)
        }
    }

    private fun setupDevelopment() {
        binding.btnCrashLog.setOnClickListener { confirmCrashLog() }
        binding.demoDataSwitch.isChecked = Settings.isDemoDataEnabled(requireContext())
        binding.rowDemoData.setOnClickListener { binding.demoDataSwitch.toggle() }
        binding.demoDataSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setDemoDataEnabled(requireContext(), checked)
            LogbookRepository.invalidate()
            Toast.makeText(
                requireContext(),
                if (checked) R.string.demo_data_enabled else R.string.demo_data_disabled,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmCrashLog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crash_log_title)
            .setMessage(R.string.crash_log_message)
            .setPositiveButton(R.string.crash_log_confirm) { _, _ ->
                crashLogLauncher.launch("crash_log.log")
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    private fun showCrashLogContent(content: String) {
        val textView = TextView(requireContext()).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(24), dp(8), dp(24), dp(8))
            text = content
        }
        val scrollView = android.widget.ScrollView(requireContext()).apply {
            addView(textView)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.development_crash_log)
            .setView(scrollView)
            .setPositiveButton(R.string.crash_log_copy) { _, _ ->
                val clip = android.content.ClipData.newPlainText(
                    "crash_log",
                    content
                )
                requireContext()
                    .getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(clip)
                Toast.makeText(
                    requireContext(),
                    R.string.crash_log_copied,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun confirmExport() {
        val name = Settings.getProfileName(requireContext())
            .trim()
            .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            .ifEmpty { "default" }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm"))
        val filename = "logbook_export_${name}_$timestamp.csv"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_title)
            .setMessage(R.string.export_message)
            .setPositiveButton(R.string.export_confirm) { _, _ ->
                exportLauncher.launch(filename)
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    private fun applyAccent(accent: String) {
        if (Settings.getAccentColor(requireContext()) == accent) return
        Settings.setAccentColor(requireContext(), accent)
        requireActivity().recreate()
    }

    /**
     * Übernimmt das Erscheinungsbild. [Settings.THEME_MODE_SYSTEM] überlässt
     * die Entscheidung dem Handy, gespeichert wird sie trotzdem, damit die
     * Kachel nach dem Neustart der Activity den aktiven Zustand zeigen kann.
     */
    private fun applyThemeMode(mode: String) {
        if (Settings.getThemeMode(requireContext()) == mode) return
        Settings.setThemeMode(requireContext(), mode)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                Settings.THEME_MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                Settings.THEME_MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        updateModeTiles(mode)
    }

    private fun updateModeTiles(mode: String) {
        highlight(binding.darkModeTile, binding.darkModeLabel, mode == Settings.THEME_MODE_DARK)
        highlight(binding.lightModeTile, binding.lightModeLabel, mode == Settings.THEME_MODE_LIGHT)
        highlight(binding.systemModeTile, binding.systemModeLabel, mode == Settings.THEME_MODE_SYSTEM)
    }

    private fun updateLanguageTiles() {
        highlight(binding.langDeTile, binding.langDeLabel, true)
    }

    private fun confirmRoleSwitch(role: Role, label: TextView) {
        if (Settings.getRole(requireContext()) == role) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.role_switch_title)
            .setMessage(getString(R.string.role_switch_message, label.text))
            .setPositiveButton(R.string.role_switch_confirm) { _, _ ->
                Settings.setRole(requireContext(), role)
                updateRoleTiles(role)
            }
            .setNegativeButton(R.string.role_switch_cancel, null)
            .show()
    }

    private fun updateRoleTiles(role: Role) {
        highlight(binding.roleCrewTile, binding.roleCrewLabel, role.isCrew)
        highlight(binding.rolePassengerTile, binding.rolePassengerLabel, role.isPassenger)
        binding.crewFunctionGroup.visibility = if (role.isCrew) View.VISIBLE else View.GONE
    }

    private fun applyCrewFunction(key: String) {
        if (Settings.getCrewFunction(requireContext()) == key) return
        Settings.setCrewFunction(requireContext(), key)
        updateCrewFunctionTiles(key)
    }

    private fun updateCrewFunctionTiles(key: String) {
        highlight(
            binding.crewFunctionPurserIiTile,
            binding.crewFunctionPurserIiLabel,
            key == Settings.CREW_FUNCTION_PURSER_II
        )
        highlight(
            binding.crewFunctionPurserITile,
            binding.crewFunctionPurserILabel,
            key == Settings.CREW_FUNCTION_PURSER_I
        )
        highlight(
            binding.crewFunctionFlightAttendantTile,
            binding.crewFunctionFlightAttendantLabel,
            key == Settings.CREW_FUNCTION_FLIGHT_ATTENDANT
        )
    }

    private fun applyPreferredClass(index: Int) {
        if (Settings.getPreferredClassIndex(requireContext()) == index) return
        Settings.setPreferredClassIndex(requireContext(), index)
        updatePreferredClassTiles(index)
    }

    private fun updatePreferredClassTiles(index: Int) {
        highlightClassTile(binding.preferredClassEconomyTile, binding.preferredClassEconomyLabel, 0, index == 0)
        highlightClassTile(
            binding.preferredClassPremiumEconomyTile,
            binding.preferredClassPremiumEconomyLabel,
            1,
            index == 1
        )
        highlightClassTile(binding.preferredClassBusinessTile, binding.preferredClassBusinessLabel, 2, index == 2)
        highlightClassTile(binding.preferredClassFirstTile, binding.preferredClassFirstLabel, 3, index == 3)
    }

    /**
     * Die Auswahl der bevorzugten Reiseklasse übernimmt die flugspezifische
     * Klassenfarbe (gleich der Reiseklassen-Auswahl unter "Neuer Flug"),
     * inaktive Kacheln verwenden die normale Auswahl-Optik.
     */
    private fun highlightClassTile(card: MaterialCardView, label: TextView, index: Int, active: Boolean) {
        if (active) {
            val classColors = ClassColorSchemes.colorsFor(Settings.getClassScheme(requireContext()))
            val color = ContextCompat.getColor(requireContext(), classColors[index])
            card.setCardBackgroundColor(color)
            card.strokeColor = color
            label.setTextColor(Color.WHITE)
        } else {
            highlight(card, label, active = false)
        }
    }

    private fun updateAccentTiles(accent: String) {
        styleAccentTile(
            binding.accentBrownTile,
            R.color.accent_brown_primary,
            accent == Settings.ACCENT_BROWN
        )
        styleAccentTile(
            binding.accentMagentaTile,
            R.color.accent_magenta_primary,
            accent == Settings.ACCENT_MAGENTA
        )
        styleAccentTile(
            binding.accentTurquoiseTile,
            R.color.accent_turquoise_primary,
            accent == Settings.ACCENT_TURQUOISE
        )
    }

    /**
     * Die Kachel ist eine reine Farbfläche in der Akzentfarbe, ohne
     * Beschriftung. Weil die Fläche bei allen drei Kacheln unterschiedlich ist,
     * zeigt nur noch ein kräftigerer Rahmen den aktiven Zustand an: die
     * Konturfarbe colorOnPrimary hebt sich von der hellen Fläche ab, während
     * inaktive Kacheln nur eine dezente Kontur behalten.
     */
    private fun styleAccentTile(card: MaterialCardView, colorRes: Int, active: Boolean) {
        val context = card.context
        card.setCardBackgroundColor(ContextCompat.getColor(context, colorRes))
        val onPrimary = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnPrimary
        )
        val outlineVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOutlineVariant
        )
        card.strokeColor = if (active) onPrimary else outlineVariant
        card.strokeWidth = if (active) dp(2) else dp(1)
        card.isSelected = active
    }

    private fun highlight(
        card: MaterialCardView,
        label: TextView,
        active: Boolean
    ) {
        val primaryContainer = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimaryContainer
        )
        val onPrimaryContainer = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        val surfaceContainerLow = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorSurfaceContainerLow
        )
        val onSurface = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        val outlineVariant = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOutlineVariant
        )

        card.setCardBackgroundColor(
            if (active) primaryContainer else surfaceContainerLow
        )
        card.strokeColor =
            if (active) primaryContainer else outlineVariant
        label.setTextColor(
            if (active) onPrimaryContainer else onSurface
        )
    }

    private fun setupDefaultPeriodDropdown() {
        val keys = PeriodOptions.keys(requireContext())
        val labels = keys.map { PeriodOptions.label(requireContext(), it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        binding.defaultPeriodDropdown.setAdapter(adapter)
        binding.defaultPeriodDropdown.setText(
            PeriodOptions.label(requireContext(), Settings.getDefaultPeriodKey(requireContext())),
            false
        )
        binding.defaultPeriodDropdown.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position) as String
            val key = PeriodOptions.keyForLabel(requireContext(), label)
            if (key != null) {
                Settings.setDefaultPeriodKey(requireContext(), key)
            }
        }
    }

    private fun setupDefaultTimeUnitDropdown() {
        val keys = TimeUnitOptions.keys()
        val labels = keys.map { TimeUnitOptions.label(requireContext(), it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        binding.defaultTimeUnitDropdown.setAdapter(adapter)
        binding.defaultTimeUnitDropdown.setText(
            TimeUnitOptions.label(requireContext(), Settings.getDefaultTimeUnitKey(requireContext())),
            false
        )
        binding.defaultTimeUnitDropdown.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position) as String
            val key = TimeUnitOptions.keyForLabel(requireContext(), label)
            if (key != null) {
                Settings.setDefaultTimeUnitKey(requireContext(), key)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}