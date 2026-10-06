package com.highfly.logbook

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import com.highfly.logbook.databinding.ActivityMainBinding

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

    override fun attachBaseContext(newBase: Context) {
        val localized = LocaleUtils.applyLocale(newBase, Settings.LANG_DE)
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
        refreshProfileNavIcon()
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateHeaderAndContent()
        }

        setupDashboardFilter()
    }

    /**
     * Die beiden Filterwoerter ueber den Kacheln: links die Flugart, rechts
     * der Zeitraum. Getippt wird nichts - ein Tipp auf eines der Woerter
     * oeffnet das Menue mit seinen Moeglichkeiten, danach richtet sich die
     * Filterzeile und damit die Kachelsumme unten neu aus.
     */
    /** Geplante Fluege im aktuellen Filter; 0 blendet das "i" aus. */
    private var upcomingCount = 0

    private fun setupDashboardFilter() {
        binding.tvFilterFlightType.setOnClickListener { anchor ->
            showFlightTypeMenu(anchor)
        }
        binding.tvFilterPeriod.setOnClickListener { anchor ->
            showPeriodMenu(anchor)
        }
        binding.ivUpcomingHint.setOnClickListener { anchor ->
            showUpcomingHint(anchor)
        }
        renderDashboardFilter()
    }

    /**
     * Das blaue "i" neben der Filterzeile erklaert, dass es geplante Fluege
     * gibt, die keine Kachel mitzaehlt. Es steht bei den Filtern und nicht
     * mehr auf der Kachel "Fluege", weil es nicht diese eine Kachel meint,
     * sondern alle - die Liste nennt sie auf.
     *
     * Sichtbar ist es nur bei geplanten Fluegen; ohne sie gaebe es nichts zu
     * erklaeren.
     */
    private fun showUpcomingHint(anchor: View) {
        val count = upcomingCount
        if (count <= 0) return
        FilterPopupMenu.showInfo(
            this,
            anchor,
            resources.getQuantityString(
                R.plurals.dashboard_upcoming_hint, count, count
            ),
            getString(R.string.dashboard_upcoming_hint_affected),
            affectedTileNames()
        )
    }

    /**
     * Die Kacheln, deren Zahl aus den geflogenen Eintraegen kommt - und damit
     * an den geplanten Fluegen vorbeilaeuft. Genau die stehen im Fenster, in
     * der Reihenfolge des Rasters, damit sie wiederzuerkennen sind.
     */
    private fun affectedTileNames(): List<String> =
        DashboardPrefs.readRows(this)
            .flatten()
            .distinct()
            .map { getString(DashboardPrefs.tileById(it).nameRes) }
            .distinct()

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
                BottomTabBar.Tab(R.id.nav_profile, R.drawable.ic_profile, R.string.nav_profile),
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

    /**
     * Shows the avatar chosen in the profile settings on the bottom bar's
     * profile tab. Uploaded photos keep their colours, presets stay tinted
     * like the other navigation icons.
     */
    fun refreshProfileNavIcon() {
        binding.bottomNav.setTabIcon(R.id.nav_profile, ProfileAvatar.load(this))
    }

    private fun isMenuTab(destinationId: Int?): Boolean =
        destinationId == R.id.nav_dashboard ||
                destinationId == R.id.nav_entries ||
                destinationId == R.id.nav_discovery ||
                destinationId == R.id.nav_rubbelkarte ||
                destinationId == R.id.nav_profile

    private fun updateTabSelection(destinationId: Int?) {
        if (!isMenuTab(destinationId)) {
            binding.bottomNav.setSelectedDestination(-1)
            return
        }
        binding.bottomNav.setSelectedDestination(destinationId!!)
    }

    private fun updateHeaderAndContent() {
        val dest = if (::navController.isInitialized) navController.currentDestination?.id else null
        val showHeader = dest == R.id.nav_dashboard
        val isAddEntry = dest == R.id.nav_add_entry
        val isWorldMap = dest == R.id.nav_world_map
        val isImport = dest == R.id.nav_import

        binding.appBar.visibility = if (showHeader) View.VISIBLE else View.GONE
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
            top = if (showHeader) 0 else statusBarTop,
            left = if (showHeader) 0 else navBarLeft,
            right = if (showHeader) 0 else navBarRight,
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

    override fun onResume() {
        super.onResume()
        refreshProfileNavIcon()
        // Der Zeitraum laesst sich auch in den Einstellungen aendern: Die
        // Filterzeile muss das bei jedem Aufkommen uebernehmen.
        renderDashboardFilter()
        DashboardEvents.onUpcomingCountChanged = { count ->
            upcomingCount = count
            binding.ivUpcomingHint.visibility =
                if (count > 0) View.VISIBLE else View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        DashboardEvents.onUpcomingCountChanged = null
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
}