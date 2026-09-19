package com.highfly.logbook

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtils.applyLocale(newBase, Settings.LANG_DE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (Settings.isDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
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
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            updateHeaderAndContent()
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setTitle("")

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_dashboard, R.id.nav_entries, R.id.nav_profile)
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        setupBottomNav(navController)
        refreshProfileNavIcon()
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateHeaderAndContent()
        }

        setupPeriodDropdown()
        setupDashboardEdit()
    }

    private fun setupDashboardEdit() {
        binding.btnEditTiles.setOnClickListener {
            DashboardEditSheet(
                this
            ) {
                DashboardEvents.onPeriodChanged?.invoke()
            }.show()
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
        fun goToTab(item: android.view.MenuItem) {
            val destId = item.itemId
            val currentId = navController.currentDestination?.id
            if (currentId == destId) {
                navController.popBackStack(
                    navController.graph.findStartDestination().id,
                    false
                )
            } else if (navController.currentBackStack.value.any { it.destination.id == destId }) {
                navController.popBackStack(destId, false)
            } else {
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
        binding.bottomNav.setOnItemSelectedListener { item ->
            goToTab(item)
            true
        }
        binding.bottomNav.setOnItemReselectedListener { item ->
            // A "reselected" item is normally one we are already on. But the
            // bar decides selected/reselected from its internal
            // NavigationBarMenuView.selectedItemId, which can go stale when we
            // leave every tab unchecked while a full-screen destination (world
            // map) is shown. In that case the tap must behave like a regular
            // tab click instead of popping to the start destination.
            if (navController.currentDestination?.id == item.itemId) {
                navController.popBackStack(
                    navController.graph.findStartDestination().id,
                    false
                )
            } else {
                goToTab(item)
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
        val item = binding.bottomNav.menu.findItem(R.id.nav_profile) ?: return
        item.icon = ProfileAvatar.load(this)
    }

    private fun isMenuTab(destinationId: Int?): Boolean =
        destinationId == R.id.nav_dashboard ||
                destinationId == R.id.nav_entries ||
                destinationId == R.id.nav_profile

    private fun updateTabSelection(destinationId: Int?) {
        if (!isMenuTab(destinationId)) return
        // Bottom navigation items are exclusive-checkable. Calling isChecked
        // on any single item rewrites the whole group so that THIS item is
        // the only checked one (the passed boolean is effectively ignored).
        // Therefore we only (re-)check the active tab and let exclusivity
        // uncheck the rest. Rewriting every item in a loop would leave the
        // last one (profile) checked no matter what the destination is.
        // On full-screen destinations (world map, add entry, import) the tabs
        // are left untouched instead of unchecking everything, which would
        // make the bar's internal selectedItemId go stale and resurrect an
        // old tab highlight when returning.
        binding.bottomNav.menu.findItem(destinationId!!)?.isChecked = true
    }

    private fun updateHeaderAndContent() {
        val dest = if (::navController.isInitialized) navController.currentDestination?.id else null
        val showHeader = dest == R.id.nav_dashboard
        val isAddEntry = dest == R.id.nav_add_entry
        val isWorldMap = dest == R.id.nav_world_map
        val isImport = dest == R.id.nav_import

        binding.appBar.visibility = if (showHeader) View.VISIBLE else View.GONE
        binding.btnEditTiles.visibility = if (showHeader) View.VISIBLE else View.GONE
        binding.appBar.setPadding(navBarLeft, if (showHeader) statusBarTop else 0, navBarRight, 0)
        binding.bottomNav.visibility =
            if (isAddEntry || isWorldMap || isImport) View.GONE else View.VISIBLE
        binding.bottomNav.updatePadding(
            left = navBarLeft,
            right = navBarRight,
            bottom = navBarBottom
        )
        binding.contentHost.updatePadding(
            top = if (showHeader) 0 else statusBarTop,
            left = if (showHeader) 0 else navBarLeft,
            right = if (showHeader) 0 else navBarRight,
            bottom = imeBottom
        )
    }

    private fun setupPeriodDropdown() {
        val keys = PeriodOptions.keys(this)
        val labels = keys.map { PeriodOptions.label(this, it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        binding.periodDropdown.setAdapter(adapter)
        binding.periodDropdown.setText(
            PeriodOptions.label(this, Settings.getDefaultPeriodKey(this)),
            false
        )
        binding.periodDropdown.setOnItemClickListener { parent, _, position, _ ->
            Log.d("MainActivity", "Period selected: ${parent.getItemAtPosition(position)}")
            val label = parent.getItemAtPosition(position) as String
            val key = PeriodOptions.keyForLabel(this, label)
            if (key != null) {
                Settings.setDefaultPeriodKey(this, key)
                DashboardEvents.onPeriodChanged?.invoke()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProfileNavIcon()
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