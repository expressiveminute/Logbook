package com.highfly.logbook

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.color.MaterialColors
import com.highfly.logbook.databinding.ActivityMainBinding

/** Dauer eines vollen Puls-Zyklus des Filter-Glow, in Millisekunden. */
private const val FILTER_GLOW_PERIOD_MS = 1500L

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController
    private lateinit var binding: ActivityMainBinding

    private var statusBarTop = 0
    private var navBarLeft = 0
    private var navBarRight = 0
    private var navBarBottom = 0
    private var imeBottom = 0
    private var imeVisible = false

    /** Akzentfarbe und Erscheinungsbild, mit denen diese Instanz gestartet ist. */
    private var appliedAccent: String? = null
    private var appliedThemeMode: String? = null

    /** Laufende Glow-Animationen der beiden Filterwoerter im Dashboard-Kopf. */
    private val filterGlowAnimators = HashMap<TextView, ValueAnimator>()

    override fun attachBaseContext(newBase: Context) {
        val localized = LocaleUtils.applyLocale(newBase, Settings.getLanguage(newBase))
        super.attachBaseContext(
            LocaleUtils.applyTextScale(localized, Settings.isCompactText(localized))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            when (Settings.getThemeMode(this)) {
                Settings.THEME_MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                Settings.THEME_MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        setTheme(Settings.accentThemeResId(this))
        super.onCreate(savedInstanceState)

        // Beim allerersten Start zuerst die Willkommens-Tour zeigen. Sie kehrt
        // danach über einen frischen Start hierher zurück.
        if (!Settings.isWelcomeSeen(this)) {
            WelcomeActivity.startForFirstRun(this)
            finish()
            return
        }

        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            statusBarTop = statusBars.top
            navBarLeft = navigationBars.left
            navBarRight = navigationBars.right
            navBarBottom = navigationBars.bottom
            imeBottom = clampImeBottom(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            // Reihenfolge wichtig: Erst die Sichtbarkeit, dann das Layout. Sonst
            // zeichnet die Navigationsleiste im ersten Frame ueber der bereits
            // offenen Tastatur.
            updateImeVisibility(insets.isVisible(WindowInsetsCompat.Type.ime()))
            updateHeaderAndContent()
            WindowInsetsCompat.CONSUMED
        }
        setupImeAnimation()
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setTitle("")

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_dashboard,
                R.id.nav_entries,
                R.id.nav_discovery,
                R.id.nav_rubbelkarte,
                R.id.nav_profile
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        setupBottomNav(navController)
        setupBackPressHandler()
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateHeaderAndContent()
            if (destination.id == R.id.nav_dashboard) {
                refreshDashboardHeader()
                // Zurueck auf dem Dashboard: Der Glow der aktiven Filter
                // laeuft wieder an (updateHeaderAndContent hat ihn beim
                // Seitenwechsel gestoppt).
                renderDashboardFilter()
            }
        }

        setupDashboardFilter()
        refreshDashboardHeader()

        // Der Story-Knopf in der Dashboard-Kopfzeile öffnet die Story.
        binding.btnDashboardStory.setOnClickListener {
            StoryActivity.start(this, StoryActivity.MODE_PLAY)
        }
    }

    /**
     * Richtet die Dashboard-Kopfzeile an den sichtbaren Kachelrändern aus:
     * [tileLeftPx] und [tileRightPx] sind die Bildschirm-Positionen der
     * Kachelkanten - der Avatar steht links bündig zu ihnen, der Story-Knopf
     * rechts. FirstFragment ruft das nach dem Vermessen des Rasters mit den
     * echten Kanten (die Kacheln schrumpfen um die Skalierung nach innen).
     */
    fun alignDashboardHeader(tileLeftPx: Int, tileRightPx: Int) {
        val header = binding.dashboardHeader
        val pos = IntArray(2)
        header.getLocationOnScreen(pos)
        header.setPadding(
            (tileLeftPx - pos[0]).coerceAtLeast(0),
            header.paddingTop,
            (pos[0] + header.width - tileRightPx).coerceAtLeast(0),
            header.paddingBottom
        )
    }

    /**
     * Leitet die Zurueck-Taste zuerst an das aktuelle Ziel der Navigation
     * weiter (siehe [BackPressAware]): Ist dort ein Suchfeld offen, wird es
     * geschlossen und die Eingabe geloescht - erst ein zweiter Druck kehrt
     * zur vorherigen Seite zurueck. Uebernimmt die Seite den Druck nicht, faellt
     * er an den NavController durch (Zurueck im Stapel bzw. App verlassen).
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this) {
            val host = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
            val current = host?.childFragmentManager?.primaryNavigationFragment
            val consumed = (current as? BackPressAware)?.onBackPressed() == true
            if (!consumed) {
                // Diesen Callback abschalten und den Druck erneut ausloesen,
                // damit der naechste (der NavController) an die Reihe kommt.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    /**
     * Die beiden Filterwoerter ueber den Kacheln: links die Flugart, rechts
     * der Zeitraum. Getippt wird nichts - ein Tipp auf eines der Woerter
     * oeffnet das Menue mit seinen Moeglichkeiten, danach richtet sich die
     * Filterzeile und damit die Kachelsumme unten neu aus.
     */
    private fun setupDashboardFilter() {
        binding.tvFilterFlightType.setOnClickListener { anchor ->
            showFlightTypeMenu(anchor)
        }
        binding.tvFilterPeriod.setOnClickListener { anchor ->
            showPeriodMenu(anchor)
        }
        renderDashboardFilter()
    }

    private fun renderDashboardFilter() {
        val typeKey = Settings.getFlightTypeFilterKey(this)
        val periodKey = Settings.getDefaultPeriodKey(this)
        binding.tvFilterFlightType.text = FlightTypeOptions.label(this, typeKey)
        binding.tvFilterPeriod.text = PeriodOptions.headerLabel(this, periodKey)
        val weight = if (
            typeKey == FlightTypeOptions.KEY_ALL && periodKey == PeriodOptions.KEY_ALL
        ) {
            android.graphics.Typeface.BOLD
        } else {
            android.graphics.Typeface.NORMAL
        }
        binding.tvFilterSeparator.setTypeface(null, weight)
        // Aktiver Filter: Das Wort pulsiert in der Akzentfarbe, damit sofort
        // sichtbar ist, dass oben ein Filter wirkt.
        updateFilterGlow(
            binding.tvFilterFlightType,
            typeKey != FlightTypeOptions.KEY_ALL
        )
        updateFilterGlow(
            binding.tvFilterPeriod,
            periodKey != PeriodOptions.KEY_ALL
        )
    }

    /**
     * Glow um ein Filterwort, solange sein Filter aktiv ist: Die Schrift
     * pulsiert in der Akzentfarbe um die Buchstaben herum, wie ein Leuchten,
     * damit sofort klar ist, dass oben ein Filter wirkt. Ohne aktiven Filter
     * bleibt das Wort schlicht; sind Systemanimationen abgeschaltet, leuchtet
     * der Glow statisch ohne Puls. Der Puls ist bewusst auffaellig (10 dp
     * Radius) und langsam (eine volle Welle in [FILTER_GLOW_PERIOD_MS]).
     * Der Radius muss kleiner bleiben als das Padding der Filter-TextViews
     * (12 dp in activity_main.xml): Eine View clippt ihr Zeichnen auf ihre
     * Bounds, ein groesserer Glow wuerde an den Kanten hart abgeschnitten und
     * als Quadrat sichtbar.
     */
    private fun updateFilterGlow(view: TextView, active: Boolean) {
        filterGlowAnimators.remove(view)?.cancel()
        if (!active) {
            view.setShadowLayer(0f, 0f, 0f, 0)
            return
        }
        val accent = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorPrimary
        )
        val glowRadius = 10f * resources.displayMetrics.density
        if (!animationsEnabled()) {
            view.setShadowLayer(glowRadius, 0f, 0f, accent)
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FILTER_GLOW_PERIOD_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                // Sinus-Puls: weich auf- und abschwellen statt harter Kante.
                val pulse =
                    kotlin.math.sin((it.animatedValue as Float) * Math.PI).toFloat()
                view.setShadowLayer(glowRadius * pulse, 0f, 0f, accent)
            }
            start()
        }
        filterGlowAnimators[view] = animator
    }

    /** Haelt alle Glow-Animationen der Filterwoerter an und nimmt den Glow weg. */
    private fun stopFilterGlow() {
        // Auf dem Willkommens-Weg ist das Binding nie initialisiert worden.
        if (!::binding.isInitialized) return
        filterGlowAnimators.values.forEach { it.cancel() }
        filterGlowAnimators.clear()
        binding.tvFilterFlightType.setShadowLayer(0f, 0f, 0f, 0)
        binding.tvFilterPeriod.setShadowLayer(0f, 0f, 0f, 0)
    }

    /** Ob Systemanimationen laufen dürfen (Einstellung "Animationsdauer"). */
    private fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f

    /**
     * Kopfzeile des Dashboards: links der Avatar, daneben der Name des Nutzers
     * und darunter bei Besatzung die Funktion mit dem Arbeitgeber, durch einen
     * Punkt getrennt. Ein Fluggast hat keine Funktion und keinen Arbeitgeber,
     * dort bleibt die zweite Zeile leer. Alle Werte kommen aus den Einstellungen
     * und werden hier bei jedem Aufkommen neu gelesen.
     *
     * Beim Avatar wird wie auf der Profilseite unterschieden: Ein
     * hochgeladenes Foto fuellt den Kreis voll aus und behaelt seine Farben,
     * ein Preset-Icon sitzt verkleinert in der Mitte und ist eingefaerbt.
     */
    private fun refreshDashboardHeader() {
        val avatar = binding.ivDashboardAvatar
        if (Settings.getAvatar(this) == Settings.AVATAR_FILE) {
            avatar.setPadding(0, 0, 0, 0)
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            avatar.setImageDrawable(ProfileAvatar.load(this))
            avatar.imageTintList = null
        } else {
            avatar.setImageDrawable(ProfileAvatar.load(this))
            avatar.scaleType = ImageView.ScaleType.FIT_CENTER
            avatar.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    binding.main,
                    com.google.android.material.R.attr.colorOnPrimaryContainer
                )
            )
        }

        val crew = Settings.getRole(this).isCrew
        val name = Settings.getProfileName(this).trim()
        binding.tvDashboardName.text = name.ifBlank {
            getString(if (crew) R.string.role_crew else R.string.role_passenger)
        }

        if (crew) {
            val function = Settings.selectedCrewFunctionLabel(this)
            val code = Settings.getAirline(this).trim().uppercase()
            val employer = AirlineCatalog.name(this, code) ?: code
            binding.tvDashboardSubtitle.text = if (employer.isBlank()) {
                function
            } else {
                getString(R.string.dashboard_header_subtitle, function, employer)
            }
            binding.tvDashboardSubtitle.visibility = View.VISIBLE
        } else {
            // Fluggast: nur der Name, ohne Funktion und Arbeitgeber.
            binding.tvDashboardSubtitle.text = ""
            binding.tvDashboardSubtitle.visibility = View.GONE
        }
    }

    private fun showFlightTypeMenu(anchor: View) {
        val keys = FlightTypeOptions.keys()
        FilterPopupMenu.showOptions(
            this,
            anchor,
            keys,
            keys.map { FlightTypeOptions.label(this, it) },
            Settings.getFlightTypeFilterKey(this)
        ) { key ->
            Settings.setFlightTypeFilterKey(this, key)
            renderDashboardFilter()
            DashboardEvents.onFilterChanged?.invoke()
        }
    }

    /** Dieselben Zeitraeume wie zuvor im Auswahlfeld, jetzt im Menue am Wort. */
    private fun showPeriodMenu(anchor: View) {
        val keys = PeriodOptions.keys(this)
        FilterPopupMenu.showOptions(
            this,
            anchor,
            keys,
            keys.map { PeriodOptions.headerLabel(this, it) },
            Settings.getDefaultPeriodKey(this)
        ) { key ->
            Settings.setDefaultPeriodKey(this, key)
            renderDashboardFilter()
            DashboardEvents.onFilterChanged?.invoke()
        }
    }

    /**
     * Manual bottom navigation handling. NavigationUI's built-in tab logic
     * breaks when a top-level destination (e.g. nav_entries) is pushed on top
     * of a full-screen destination like the world map: tapping "Dashboard"
     * would instantly restore the just-popped back stack ("nothing happens").
     * Instead we pop to an existing tab instance or navigate fresh, which
     * keeps the drill-down (world map -> entries) intact and the tabs working.
     */
    private fun setupBottomNav(navController: NavController) {
        binding.bottomNav.setTabs(
            listOf(
                BottomTabBar.Tab(R.id.nav_entries, R.drawable.ic_entries, R.string.nav_entries),
                BottomTabBar.Tab(R.id.nav_discovery, R.drawable.ic_star, R.string.nav_discovery),
                BottomTabBar.Tab(R.id.nav_dashboard, R.drawable.ic_dashboard, R.string.nav_dashboard),
                BottomTabBar.Tab(
                    R.id.nav_rubbelkarte, R.drawable.ic_world_map, R.string.nav_rubbelkarte
                ),
                BottomTabBar.Tab(R.id.nav_profile, R.drawable.ic_settings, R.string.nav_profile),
            )
        )
        binding.bottomNav.setOnTabSelectedListener { destId ->
            val currentId = navController.currentDestination?.id
            if (currentId == destId) {
                navController.popBackStack(
                    navController.graph.findStartDestination().id,
                    false
                )
            } else if (!navController.popBackStack(destId, false)) {
                // popBackStack gibt false zurueck, wenn das Ziel gar nicht im
                // Backstack liegt - dann muss neu navigiert werden.
                navController.navigate(
                    destId,
                    null,
                    NavOptions.Builder()
                        .setLaunchSingleTop(true)
                        .setPopUpTo(navController.graph.findStartDestination().id, false)
                        // Seitenwechsel als Fade-Through (Crossfade): die neue
                        // Seite blendet ein, die alte aus - auch beim Poppen.
                        .setEnterAnim(R.anim.nav_fade_in)
                        .setExitAnim(R.anim.nav_fade_out)
                        .setPopEnterAnim(R.anim.nav_fade_in)
                        .setPopExitAnim(R.anim.nav_fade_out)
                        .build()
                )
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateTabSelection(destination.id)
        }
        // The navigation to the start destination may already have happened
        // before this listener was registered, so sync once explicitly.
        updateTabSelection(navController.currentDestination?.id)
    }

    private fun isMenuTab(destinationId: Int?): Boolean =
        destinationId == R.id.nav_dashboard ||
                destinationId == R.id.nav_entries ||
                destinationId == R.id.nav_discovery ||
                destinationId == R.id.nav_rubbelkarte ||
                destinationId == R.id.nav_profile

    private fun updateTabSelection(destinationId: Int?) {
        // Die Auswahl wird nicht zurückgesetzt: Solange die Leiste sichtbar
        // ist, bleibt der zuletzt gewählte Tab markiert - auch auf einer
        // Detailseite (z. B. Airline- oder Flughafen-Detail), die über eine
        // der Tab-Seiten aufgeht.
        if (!isMenuTab(destinationId)) return
        binding.bottomNav.setSelectedDestination(destinationId!!)
    }

    private fun updateHeaderAndContent() {
        val dest = if (::navController.isInitialized) navController.currentDestination?.id else null
        val showHeader = dest == R.id.nav_dashboard
        val isAddEntry = dest == R.id.nav_add_entry
        val isWorldMap = dest == R.id.nav_world_map
        val isImport = dest == R.id.nav_import

        binding.appBar.visibility = if (showHeader) View.VISIBLE else View.GONE
        // Ausserhalb des Dashboards ist die Filterzeile nicht sichtbar; der
        // Glow der aktiven Filter pausiert, bis das Dashboard zurueckkehrt.
        if (!showHeader) stopFilterGlow()
        binding.appBar.setPadding(navBarLeft, if (showHeader) statusBarTop else 0, navBarRight, 0)
        val navVisible = !isAddEntry && !isWorldMap && !isImport
        // Über der Tastatur soll nichts stehen: Solange sie offen ist, bekommt
        // der Inhalt ihren Platz und die Navigationsleiste verschwindet ganz.
        // Sie taucht genau dort wieder auf, wo sie sonst auch steht - direkt
        // über der Systemleiste.
        val navSichtbar = navVisible && !imeVisible
        binding.bottomNav.visibility = if (navSichtbar) View.VISIBLE else View.GONE
        binding.bottomNav.updatePadding(
            left = navBarLeft,
            right = navBarRight,
            bottom = navBarBottom
        )
        binding.contentHost.updatePadding(
            top = if (showHeader || isWorldMap) 0 else statusBarTop,
            left = if (showHeader) 0 else navBarLeft,
            right = if (showHeader) 0 else navBarRight,
            // Die Navigationsleiste schwebt über dem Inhalt: Die Seite läuft
            // bis zum unteren Rand, die Leiste liegt darüber.
            bottom = if (navSichtbar) 0 else imeBottom
        )
    }

    /**
     * Die Tastaturhoehe stammt bevorzugt aus den echten Insets, ersatzweise aus
     * dem Rahmen der laufenden Animation. Beide Werte werden begrenzt: Der
     * Content darf nie auf 0 zusammenfallen, sonst waere fuer die Dauer der
     * Animation nur noch der Fensterhintergrund zu sehen (die Seite schlaegt
     * dann kurz schwarz bzw. weiss um).
     */
    private fun clampImeBottom(candidate: Int): Int {
        val minContent = 240f * resources.displayMetrics.density
        val maxBottom = (binding.main.height - statusBarTop - minContent).toInt()
        if (candidate <= 0 || candidate > maxBottom) return 0
        return candidate
    }

    private fun applyImeInset(bounds: WindowInsetsAnimationCompat.BoundsCompat? = null) {
        val insets = ViewCompat.getRootWindowInsets(binding.main) ?: return
        val reported = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val candidate = if (clampImeBottom(reported) > 0) {
            reported
        } else {
            bounds?.let { binding.main.height - it.lowerBound.top } ?: 0
        }
        val bottom = clampImeBottom(candidate)
        if (bottom != imeBottom) {
            imeBottom = bottom
            updateHeaderAndContent()
        }
    }

    /**
     * Die Insets werden hier konsumiert, daher bekommen die Fragmente den
     * Tastaturzustand nicht automatisch. Das aktuelle Ziel der Navigation wird
     * direkt benachrichtigt, damit es seinen Inhalt an die kleinere Hoehe
     * anpassen kann.
     */
    private fun updateImeVisibility(visible: Boolean) {
        if (visible == imeVisible) return
        imeVisible = visible
        updateHeaderAndContent()
        val host = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) ?: return
        val current = host.childFragmentManager.primaryNavigationFragment ?: return
        (current as? ImeVisibilityAware)?.onImeVisibilityChanged(visible)
    }

    /**
     * Im Edge-to-Edge-Modus wird die Tastaturhoehe ueber die Insets der
     * Tastatur gelesen, die waehrend der Animation frameweise aktualisiert
     * werden. Der Rahmen der Animation dient nur als Rueckfall, wenn das
     * System keine Tastaturhoehe meldet - und wird dabei geprueft, weil er auf
     * manchen Geraeten den kompletten Fensterbereich umfasst (top = 0).
     */
    private fun setupImeAnimation() {
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.main,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                private fun isIme(animation: WindowInsetsAnimationCompat) =
                    animation.typeMask and WindowInsetsCompat.Type.ime() != 0

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (isIme(animation)) updateImeVisibility(true)
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (isIme(animation)) applyImeInset(bounds)
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat = insets

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (!isIme(animation)) return
                    applyImeInset()
                    val visible = ViewCompat.getRootWindowInsets(binding.main)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    updateImeVisibility(visible)
                }
            }
        )
    }

    override fun onPause() {
        super.onPause()
        // Im Hintergrund pulsiert kein Filter-Glow.
        stopFilterGlow()
    }

    override fun onResume() {
        super.onResume()
        // Rückkehr aus der Story: Die StoryActivity hat den Flag gesetzt.
        // Die Activity baut sich neu auf, damit MaterialCardViews und Karten
        // (Discovery, Dashboard, Rubbelkarte) mit dem korrekten Theme zeichnen
        // - sonst bleiben sie im falschen Modus hängen.
        if (StoryActivity.wasLaunched) {
            StoryActivity.wasLaunched = false
            recreate()
            return
        }
        // Die Willkommens-Tour laesst sich aus den Einstellungen erneut öffnen
        // und kann dort Akzentfarbe und Erscheinungsbild ändern. Theme-Wechsel
        // meldet AppCompatDelegate zwar selbst, die Akzentfarbe nicht - sie
        // wird hier verglichen und die Activity bei Bedarf neu aufgebaut.
        val accent = Settings.getAccentColor(this)
        val themeMode = Settings.getThemeMode(this)
        if (appliedAccent != null &&
            (appliedAccent != accent || appliedThemeMode != themeMode)
        ) {
            appliedAccent = accent
            appliedThemeMode = themeMode
            recreate()
            return
        }
        appliedAccent = accent
        appliedThemeMode = themeMode
        // Der Zeitraum laesst sich auch in den Einstellungen aendern: Die
        // Filterzeile muss das bei jedem Aufkommen uebernehmen. Gleiches gilt
        // fuer Avatar, Rolle und Fluggesellschaft der Kopfzeile.
        renderDashboardFilter()
        refreshDashboardHeader()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        binding.toolbar.setTitle("")
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        stopFilterGlow()
        super.onDestroy()
    }
}