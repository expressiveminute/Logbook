package com.highfly.logbook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.highfly.logbook.databinding.ActivityWelcomeBinding
import com.highfly.logbook.databinding.DialogAvatarPickerBinding
import com.highfly.logbook.databinding.ItemAvatarChoiceBinding
import com.highfly.logbook.databinding.WelcomePageProfileBinding
import java.io.File
import kotlin.math.roundToInt

/**
 * Willkommens-Tour. Sie erscheint beim allerersten Start der App und läßt sich
 * aus den Einstellungen unter "Profil" jederzeit erneut öffnen.
 *
 * Die Tour beginnt mit einer reinen Begrüßungsseite. Darauf folgt die
 * Profilseite, die die wichtigsten Einstellungen Schritt für Schritt abfragt
 * (Name, Icon, Erscheinungsbild, Akzentfarbe, Rolle, Funktion,
 * Standardfluggesellschaft und bevorzugte Arbeitsposition): Der jeweils
 * nächste Schritt erscheint erst nach der Auswahl, damit nie zu viel auf
 * einmal zu sehen ist. Die folgenden Seiten erklären die Grundzüge der App.
 * Alle Werte landen direkt in [Settings], damit die Tour nichts anderes tut
 * als der Einstellungsbildschirm.
 *
 * Aufbau: Der Rahmen steht in [activity_welcome], die Seiten werden zur
 * Laufzeit eingehängt und über die Steuerleiste gewechselt. Die Namen der
 * Verweise folgen den Einstellungen (SettingsTile usw.); die kleinen
 * Hervorhebungshelfer spiegeln bewußt die Logik aus
 * [SettingsSectionFragment], damit die Tour eigenständig bleibt.
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_REPLAY = "welcome_replay"
        private const val STATE_PAGE = "welcome_page"
        private const val STATE_STEP = "welcome_step"
        private const val STATE_CHOSEN = "welcome_chosen"
        private const val AVATAR_ICON_INSET_DP = 22
        private const val AVATAR_GRID_COLUMNS = 4

        private const val PAGE_INTRO = 0
        private const val PAGE_PROFILE = 1

        // Schritte der Profilseite. Sie werden in dieser Reihenfolge nach und
        // nach eingeblendet; Funktions- und Klassen-Schritt nur bei Besatzung.
        private const val STEP_NAME = 0
        private const val STEP_THEME = 1
        private const val STEP_ACCENT = 2
        private const val STEP_ROLE = 3
        private const val STEP_FUNCTION = 4
        private const val STEP_CLASS = 5
        private const val STEP_AIRLINE = 6

        /** Erster Start: nach dem Abschluss wird [MainActivity] frisch gestartet. */
        fun startForFirstRun(context: Context) {
            context.startActivity(Intent(context, WelcomeActivity::class.java))
        }

        /** Erneutes Öffnen aus den Einstellungen; danach geht es dorthin zurück. */
        fun startForReplay(activity: Activity) {
            activity.startActivity(
                Intent(activity, WelcomeActivity::class.java).putExtra(EXTRA_REPLAY, true)
            )
        }
    }

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var pageBinding: WelcomePageProfileBinding

    private var isReplay = false
    private var currentPage = 0

    private val pages = mutableListOf<View>()
    private val pageTitles = mutableListOf<Int>()
    private val dots = mutableListOf<View>()

    // Je Schritt die Views, die zusammen ein- und ausgeblendet werden.
    private var stepViews: List<List<View>> = emptyList()
    private var visibleStep = STEP_NAME
    private var restoredStep = -1

    // Schritte, die der Nutzer in dieser Sitzung angetippt hat. Vorbelegt ist
    // nur das Erscheinungsbild (Dunkel); alle übrigen Kacheln stehen zunächst
    // ungewählt da, damit die Wahl wirklich getroffen wird. Ein Akzent- oder
    // Theme-Wechsel baut die Seite neu auf, deshalb überlebt der Satz den
    // Neubau über [STATE_CHOSEN].
    private val chosenSteps = mutableSetOf(STEP_THEME)

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) saveAvatarFromUri(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        val localized = LocaleUtils.applyLocale(newBase, Settings.LANG_DE)
        super.attachBaseContext(
            LocaleUtils.applyTextScale(localized, Settings.isCompactText(localized))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        isReplay = intent.getBooleanExtra(EXTRA_REPLAY, false)
        // Beim ersten Start ist "Dunkel" die Vorauswahl. Eine bereits bewußt
        // getroffene Wahl (auch aus einer früheren Installation) bleibt bestehen.
        if (!isReplay && !Settings.isThemeModeChosen(this)) {
            Settings.setThemeMode(this, Settings.THEME_MODE_DARK)
        }
        AppCompatDelegate.setDefaultNightMode(nightModeFor(Settings.getThemeMode(this)))
        setTheme(Settings.accentThemeResId(this))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentPage = savedInstanceState?.getInt(STATE_PAGE, 0) ?: 0
        restoredStep = savedInstanceState?.getInt(STATE_STEP, -1) ?: -1
        savedInstanceState?.getIntegerArrayList(STATE_CHOSEN)?.forEach { chosenSteps += it }

        buildPages()
        setupProfilePage()
        setupControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage > 0) showPage(currentPage - 1) else finish()
            }
        })

        showPage(currentPage.coerceIn(0, pages.lastIndex))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
        outState.putInt(STATE_STEP, visibleStep)
        outState.putIntegerArrayList(STATE_CHOSEN, ArrayList(chosenSteps))
    }

    private fun nightModeFor(mode: String): Int = when (mode) {
        Settings.THEME_MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        Settings.THEME_MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    // --- Seitenrahmen -----------------------------------------------------

    private fun buildPages() {
        val container = binding.welcomePageContainer
        val inflater = layoutInflater

        pages += inflater.inflate(R.layout.welcome_page_intro, container, false)
        pageTitles += R.string.welcome_page_intro_title

        pageBinding = WelcomePageProfileBinding.inflate(inflater, container, false)
        pages += pageBinding.root
        pageTitles += R.string.welcome_page_profile_title

        pages += inflater.inflate(R.layout.welcome_page_dashboard, container, false)
        pageTitles += R.string.welcome_page_dashboard_title

        pages += inflater.inflate(R.layout.welcome_page_entries, container, false)
        pageTitles += R.string.welcome_page_entries_title

        pages += inflater.inflate(R.layout.welcome_page_discovery, container, false)
        pageTitles += R.string.welcome_page_discovery_title

        pages.forEach { page ->
            page.visibility = View.GONE
            container.addView(page)
        }
        buildDots()
    }

    private fun buildDots() {
        binding.welcomeDots.removeAllViews()
        dots.clear()
        val size = dp(8)
        val margin = dp(4)
        repeat(pages.size) {
            val dot = View(this)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginStart = margin
            params.marginEnd = margin
            dot.layoutParams = params
            binding.welcomeDots.addView(dot)
            dots += dot
        }
    }

    private fun updateDots() {
        val active = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimary
        )
        val inactive = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOutlineVariant
        )
        dots.forEachIndexed { index, dot ->
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (index == currentPage) active else inactive)
            }
        }
    }

    private fun setupControls() {
        binding.btnWelcomeBack.setOnClickListener {
            if (currentPage > 0) showPage(currentPage - 1)
        }
        binding.btnWelcomeNext.setOnClickListener {
            // Auf der Profilseite zeigt "Weiter" zuerst den nächsten noch
            // verborgenen Schritt. Erst wenn alles sichtbar ist, geht es zur
            // nächsten Seite – so lässt sich kein Schritt versehentlich
            // überspringen.
            if (currentPage == PAGE_PROFILE) {
                if (visibleStep == STEP_NAME && Settings.getProfileName(this).isBlank()) {
                    pageBinding.welcomeNameLayout.error = getString(R.string.welcome_name_required)
                    pageBinding.welcomeNameInput.requestFocus()
                    return@setOnClickListener
                }
                // Die Rolle steht von Anfang an zur Wahl und muss bewusst
                // angeklickt werden; erst dann öffnet "Weiter" den nächsten
                // Schritt. Damit lässt sie sich nicht versehentlich übergehen.
                if (visibleStep == STEP_ROLE && STEP_ROLE !in chosenSteps) {
                    return@setOnClickListener
                }
                if (revealNextStep()) return@setOnClickListener
            }
            if (!validateCurrentPage()) return@setOnClickListener
            if (currentPage < pages.lastIndex) showPage(currentPage + 1) else finishTour()
        }
        binding.btnWelcomeSkip.setOnClickListener { finishTour() }
        // Eigener "Weiter"-Knopf der Begrüßungsseite (dort ist die
        // Steuerleiste des Rahmens ausgeblendet).
        pages.getOrNull(PAGE_INTRO)
            ?.findViewById<com.google.android.material.button.MaterialButton>(R.id.welcome_intro_next)
            ?.setOnClickListener { showPage(PAGE_PROFILE) }
    }

    private fun showPage(index: Int) {
        currentPage = index.coerceIn(0, pages.lastIndex)
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == currentPage) View.VISIBLE else View.GONE
        }
        // Die Begrüßungsseite ist bewusst schlicht: kein Titel, kein
        // Überspringen, keine Punkte und keine Steuerleiste. Damit sitzt das
        // Logo wirklich in der Mitte des Bildschirms; "Weiter" liegt als
        // eigener Knopf unten auf der Seite. Auf den übrigen Seiten bleibt der
        // Rahmen mit Kopf- und Steuerleiste.
        val intro = currentPage == PAGE_INTRO
        binding.welcomeHeader.isVisible = !intro
        binding.welcomeDots.isVisible = !intro
        binding.welcomeNavBar.isVisible = !intro
        // "Überspringen" gibt es weder auf der Begrüßung noch auf der
        // Profilseite; erst ab den Erklärungsseiten ist es wieder da.
        binding.btnWelcomeSkip.isVisible = currentPage != PAGE_INTRO && currentPage != PAGE_PROFILE
        binding.tvWelcomeTitle.setText(pageTitles[currentPage])
        binding.btnWelcomeBack.isEnabled = currentPage > 0
        binding.btnWelcomeNext.setText(
            if (currentPage == pages.lastIndex) R.string.welcome_finish else R.string.welcome_next
        )
        updateDots()
    }

    /** Nur die Profilseite verlangt eine Eingabe: den Namen. */
    private fun validateCurrentPage(): Boolean {
        if (currentPage != PAGE_PROFILE) return true
        if (Settings.getProfileName(this).isBlank()) {
            pageBinding.welcomeNameLayout.error = getString(R.string.welcome_name_required)
            pageBinding.welcomeNameInput.requestFocus()
            return false
        }
        pageBinding.welcomeNameLayout.error = null
        return true
    }

    private fun finishTour() {
        Settings.setWelcomeSeen(this, true)
        if (isReplay) {
            finish()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            finish()
        }
    }

    // --- Profilseite ------------------------------------------------------

    private fun setupProfilePage() {
        val b = pageBinding

        b.welcomeAvatarRow.setOnClickListener { showAvatarDialog() }
        loadAvatarPreview()

        b.welcomeNameInput.setText(Settings.getProfileName(this))
        b.welcomeNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                Settings.setProfileName(this@WelcomeActivity, s?.toString().orEmpty())
                if (!s.isNullOrBlank()) {
                    b.welcomeNameLayout.error = null
                    advanceFrom(STEP_NAME)
                }
            }
        })

        b.welcomeThemeDarkTile.setOnClickListener {
            applyThemeMode(Settings.THEME_MODE_DARK)
            advanceFrom(STEP_THEME)
        }
        b.welcomeThemeLightTile.setOnClickListener {
            applyThemeMode(Settings.THEME_MODE_LIGHT)
            advanceFrom(STEP_THEME)
        }
        b.welcomeThemeSystemTile.setOnClickListener {
            applyThemeMode(Settings.THEME_MODE_SYSTEM)
            advanceFrom(STEP_THEME)
        }

        b.welcomeAccentBrownTile.setOnClickListener {
            chosenSteps += STEP_ACCENT
            applyAccent(Settings.ACCENT_BROWN)
            updateAccentTiles()
            advanceFrom(STEP_ACCENT)
        }
        b.welcomeAccentMagentaTile.setOnClickListener {
            chosenSteps += STEP_ACCENT
            applyAccent(Settings.ACCENT_MAGENTA)
            updateAccentTiles()
            advanceFrom(STEP_ACCENT)
        }
        b.welcomeAccentTurquoiseTile.setOnClickListener {
            chosenSteps += STEP_ACCENT
            applyAccent(Settings.ACCENT_TURQUOISE)
            updateAccentTiles()
            advanceFrom(STEP_ACCENT)
        }

        b.welcomeRoleCrewTile.setOnClickListener {
            chosenSteps += STEP_ROLE
            applyRole(Role.CREW)
            advanceFrom(STEP_ROLE)
        }
        b.welcomeRolePassengerTile.setOnClickListener {
            chosenSteps += STEP_ROLE
            applyRole(Role.PASSENGER)
            advanceFrom(STEP_ROLE)
        }

        b.welcomeFunctionPurserIiTile.setOnClickListener {
            chosenSteps += STEP_FUNCTION
            applyCrewFunction(Settings.CREW_FUNCTION_PURSER_II)
            advanceFrom(STEP_FUNCTION)
        }
        b.welcomeFunctionPurserITile.setOnClickListener {
            chosenSteps += STEP_FUNCTION
            applyCrewFunction(Settings.CREW_FUNCTION_PURSER_I)
            advanceFrom(STEP_FUNCTION)
        }
        b.welcomeFunctionFlightAttendantTile.setOnClickListener {
            chosenSteps += STEP_FUNCTION
            applyCrewFunction(Settings.CREW_FUNCTION_FLIGHT_ATTENDANT)
            advanceFrom(STEP_FUNCTION)
        }

        b.welcomeClassEconomyTile.setOnClickListener {
            chosenSteps += STEP_CLASS
            applyPreferredClass(0)
            advanceFrom(STEP_CLASS)
        }
        b.welcomeClassPremiumEconomyTile.setOnClickListener {
            chosenSteps += STEP_CLASS
            applyPreferredClass(1)
            advanceFrom(STEP_CLASS)
        }
        b.welcomeClassBusinessTile.setOnClickListener {
            chosenSteps += STEP_CLASS
            applyPreferredClass(2)
            advanceFrom(STEP_CLASS)
        }
        b.welcomeClassFirstTile.setOnClickListener {
            chosenSteps += STEP_CLASS
            applyPreferredClass(3)
            advanceFrom(STEP_CLASS)
        }

        b.welcomeAirlineSwitch.isChecked = Settings.getAirline(this).isNotBlank()
        b.welcomeAirlineSwitchRow.setOnClickListener { b.welcomeAirlineSwitch.toggle() }
        b.welcomeAirlineSwitch.setOnCheckedChangeListener { _, checked ->
            if (Settings.getRole(this).isPassenger && !checked) {
                Settings.setAirline(this, "")
                b.welcomeAirlineInput.setText("")
            }
            updateAirlineRow()
        }
        b.welcomeAirlineInput.setText(Settings.getAirline(this))
        b.welcomeAirlineInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                val upper = text.uppercase()
                if (upper != text) s?.replace(0, s.length, upper)
                Settings.setAirline(this@WelcomeActivity, upper)
            }
        })

        // Schritt-Verweise in der Reihenfolge der Einblendung.
        stepViews = listOf(
            listOf(b.welcomeStepName),
            listOf(b.welcomeStepTheme),
            listOf(b.welcomeStepAccent),
            listOf(b.welcomeStepRole),
            listOf(b.welcomeStepFunction),
            listOf(b.welcomeStepClass),
            listOf(b.welcomeStepAirline)
        )

        updateThemeTiles()
        updateAccentTiles()
        updateRoleRow()
        updateCrewFunctionTiles()
        updatePreferredClassTiles()
        updateAirlineRow()

        // Nach dem Wechsel des Erscheinungsbilds wird die Activity neu gebaut;
        // dann den zuletzt erreichten Schritt wiederherstellen. Ist der Name
        // schon gesetzt (erneutes Öffnen aus den Einstellungen), beginnt die
        // Seite gleich mit Erscheinungsbild, Akzentfarbe und Rolle.
        when {
            restoredStep >= 0 -> showStep(restoredStep)
            Settings.getProfileName(this).isNotBlank() -> showStep(STEP_ROLE)
            else -> showStep(STEP_NAME)
        }
    }

    // --- Fortschreitende Einblendung der Profil-Schritte ------------------

    /**
     * Macht alle Schritte bis einschließlich [step] sichtbar. Die beiden
     * Besatzungs-Schritte bleiben ausgeblendet, wenn die Rolle Fluggast ist.
     */
    private fun showStep(step: Int) {
        val target = step.coerceIn(STEP_NAME, STEP_AIRLINE)
        val crew = Settings.getRole(this).isCrew
        stepViews.forEachIndexed { index, views ->
            val show = index <= target && isStepAvailable(index, crew)
            views.forEach { it.isVisible = show }
        }
        visibleStep = if (isStepAvailable(target, crew)) target else STEP_ROLE
        stepViews.getOrNull(target)?.firstOrNull()?.let { animateIn(it) }
        scrollToStep(visibleStep)
    }

    private fun isStepAvailable(step: Int, crew: Boolean): Boolean =
        crew || (step != STEP_FUNCTION && step != STEP_CLASS)

    /**
     * Zeigt den nächsten noch verborgenen Schritt und meldet, ob es einen gab.
     * Damit funktioniert "Weiter" als Rückfallebene, ohne die Auswahl-Logik zu
     * ersetzen: Wer nichts antippt, kommt trotzdem Schritt für Schritt voran.
     */
    private fun revealNextStep(): Boolean {
        val crew = Settings.getRole(this).isCrew
        for (step in visibleStep + 1..STEP_AIRLINE) {
            if (isStepAvailable(step, crew)) {
                showStep(step)
                return true
            }
        }
        return false
    }

    /**
     * Nach der Auswahl im aktuellen Schritt den nächsten einblenden – aber nur
     * dann, wenn der Schritt auch wirklich der zuletzt sichtbare ist. Tippt
     * der Nutzer weiter oben noch einmal, bleibt alles wie es ist.
     */
    private fun advanceFrom(step: Int) {
        if (step != visibleStep) return
        when (step) {
            // Erscheinungsbild, Akzentfarbe und Rolle stehen von Anfang an zur
            // Wahl; erst die bewusst getroffene Rollenwahl gibt den nächsten
            // Schritt frei (die Rolle ist damit der "Türöffner").
            STEP_NAME, STEP_THEME, STEP_ACCENT -> showStep(STEP_ROLE)
            STEP_ROLE -> showStep(if (Settings.getRole(this).isCrew) STEP_FUNCTION else STEP_AIRLINE)
            STEP_FUNCTION -> showStep(STEP_CLASS)
            STEP_CLASS -> showStep(STEP_AIRLINE)
        }
    }

    private fun animateIn(view: View) {
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180).start()
    }

    private fun scrollToStep(step: Int) {
        val target = stepViews.getOrNull(step)?.firstOrNull() ?: return
        pageBinding.welcomeProfileScroll.post {
            pageBinding.welcomeProfileScroll.smoothScrollTo(0, target.top)
        }
    }

    private fun applyThemeMode(mode: String) {
        if (Settings.getThemeMode(this) == mode) return
        Settings.setThemeMode(this, mode)
        AppCompatDelegate.setDefaultNightMode(nightModeFor(mode))
        updateThemeTiles()
    }

    private fun updateThemeTiles() {
        val mode = Settings.getThemeMode(this)
        highlight(pageBinding.welcomeThemeDarkTile, pageBinding.welcomeThemeDarkLabel,
            mode == Settings.THEME_MODE_DARK)
        highlight(pageBinding.welcomeThemeLightTile, pageBinding.welcomeThemeLightLabel,
            mode == Settings.THEME_MODE_LIGHT)
        highlight(pageBinding.welcomeThemeSystemTile, pageBinding.welcomeThemeSystemLabel,
            mode == Settings.THEME_MODE_SYSTEM)
    }

    private fun applyAccent(accent: String) {
        if (Settings.getAccentColor(this) == accent) return
        Settings.setAccentColor(this, accent)
        recreate()
    }

    private fun updateAccentTiles() {
        val accent = Settings.getAccentColor(this)
        val chosen = STEP_ACCENT in chosenSteps
        styleAccentTile(pageBinding.welcomeAccentBrownTile, R.color.accent_brown_primary,
            chosen && accent == Settings.ACCENT_BROWN)
        styleAccentTile(pageBinding.welcomeAccentMagentaTile, R.color.accent_magenta_primary,
            chosen && accent == Settings.ACCENT_MAGENTA)
        styleAccentTile(pageBinding.welcomeAccentTurquoiseTile, R.color.accent_turquoise_primary,
            chosen && accent == Settings.ACCENT_TURQUOISE)
    }

    private fun applyRole(role: Role) {
        Settings.setRole(this, role)
        updateRoleRow()
        updateAirlineRow()
        // Wurde die Rolle geändert, nachdem schon spätere Schritte sichtbar
        // waren, müssen die Besatzungs-Schritte neu bewertet werden.
        if (visibleStep > STEP_ROLE) showStep(visibleStep)
    }

    private fun updateRoleRow() {
        val role = Settings.getRole(this)
        val chosen = STEP_ROLE in chosenSteps
        highlight(pageBinding.welcomeRoleCrewTile, pageBinding.welcomeRoleCrewLabel,
            chosen && role.isCrew)
        highlight(pageBinding.welcomeRolePassengerTile, pageBinding.welcomeRolePassengerLabel,
            chosen && role.isPassenger)
    }

    private fun applyCrewFunction(key: String) {
        if (Settings.getCrewFunction(this) == key) return
        Settings.setCrewFunction(this, key)
        updateCrewFunctionTiles()
    }

    private fun updateCrewFunctionTiles() {
        val key = Settings.getCrewFunction(this)
        val chosen = STEP_FUNCTION in chosenSteps
        highlight(pageBinding.welcomeFunctionPurserIiTile, pageBinding.welcomeFunctionPurserIiLabel,
            chosen && key == Settings.CREW_FUNCTION_PURSER_II)
        highlight(pageBinding.welcomeFunctionPurserITile, pageBinding.welcomeFunctionPurserILabel,
            chosen && key == Settings.CREW_FUNCTION_PURSER_I)
        highlight(pageBinding.welcomeFunctionFlightAttendantTile,
            pageBinding.welcomeFunctionFlightAttendantLabel,
            chosen && key == Settings.CREW_FUNCTION_FLIGHT_ATTENDANT)
    }

    private fun applyPreferredClass(index: Int) {
        if (Settings.getPreferredClassIndex(this) == index) return
        Settings.setPreferredClassIndex(this, index)
        updatePreferredClassTiles()
    }

    private fun updatePreferredClassTiles() {
        val index = Settings.getPreferredClassIndex(this)
        val chosen = STEP_CLASS in chosenSteps
        highlightClassTile(pageBinding.welcomeClassEconomyTile, pageBinding.welcomeClassEconomyLabel,
            0, chosen && index == 0)
        highlightClassTile(pageBinding.welcomeClassPremiumEconomyTile,
            pageBinding.welcomeClassPremiumEconomyLabel, 1, chosen && index == 1)
        highlightClassTile(pageBinding.welcomeClassBusinessTile, pageBinding.welcomeClassBusinessLabel,
            2, chosen && index == 2)
        highlightClassTile(pageBinding.welcomeClassFirstTile, pageBinding.welcomeClassFirstLabel,
            3, chosen && index == 3)
    }

    /**
     * Beschriftung und Sichtbarkeit der Fluggesellschaft richten sich nach der
     * Rolle: Bei der Besatzung ist es der Arbeitgeber und das Feld immer da,
     * beim Fluggast steht der Schalter davor, der das Feld ein- und ausblendet.
     */
    private fun updateAirlineRow() {
        val crew = Settings.getRole(this).isCrew
        pageBinding.welcomeAirlineSwitchRow.isVisible = !crew
        pageBinding.welcomeAirlineTile.setLabelText(
            getString(
                if (crew) R.string.welcome_airline_label_crew
                else R.string.welcome_airline_label_passenger
            )
        )
        pageBinding.welcomeAirlineFieldRow.isVisible = crew || pageBinding.welcomeAirlineSwitch.isChecked
    }

    // --- Avatar -----------------------------------------------------------

    private fun loadAvatarPreview() {
        val icon = pageBinding.welcomeAvatarPreview
        val value = Settings.getAvatar(this)
        if (value == Settings.AVATAR_FILE) {
            val file = File(filesDir, ProfileAvatar.FILE_NAME)
            val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            if (bitmap != null) {
                setIconInset(icon, 0)
                icon.setImageDrawable(
                    RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true }
                )
                icon.imageTintList = null
                return
            }
        }
        setIconInset(icon, AVATAR_ICON_INSET_DP)
        icon.setImageResource(ProfileAvatar.resId(value))
        icon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                pageBinding.root,
                com.google.android.material.R.attr.colorOnPrimaryContainer
            )
        )
    }

    private fun setIconInset(icon: ImageView, insetDp: Int) {
        val inset = dp(insetDp)
        icon.setPadding(inset, inset, inset, inset)
    }

    private fun showAvatarDialog() {
        val dialogBinding = DialogAvatarPickerBinding.inflate(layoutInflater)
        val current = ProfileAvatar.normalize(Settings.getAvatar(this))
        lateinit var dialog: AlertDialog

        ProfileAvatar.presets.chunked(AVATAR_GRID_COLUMNS).forEach { rowPresets ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            rowPresets.forEach { preset ->
                val cell = ItemAvatarChoiceBinding.inflate(layoutInflater, row, false)
                val selected = preset.value == current
                cell.cardAvatarChoice.contentDescription = getString(preset.nameRes)
                cell.cardAvatarChoice.isSelected = selected
                cell.cardAvatarChoice.strokeWidth = dp(if (selected) 2 else 1)
                cell.ivAvatarChoiceCheck.isVisible = selected
                cell.ivAvatarChoice.setImageResource(preset.iconRes)
                cell.cardAvatarChoice.setOnClickListener {
                    Settings.setAvatar(this, preset.value)
                    loadAvatarPreview()
                    dialog.dismiss()
                }
                row.addView(cell.root)
            }
            dialogBinding.llAvatarGrid.addView(row)
        }

        dialogBinding.tvAvatarCurrent.text = getString(
            R.string.profile_avatar_current,
            getString(
                if (current == Settings.AVATAR_FILE) R.string.profile_avatar_own_photo
                else ProfileAvatar.presetNameRes(current)
            )
        )

        dialogBinding.btnAvatarUpload.setOnClickListener {
            dialog.dismiss()
            avatarPickerLauncher.launch("image/*")
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_avatar_title)
            .setView(dialogBinding.root)
            .show()
    }

    private fun saveAvatarFromUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val scaled = Bitmap.createScaledBitmap(decoded, 512, 512, true)
            if (decoded != scaled) decoded.recycle()
            File(filesDir, ProfileAvatar.FILE_NAME).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            scaled.recycle()
            Settings.setAvatar(this, Settings.AVATAR_FILE)
            loadAvatarPreview()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.import_error, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Kachel-Optik (Spiegel von SettingsSectionFragment) ---------------

    private fun highlight(card: MaterialCardView, label: TextView, active: Boolean) {
        val primaryContainer = MaterialColors.getColor(
            pageBinding.root, com.google.android.material.R.attr.colorPrimaryContainer
        )
        val onPrimaryContainer = MaterialColors.getColor(
            pageBinding.root, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        val surfaceContainerLow = MaterialColors.getColor(
            pageBinding.root, com.google.android.material.R.attr.colorSurfaceContainerLow
        )
        val onSurface = MaterialColors.getColor(
            pageBinding.root, com.google.android.material.R.attr.colorOnSurface
        )
        val outlineVariant = MaterialColors.getColor(
            pageBinding.root, com.google.android.material.R.attr.colorOutlineVariant
        )
        card.setCardBackgroundColor(if (active) primaryContainer else surfaceContainerLow)
        card.strokeColor = if (active) primaryContainer else outlineVariant
        label.setTextColor(if (active) onPrimaryContainer else onSurface)
    }

    private fun styleAccentTile(card: MaterialCardView, colorRes: Int, active: Boolean) {
        val context = card.context
        card.setCardBackgroundColor(ContextCompat.getColor(context, colorRes))
        val onPrimary = MaterialColors.getColor(
            pageBinding.root, com.google.android.material.R.attr.colorOnPrimary
        )
        val outlineVariant = MaterialColors.getColor(
            pageBinding.root, com.google.android.material.R.attr.colorOutlineVariant
        )
        card.strokeColor = if (active) onPrimary else outlineVariant
        card.strokeWidth = dp(if (active) 2 else 1)
        card.isSelected = active
    }

    private fun highlightClassTile(
        card: MaterialCardView,
        label: TextView,
        index: Int,
        active: Boolean
    ) {
        if (active) {
            val classColors = ClassColorSchemes.colorsFor(Settings.getClassScheme(this))
            val color = ContextCompat.getColor(this, classColors[index])
            card.setCardBackgroundColor(color)
            card.strokeColor = color
            label.setTextColor(Color.WHITE)
        } else {
            highlight(card, label, active = false)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
