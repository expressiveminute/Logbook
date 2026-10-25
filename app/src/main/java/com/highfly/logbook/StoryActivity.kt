package com.highfly.logbook

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.highfly.logbook.databinding.ActivityStoryBinding

/**
 * Die Story-Ansicht zu den Knöpfen "Play" (Dashboard) und "Insights"
 * (Layover-Seite): eine Folge von Seiten im Stil einer Social-Media-Story.
 *
 *  - Tippen auf die rechte Bildschirmhälfte (genauer: zwei Drittel) geht
 *    eine Seite weiter, Tippen auf die linke Drittelseite zurück.
 *  - Jede Seite läuft zehn Sekunden ([PAGE_MS]), dann geht es automatisch
 *    weiter; oben zeigen segmentierte Balken den Fortschritt.
 *  - Die Inhalte sind animiert: Icon und Titel treten auf, die Zahlen
 *    rollen als Flip-Uhr ([FlipNumberView]) hoch, Zeilen flippen gestaffelt.
 *
 * Die Auswertung dahinter rechnet [StoryStats], die Seiten baut
 * [StoryPages]. Geladen wird im Hintergrund (Datenbank und Assets), danach
 * erscheint die erste Seite. Der Timer pausiert mit [onPause] und
 * [onWindowFocusChanged]; bei abgeschalteten Systemanimationen erscheinen
 * die Seiten ohne Choreografie, der Timer läuft aber weiter.
 */
class StoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoryBinding

    private var story: StoryPages.Story? = null

    /** Index der angezeigten Seite. */
    private var currentPage = 0

    /** Zeitpunkt (uptimeMillis), zu dem die aktuelle Seite erschien. */
    private var pageShownAt = 0L

    /** Verstrichene Zeit der aktuellen Seite vor einer Pause. */
    private var elapsedBeforePause = 0L

    /** Ob der Timer gerade pausiert ist. */
    private var timerPaused = true

    /** Ob der Tipp-Hinweis unten schon einmal weggeklickt wurde. */
    private var hintHidden = false

    /** Füllungen der Fortschrittsbalken, eine je Seite. */
    private val progressFills = mutableListOf<View>()

    private val handler = Handler(Looper.getMainLooper())

    /** Aktualisiert den Fortschrittsbalken und schaltet nach [PAGE_MS] weiter. */
    private val ticker = object : Runnable {
        override fun run() {
            val fill = progressFills.getOrNull(currentPage) ?: return
            val elapsed = SystemClock.uptimeMillis() - pageShownAt
            fill.scaleX = (elapsed.toFloat() / PAGE_MS).coerceIn(0f, 1f)
            if (elapsed >= PAGE_MS) {
                nextPage()
            } else {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val localized = LocaleUtils.applyLocale(newBase, Settings.getLanguage(newBase))
        super.attachBaseContext(
            LocaleUtils.applyTextScale(localized, Settings.isCompactText(localized))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Kein setDefaultNightMode hier: Das würde einen globalen Konfig-
        // wechsel auslösen und die MainActivity mit dem falschen Modus neu
        // erstellen. Die Story ist immer dunkel - ihr Hintergrund ist der
        // feste Scrim (story_scrim), alle Text- und Kartenfarben sind fest
        // definiert, die Systemleisten werden unten dunkel geschaltet.
        setTheme(Settings.accentThemeResId(this))
        super.onCreate(savedInstanceState)
        // Merker für die MainActivity: Sie baut sich bei Rückkehr neu auf,
        // damit MaterialCardViews und Karten mit dem korrekten Theme zeichnen.
        wasLaunched = true
        binding = ActivityStoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()
        // Die Story läuft von allein - der Bildschirm soll nicht ausgehen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Der Hintergrund ist immer dunkel, also helle Systemleisten-Icons.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.storyRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.storyHeader.updatePadding(top = bars.top + dp(8))
            binding.tvStoryHint.updatePadding(bottom = bars.bottom + dp(8))
            insets
        }

        binding.btnStoryClose.setOnClickListener { finish() }
        binding.storyTapLeft.setOnClickListener {
            hideHint()
            previousPage()
        }
        binding.storyTapRight.setOnClickListener {
            hideHint()
            nextPage()
        }

        val startIndex = savedInstanceState?.getInt(KEY_PAGE, 0) ?: 0
        loadStory(startIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, currentPage)
    }

    override fun onResume() {
        super.onResume()
        resumeTimer()
    }

    override fun onPause() {
        pauseTimer()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            resumeTimer()
        } else {
            pauseTimer()
        }
    }

    /**
     * Lädt Logbuch und Assets im Hintergrund und baut die Auswertung. Die
     * Story selbst entsteht auf dem Hauptfaden - sie liest nur Strings.
     */
    private fun loadStory(startIndex: Int) {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PLAY
        binding.storyLoading.visibility = View.VISIBLE
        Thread {
            val app = applicationContext
            val entries = LogbookRepository.getFlownEntries()
            val role = Settings.getRole(app)
            // Homebase.current ist beim Laden der Einträge gesetzt worden.
            val homebase = Homebase.current
            val loadFactor = Settings.getPassengerLoadFactor(app)
            val catalog = AircraftSeats.load(app)
            val countries = CountryShapes.load(app).orEmpty()
            val german = Settings.getLanguage(app) == Settings.LANG_DE
            val nameByIso = countries.associate { country ->
                country.iso2 to (if (german) country.nameDe else country.nameEn)
            }
            val lookups = StoryStats.Lookups(
                countryOf = { code -> AirportData.country(app, code) },
                countryNameOf = { iso -> nameByIso[iso] }
            )
            val play = StoryStats.playStats(
                entries, catalog, loadFactor, homebase, lookups = lookups
            )
            val insights = StoryStats.insightsStats(entries, homebase, lookups = lookups)
            val built = if (mode == MODE_INSIGHTS) {
                StoryPages.insights(app, insights)
            } else {
                StoryPages.play(app, play, role)
            }
            runOnUiThread { showStory(built, startIndex) }
        }.start()
    }

    /** Zeigt die fertige Story ab Seite [startIndex]. */
    private fun showStory(story: StoryPages.Story, startIndex: Int) {
        this.story = story
        binding.storyLoading.visibility = View.GONE
        binding.tvStoryTitle.setText(story.titleRes)
        buildProgressSegments(story.pages.size)
        val first = startIndex.coerceIn(0, story.pages.size - 1)
        showPage(first, animate = false)
    }

    /** Baut die segmentierten Fortschrittsbalken, eine je Seite. */
    private fun buildProgressSegments(count: Int) {
        binding.storyProgress.removeAllViews()
        progressFills.clear()
        val track = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(ContextCompat.getColor(this@StoryActivity, R.color.story_progress_track))
        }
        val fillColor = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(ContextCompat.getColor(this@StoryActivity, R.color.story_progress_fill))
        }
        repeat(count) { index ->
            val segment = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(3),
                    1f
                ).apply {
                    if (index < count - 1) marginEnd = dp(4)
                }
                background = track
            }
            val fill = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                background = fillColor
                scaleX = 0f
                pivotX = 0f
            }
            segment.addView(fill)
            binding.storyProgress.addView(segment)
            progressFills += fill
        }
    }

    /** Zeigt die Seite [index] mit Auftritts-Choreografie an. */
    private fun showPage(index: Int, animate: Boolean) {
        val pages = story?.pages ?: return
        if (index !in pages.indices) return

        pauseTimer()

        val oldView = binding.storyContent.getChildAt(0).takeIf {
            it is StoryPageView
        }

        val pageView = StoryPageView(this)
        val maxContentWidth = resources.displayMetrics.widthPixels - dp(48)
        pageView.bind(pages[index], maxContentWidth)
        // Neue Seite unter die Tap-Zonen einfügen (sie liegen hinten).
        binding.storyContent.addView(pageView, 0)
        pageView.playEntrance()

        if (animate && oldView != null) {
            oldView.animate()
                .alpha(0f).scaleX(0.95f).scaleY(0.95f)
                .setDuration(PAGE_TRANSITION_MS)
                .withEndAction { binding.storyContent.removeView(oldView) }
                .start()
        } else {
            oldView?.let { binding.storyContent.removeView(it) }
        }

        currentPage = index
        pageShownAt = SystemClock.uptimeMillis()
        elapsedBeforePause = 0L

        // Fortschritt: vorherige voll, aktuelle und folgende leer.
        progressFills.forEachIndexed { i, fill ->
            fill.scaleX = if (i < index) 1f else 0f
        }
        binding.tvStoryCounter.text = getString(R.string.story_counter, index + 1, pages.size)

        resumeTimer()
    }

    /** Eine Seite weiter; auf der letzten Seite endet die Story. */
    private fun nextPage() {
        val pages = story?.pages ?: return
        if (currentPage < pages.size - 1) {
            showPage(currentPage + 1, animate = true)
        } else {
            finish()
        }
    }

    /** Eine Seite zurück; auf der ersten Seite passiert nichts. */
    private fun previousPage() {
        if (currentPage > 0) {
            showPage(currentPage - 1, animate = true)
        }
    }

    /** Der Timer ruht, solange die Story nicht im Vordergrund ist. */
    private fun pauseTimer() {
        if (timerPaused) return
        timerPaused = true
        handler.removeCallbacks(ticker)
        elapsedBeforePause = SystemClock.uptimeMillis() - pageShownAt
    }

    /** Der Timer läuft weiter, mit der vor der Pause verstrichenen Zeit. */
    private fun resumeTimer() {
        if (!timerPaused) return
        timerPaused = false
        if (story == null) return
        pageShownAt = SystemClock.uptimeMillis() - elapsedBeforePause
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** Blendet den Tipp-Hinweis nach dem ersten Tippen aus. */
    private fun hideHint() {
        if (hintHidden) return
        hintHidden = true
        binding.tvStoryHint.animate()
            .alpha(0f)
            .setDuration(HINT_FADE_MS)
            .withEndAction { binding.tvStoryHint.visibility = View.GONE }
            .start()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Wird gesetzt, wenn die Story gestartet wurde. Die MainActivity
         * prüft das in onResume und baut sich neu auf (recreate), damit
         * MaterialCardViews und Karten nach der Story mit dem korrekten
         * Theme zeichnen - sonst bleiben sie im falschen Modus hängen.
         */
        var wasLaunched = false

        /** Intent-Extra: welche Story - [MODE_PLAY] oder [MODE_INSIGHTS]. */
        const val EXTRA_MODE = "story_mode"

        /** Die Play-Story (Dashboard). */
        const val MODE_PLAY = "play"

        /** Die Insights-Story (Layover-Seite). */
        const val MODE_INSIGHTS = "insights"

        /** Anzeigedauer einer Seite. */
        private const val PAGE_MS = 10_000L

        /** Aktualisierung des Fortschrittsbalkens (etwa 30 Bilder pro Sekunde). */
        private const val TICK_MS = 32L

        /** Dauer des Seitenwechsels. */
        private const val PAGE_TRANSITION_MS = 180L

        /** Dauer des Ausblendens des Tipp-Hinweises. */
        private const val HINT_FADE_MS = 250L

        private const val KEY_PAGE = "story_page"

        /** Startet die Story. */
        fun start(context: Context, mode: String) {
            context.startActivity(
                Intent(context, StoryActivity::class.java).putExtra(EXTRA_MODE, mode)
            )
        }
    }
}
