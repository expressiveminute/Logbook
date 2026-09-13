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
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.highfly.logbook.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController
    private lateinit var binding: ActivityMainBinding

    private var statusBarTop = 0
    private var navBarLeft = 0
    private var navBarRight = 0
    private var navBarBottom = 0

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
        binding.bottomNav.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateHeaderAndContent()
        }

        setupPeriodDropdown()
        setupDashboardEdit()
    }

    private fun setupDashboardEdit() {
        binding.btnEditTiles.setOnClickListener {
            val key = Settings.getDefaultPeriodKey(this)
            val entries = DashboardStats.filterForPeriod(
                LogbookRepository.getEntries(),
                key
            )
            val values = DashboardStats.values(this, entries)
            DashboardEditSheet(
                this,
                values
            ) {
                DashboardEvents.onPeriodChanged?.invoke()
            }.show()
        }
    }

    private fun updateHeaderAndContent() {
        val showHeader = ::navController.isInitialized
                && navController.currentDestination?.id == R.id.nav_dashboard

        binding.appBar.visibility = if (showHeader) View.VISIBLE else View.GONE
        binding.btnEditTiles.visibility = if (showHeader) View.VISIBLE else View.GONE
        binding.appBar.setPadding(navBarLeft, if (showHeader) statusBarTop else 0, navBarRight, 0)
        binding.bottomNav.updatePadding(
            left = navBarLeft,
            right = navBarRight,
            bottom = navBarBottom
        )
        binding.contentHost.updatePadding(
            top = if (showHeader) 0 else statusBarTop,
            left = if (showHeader) 0 else navBarLeft,
            right = if (showHeader) 0 else navBarRight,
            bottom = 0
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