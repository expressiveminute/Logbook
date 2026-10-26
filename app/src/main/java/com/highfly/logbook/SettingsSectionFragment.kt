package com.highfly.logbook

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.FragmentSettingsSectionBinding
import com.highfly.logbook.databinding.ItemUserRowBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class SettingsSectionFragment : LogbookFragment() {

    private var _binding: FragmentSettingsSectionBinding? = null

    private val binding get() = _binding!!

    private var sectionKey: String = "profile"

    /** Nutzerplatz, dessen Daten der laufende Export ausliest. */
    private var pendingExportUserId = 1

    /** Zellen des Reglers "Rolle" in Anzeige-Reihenfolge. */
    private val roleLabels = listOf(R.string.role_crew, R.string.role_passenger)

    /** Zellen des Reglers "Funktion": Schlüssel und Beschriftung je Index. */
    private val crewFunctionKeys = listOf(
        Settings.CREW_FUNCTION_PURSER_II,
        Settings.CREW_FUNCTION_PURSER_I,
        Settings.CREW_FUNCTION_FLIGHT_ATTENDANT
    )
    private val crewFunctionLabels = listOf(
        R.string.crew_function_purser_ii,
        R.string.crew_function_purser_i,
        R.string.crew_function_flight_attendant
    )

    /** Zellen des Reglers "Bevorzugte Arbeitsposition". */
    private val classLabels = listOf(
        R.string.class_economy,
        R.string.class_premium_economy_short,
        R.string.class_business,
        R.string.class_first
    )

    /** Zellen des Reglers "Erscheinungsbild" (Reihenfolge wie früher die Kacheln). */
    private val themeModeKeys = listOf(
        Settings.THEME_MODE_DARK,
        Settings.THEME_MODE_LIGHT,
        Settings.THEME_MODE_SYSTEM
    )
    private val themeModeLabels = listOf(
        R.string.settings_dark_mode,
        R.string.settings_light_mode,
        R.string.settings_system_mode
    )

    /** Zellen des Reglers "Akzentfarbe": Schlüssel, Farbe und Name je Index. */
    private val accentKeys = Settings.ACCENT_OPTIONS
    private val accentColors = listOf(
        R.color.accent_brown_primary,
        R.color.accent_magenta_primary,
        R.color.accent_turquoise_primary
    )
    private val accentNames = listOf(
        R.string.settings_accent_brown,
        R.string.settings_accent_magenta,
        R.string.settings_accent_turquoise
    )

    /** Zellen des Reglers "Sprache". */
    private val languageKeys = Settings.LANG_OPTIONS
    private val languageLabels = listOf(R.string.settings_lang_de, R.string.settings_lang_en)

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val app = requireContext().applicationContext
            val csv = LogbookCsv.toCsv(
                LogbookRepository.entriesForUser(app, pendingExportUserId)
            )
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), R.string.export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.export_error, Toast.LENGTH_SHORT).show()
        }
    }

    /** Voller JSON-Export aller App-Daten (Flüge, Einstellungen, Rubbelkarte). */
    private val fullExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = requireContext().applicationContext
        // Vor dem Thread holen: requireActivity() wirft, sobald das Fragment
        // abgelöst ist, und darf nicht aus dem Hintergrund-Thread laufen.
        val activity = activity
        Thread {
            val ok = try {
                val json = AppDataTransfer.exportJson(app)
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                true
            } catch (e: Exception) {
                false
            }
            activity?.runOnUiThread {
                Toast.makeText(
                    app,
                    if (ok) R.string.full_export_success else R.string.full_export_error,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    /** Voller JSON-Import aller App-Daten (überschreibt alles). */
    private val fullImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = requireContext().applicationContext
        val activity = activity
        Thread {
            // null = Lesefehler, false = ungültige Datei, true = Erfolg
            val result: Boolean? = try {
                val content = app.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (content == null) null else AppDataTransfer.importJson(app, content)
            } catch (e: Exception) {
                null
            }
            activity?.runOnUiThread {
                when (result) {
                    true -> {
                        Toast.makeText(app, R.string.full_import_success, Toast.LENGTH_SHORT).show()
                        // Neustart, damit alle Einstellungen sofort greifen
                        activity.recreate()
                    }
                    false -> Toast.makeText(app, R.string.full_import_invalid, Toast.LENGTH_SHORT).show()
                    null -> Toast.makeText(app, R.string.full_import_error, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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

        // Die Seite läuft bis zum unteren Bildschirmrand; unten bleibt
        // scrollbarer Leerraum, damit nichts hinter der schwebenden
        // Navigationsleiste verschwindet.
        binding.settingsScroll.addBottomNavPadding()

        binding.btnSettingsSectionBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.tvSettingsSectionTitle.text = requireContext().getString(sectionTitleRes(sectionKey))

        when (sectionKey) {
            "profile" -> {
                binding.contentProfile.visibility = View.VISIBLE
                setupProfile()
            }
            "users" -> {
                binding.contentUsers.visibility = View.VISIBLE
                setupUsers()
            }
            "customization" -> {
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
    }

    private fun sectionTitleRes(key: String): Int = when (key) {
        "profile" -> R.string.settings_section_profile
        "users" -> R.string.settings_section_users
        "customization" -> R.string.settings_section_customization
        "data" -> R.string.settings_section_data
        else -> R.string.settings_section_development
    }

    private fun setupProfile() {
        val ctx = requireContext()

        binding.roleSelector.setOptions(
            roleLabels.map { SegmentedOptionView.Option(getString(it)) },
            selected = if (Settings.getRole(ctx).isCrew) 0 else 1
        )
        binding.roleSelector.onOptionSelected = { index ->
            confirmRoleSwitch(
                if (index == 0) Role.CREW else Role.PASSENGER,
                roleLabels[index]
            )
        }

        binding.crewFunctionSelector.setOptions(
            crewFunctionLabels.map { SegmentedOptionView.Option(getString(it)) },
            selected = crewFunctionKeys.indexOf(Settings.getCrewFunction(ctx)).coerceAtLeast(0)
        )
        binding.crewFunctionSelector.onOptionSelected = { index ->
            applyCrewFunction(crewFunctionKeys[index])
        }

        setupPreferredClassSelector()

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

        updateRoleSelection(Settings.getRole(requireContext()))

        // Die Willkommens-Tour lässt sich hier erneut öffnen; sie schreibt
        // dieselben Einstellungen wie diese Seite, deshalb beim Zurückkommen
        // neu einlesen.
        binding.btnWelcomeTour.setOnClickListener {
            WelcomeActivity.startForReplay(requireActivity())
        }
    }

    /**
     * Die bevorzugte Arbeitsposition als Regler. Die Markierung der
     * gewählten Klasse übernimmt die flugspezifische Klassenfarbe (wie die
     * Reiseklassen-Auswahl unter "Neuer Flug"), die Beschriftung darauf
     * steht in Weiss.
     */
    private fun setupPreferredClassSelector() {
        val colors = ClassColorSchemes.colorsFor(Settings.getClassScheme(requireContext()))
            .map { ContextCompat.getColor(requireContext(), it) }
        binding.preferredClassSelector.setOptions(
            classLabels.mapIndexed { index, labelRes ->
                SegmentedOptionView.Option(
                    label = getString(labelRes),
                    highlightColor = colors[index],
                    onHighlightColor = Color.WHITE
                )
            },
            selected = Settings.getPreferredClassIndex(requireContext())
        )
        binding.preferredClassSelector.onOptionSelected = { applyPreferredClass(it) }
    }

    private fun setupThemes() {
        val ctx = requireContext()

        binding.themeModeSelector.setOptions(
            themeModeLabels.map { SegmentedOptionView.Option(getString(it)) },
            selected = themeModeKeys.indexOf(Settings.getThemeMode(ctx)).coerceAtLeast(0)
        )
        binding.themeModeSelector.onOptionSelected = { index ->
            applyThemeMode(themeModeKeys[index])
        }

        binding.accentSelector.setOptions(
            accentKeys.mapIndexed { index, _ ->
                SegmentedOptionView.Option(
                    swatchColor = ContextCompat.getColor(ctx, accentColors[index]),
                    contentDescription = getString(accentNames[index])
                )
            },
            selected = accentKeys.indexOf(Settings.getAccentColor(ctx)).coerceAtLeast(0)
        )
        binding.accentSelector.onOptionSelected = { index ->
            applyAccent(accentKeys[index])
        }
    }

    private fun setupCustomization() {
        binding.btnEditDashboard.setOnClickListener {
            findNavController().navigate(R.id.action_settings_section_to_dashboard_edit)
        }
        binding.languageSelector.setOptions(
            languageLabels.map { SegmentedOptionView.Option(getString(it)) },
            selected = languageKeys.indexOf(Settings.getLanguage(requireContext())).coerceAtLeast(0)
        )
        binding.languageSelector.onOptionSelected = { index ->
            applyLanguage(languageKeys[index])
        }
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
        binding.btnFullExport.setOnClickListener { confirmFullExport() }
        binding.btnFullImport.setOnClickListener { confirmFullImport() }
        // Beide Danger-Zone-Aktionen verlangen die Passphrase FLIGHTS; erst
        // die getippte Passphrase gibt den endgültigen Knopf frei.
        binding.btnDeleteUser.setOnClickListener { confirmDeleteUserData() }
        binding.btnFactoryReset.setOnClickListener { confirmFactoryReset() }
    }

    /**
     * Voller JSON-Export: alle Daten der App (Flüge aller Nutzer,
     * Einstellungen, Rubbelkarte, Avatare) werden in eine Datei geschrieben.
     */
    private fun confirmFullExport() {
        val ctx = requireContext()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm"))
        val filename = "logbook_full_export_$timestamp.json"
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.full_export_title)
            .setMessage(R.string.full_export_message)
            .setPositiveButton(R.string.full_export_confirm) { _, _ ->
                fullExportLauncher.launch(filename)
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    /**
     * Voller JSON-Import: alle Daten der App werden aus einer Datei
     * wiederhergestellt, bestehende Daten werden überschrieben.
     */
    private fun confirmFullImport() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.full_import_title)
            .setMessage(R.string.full_import_message)
            .setPositiveButton(R.string.full_import_confirm) { _, _ ->
                fullImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
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

    /**
     * Ab zwei Nutzern zuerst fragen, wessen Logbuch exportiert werden soll;
     * danach dieselbe Abfrage wie bisher. Nur das Logbuch des gewählten
     * Nutzers landet in der CSV-Datei.
     */
    private fun confirmExport() {
        val app = requireContext().applicationContext
        val ids = Users.ids(app)
        if (ids.size > 1) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.export_choose_user_title)
                .setItems(ids.map { Users.label(app, it) }.toTypedArray()) { _, which ->
                    startExport(ids[which])
                }
                .setNegativeButton(R.string.discard_cancel, null)
                .show()
        } else {
            startExport(ids.first())
        }
    }

    private fun startExport(userId: Int) {
        val ctx = requireContext()
        pendingExportUserId = userId
        val name = Settings.getProfileName(ctx, userId)
            .trim()
            .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            .ifEmpty { "default" }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm"))
        val filename = "logbook_export_${name}_$timestamp.csv"
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.export_title)
            .setMessage(R.string.export_message)
            .setPositiveButton(R.string.export_confirm) { _, _ ->
                exportLauncher.launch(filename)
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    // --- Nutzer -----------------------------------------------------------

    private fun setupUsers() {
        binding.btnAddUser.setOnClickListener { addUser() }
        renderUsers()
    }

    /**
     * Zeigt alle angelegten Nutzer als Liste. Ein Tipp auf eine Zeile macht
     * den Nutzer aktiv (Datenbankwechsel + Neustart der Activity), der
     * Entfernen-Knopf löscht den Nutzer mit allen Daten. Platz 1 bleibt
     * immer bestehen und trägt keinen Entfernen-Knopf.
     */
    private fun renderUsers() {
        val ctx = requireContext()
        val container = binding.contentUsersList
        container.removeAllViews()
        val activeId = Users.activeId(ctx)
        Users.ids(ctx).forEach { id ->
            val row = ItemUserRowBinding.inflate(layoutInflater, container, false)
            val name = Settings.getProfileName(ctx, id).trim()
            row.tvUserRowName.text = name.ifBlank { Users.label(ctx, id) }
            loadUserRowAvatar(ctx, row, id)
            row.ivUserRowActive.isVisible = id == activeId
            row.btnUserRowRemove.isVisible = id > 1
            if (id == activeId) {
                // Der aktive Nutzer braucht keinen Tipp-Ziel; die Zeile bleibt
                // lesbar, nur der Entfernen-Knopf funktioniert.
                row.root.isClickable = false
                row.root.isFocusable = false
            } else {
                row.root.setOnClickListener { switchToUser(id) }
            }
            row.btnUserRowRemove.setOnClickListener { confirmRemoveUser(id) }
            container.addView(row.root)
        }
        binding.btnAddUser.isVisible = Users.hasRoom(ctx)
        binding.tvUsersHint.isVisible = Users.count(ctx) > 1
    }

    private fun loadUserRowAvatar(context: Context, row: ItemUserRowBinding, userId: Int) {
        val avatar = row.ivUserRowAvatar
        if (Settings.getAvatar(context, userId) == Settings.AVATAR_FILE) {
            avatar.setPadding(0, 0, 0, 0)
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            avatar.imageTintList = null
        } else {
            avatar.setPadding(dp(10), dp(10), dp(10), dp(10))
            avatar.scaleType = ImageView.ScaleType.FIT_CENTER
            avatar.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer
                )
            )
        }
        avatar.setImageDrawable(ProfileAvatar.load(context, userId))
    }

    private fun switchToUser(userId: Int) {
        val app = requireContext().applicationContext
        Users.setActiveId(app, userId)
        // Schließt die Verbindung des bisherigen Nutzers und öffnet die
        // Datenbank des neuen, damit wirklich dessen Logbuch angezeigt wird.
        LogbookRepository.switchUser(app)
        requireActivity().recreate()
    }

    private fun addUser() {
        val app = requireContext().applicationContext
        if (Users.addUser(app) == null) return
        LogbookRepository.switchUser(app)
        requireActivity().recreate()
    }

    private fun confirmRemoveUser(userId: Int) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.users_remove_title)
            .setMessage(getString(R.string.users_remove_message, Users.label(ctx, userId)))
            .setPositiveButton(R.string.users_remove_confirm) { _, _ ->
                AppReset.removeUser(ctx, userId)
                requireActivity().recreate()
            }
            .setNegativeButton(R.string.discard_cancel, null)
            .show()
    }

    // --- Danger Zone ------------------------------------------------------

    /** Löscht Logbuch und Profil des aktiven Nutzers; der Platz bleibt. */
    private fun confirmDeleteUserData() {
        val ctx = requireContext()
        PassphraseConfirm.show(
            context = ctx,
            titleRes = R.string.danger_delete_user_title,
            message = ctx.getString(
                R.string.danger_delete_user_message,
                Users.label(ctx, Users.activeId(ctx))
            ),
            phrase = PassphraseConfirm.PHRASE_FLIGHTS,
            confirmRes = R.string.danger_delete_user_confirm,
            onConfirmed = {
                AppReset.clearActiveUser(ctx)
                Toast.makeText(ctx, R.string.danger_delete_user_done, Toast.LENGTH_SHORT).show()
                requireActivity().recreate()
            }
        )
    }

    /** Setzt die App auf den Werkszustand zurück und startet sie neu. */
    private fun confirmFactoryReset() {
        val ctx = requireContext()
        PassphraseConfirm.show(
            context = ctx,
            titleRes = R.string.danger_reset_title,
            message = ctx.getString(R.string.danger_reset_message),
            phrase = PassphraseConfirm.PHRASE_FLIGHTS,
            confirmRes = R.string.danger_reset_confirm,
            onConfirmed = {
                AppReset.factoryReset(ctx)
                restartToMain()
            }
        )
    }

    /**
     * Nach dem Werksreset ist die Einstellungsdatei leer, die App startet
     * also mit der Willkommens-Tour. Damit kein halb gezeichneter
     * Einstellungsbildschirm stehenbleibt, ersetzt eine frische MainActivity
     * die gesamte Aufgabengeschichte.
     */
    private fun restartToMain() {
        val intent = Intent(requireContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        requireActivity().finish()
    }

    private fun applyAccent(accent: String) {
        if (Settings.getAccentColor(requireContext()) == accent) return
        Settings.setAccentColor(requireContext(), accent)
        requireActivity().recreate()
    }

    /**
     * Übernimmt das Erscheinungsbild. [Settings.THEME_MODE_SYSTEM] überlässt
     * die Entscheidung dem Handy, gespeichert wird sie trotzdem, damit der
     * Regler nach dem Neustart der Activity den aktiven Zustand zeigen kann.
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
        updateThemeModeSelection(mode)
    }

    private fun updateThemeModeSelection(mode: String) {
        val index = themeModeKeys.indexOf(mode)
        if (index >= 0) binding.themeModeSelector.select(index)
    }

    /**
     * Übernimmt die Sprache der App. [AppCompatDelegate.setApplicationLocales]
     * baut alle Activities neu auf, sodass die neue Sprache sofort überall
     * gilt; die Standardsprache des Prozesses folgt, damit auch Zahlen- und
     * Datumsformate umstellen.
     */
    private fun applyLanguage(language: String) {
        if (Settings.getLanguage(requireContext()) == language) return
        Settings.setLanguage(requireContext(), language)
        Locale.setDefault(if (language == Settings.LANG_EN) Locale.ENGLISH else Locale.GERMANY)
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language)
        )
        updateLanguageSelection(language)
    }

    private fun updateLanguageSelection(language: String) {
        val index = languageKeys.indexOf(language)
        if (index >= 0) binding.languageSelector.select(index)
    }

    private fun confirmRoleSwitch(role: Role, labelRes: Int) {
        if (Settings.getRole(requireContext()) == role) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.role_switch_title)
            .setMessage(getString(R.string.role_switch_message, getString(labelRes)))
            .setPositiveButton(R.string.role_switch_confirm) { _, _ ->
                Settings.setRole(requireContext(), role)
                updateRoleSelection(role)
            }
            .setNegativeButton(R.string.role_switch_cancel, null)
            .show()
    }

    private fun updateRoleSelection(role: Role) {
        binding.roleSelector.select(if (role.isCrew) 0 else 1)
        binding.crewFunctionGroup.visibility = if (role.isCrew) View.VISIBLE else View.GONE
    }

    private fun applyCrewFunction(key: String) {
        if (Settings.getCrewFunction(requireContext()) == key) return
        Settings.setCrewFunction(requireContext(), key)
        updateCrewFunctionSelection(key)
    }

    private fun updateCrewFunctionSelection(key: String) {
        val index = crewFunctionKeys.indexOf(key)
        if (index >= 0) binding.crewFunctionSelector.select(index)
    }

    private fun applyPreferredClass(index: Int) {
        if (Settings.getPreferredClassIndex(requireContext()) == index) return
        Settings.setPreferredClassIndex(requireContext(), index)
        updatePreferredClassSelection(index)
    }

    private fun updatePreferredClassSelection(index: Int) {
        binding.preferredClassSelector.select(index)
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

    /**
     * Nach einer wiederholten Willkommens-Tour stehen die Werte der Profilseite
     * möglicherweise anders; sie werden beim Zurückkommen neu gesetzt. Die
     * Regler und Felder hier tragen dieselben Werte wie die Tour.
     */
    override fun onResume() {
        super.onResume()
        if (sectionKey != "profile" || _binding == null) return
        updateRoleSelection(Settings.getRole(requireContext()))
        updateCrewFunctionSelection(Settings.getCrewFunction(requireContext()))
        updatePreferredClassSelection(Settings.getPreferredClassIndex(requireContext()))
        val airline = Settings.getAirline(requireContext())
        if (binding.profileAirlineInput.text?.toString() != airline) {
            binding.profileAirlineInput.setText(airline)
        }
    }
}