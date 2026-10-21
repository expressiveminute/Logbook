package com.highfly.logbook

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Basisklasse aller Seiten der App: Merkt sich die Scroll-Position jedes
 * Scroll-Containers (RecyclerView, ScrollView, HorizontalScrollView), wenn
 * die Seite verlassen wird, und stellt sie beim Wiederaufbau der Ansicht
 * wieder her - egal, ob die Rückkehr über die Zurück-Taste, den Zurück-Pfeil
 * oder einen Wechsel in der unteren Leiste läuft. Die Position lebt in der
 * Fragment-Instanz (sie überlebt die Navigation im Backstack) und zusätzlich
 * im Instanzzustand (Drehung, Prozess-Neustart).
 *
 * Schlüssel ist die View-ID des Containers; Container ohne ID bleiben außen
 * vor. Seiten mit eigenem, gezieltem Scrollen (z. B. nach dem Bearbeiten
 * eines Eintrags) rufen [cancelPendingScrollRestore] auf, damit die
 * Wiederherstellung nicht dagegenhält.
 *
 * Beim RecyclerView wird die Position des ersten sichtbaren Elements samt
 * seinem Abstand zum Listenanfang gemerkt (wie die früheren manuellen
 * Lösungen der Listen); die Daten treffen oft erst asynchron ein, deshalb
 * wird gewartet, bis der Adapter Einträge hat. Beim ScrollView wächst der
 * Inhalt teils erst nach dem ersten Layout (Kacheln, Bilder), deshalb wird
 * gewartet, bis sich die Inhaltshöhe nicht mehr ändert, und die Position
 * notfalls auf das erreichbare Maximum begrenzt.
 */
abstract class LogbookFragment : Fragment() {

    /** Gemerkter Zustand eines Scroll-Containers. */
    private sealed class ScrollState {
        /** RecyclerView: Position des ersten sichtbaren Elements und Offset. */
        class List(val position: Int, val offset: Int) : ScrollState()

        /** ScrollView: vertikale Scroll-Position. */
        class Vertical(val y: Int) : ScrollState()

        /** HorizontalScrollView: horizontale Scroll-Position. */
        class Horizontal(val x: Int) : ScrollState()
    }

    /** Gemerkte Zustände je View-ID, gefüllt beim Verlassen der Seite. */
    private val savedScrollStates = HashMap<Int, ScrollState>()

    /** Zustände, die auf die Wiederherstellung in der neuen Ansicht warten. */
    private val pendingScrollRestores = HashMap<Int, ScrollState>()

    /** Wird bei jedem Abbruch der Wiederherstellung erhöht. */
    private var restoreGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drehung oder Prozess-Neustart: Die Zustände kommen aus dem Bundle.
        val count = savedInstanceState?.getInt(KEY_SCROLL_COUNT) ?: 0
        for (i in 0 until count) {
            val entry = savedInstanceState?.getBundle("$KEY_SCROLL_ENTRY$i") ?: continue
            val id = entry.getInt(KEY_SCROLL_ID, 0)
            if (id == 0) continue
            val state = when (entry.getInt(KEY_SCROLL_TYPE, TYPE_VERTICAL)) {
                TYPE_LIST -> ScrollState.List(
                    entry.getInt(KEY_SCROLL_POSITION, 0),
                    entry.getInt(KEY_SCROLL_OFFSET, 0)
                )
                TYPE_HORIZONTAL -> ScrollState.Horizontal(entry.getInt(KEY_SCROLL_POSITION, 0))
                else -> ScrollState.Vertical(entry.getInt(KEY_SCROLL_POSITION, 0))
            }
            savedScrollStates[id] = state
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoreScrollStates(view)
    }

    override fun onPause() {
        super.onPause()
        // Die Ansicht steht noch; die Position merken, solange sie greifbar ist.
        view?.let { saveScrollStates(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Aus der noch stehenden Ansicht auffrischen: Die Reihenfolge von
        // onPause und onSaveInstanceState ist nicht garantiert.
        view?.let { saveScrollStates(it) }
        val states = savedScrollStates.entries.toList()
        outState.putInt(KEY_SCROLL_COUNT, states.size)
        states.forEachIndexed { index, (id, state) ->
            outState.putBundle("$KEY_SCROLL_ENTRY$index", bundleOf(
                KEY_SCROLL_ID to id,
                KEY_SCROLL_TYPE to when (state) {
                    is ScrollState.List -> TYPE_LIST
                    is ScrollState.Horizontal -> TYPE_HORIZONTAL
                    is ScrollState.Vertical -> TYPE_VERTICAL
                },
                KEY_SCROLL_POSITION to when (state) {
                    is ScrollState.List -> state.position
                    is ScrollState.Horizontal -> state.x
                    is ScrollState.Vertical -> state.y
                },
                KEY_SCROLL_OFFSET to if (state is ScrollState.List) state.offset else 0
            ))
        }
    }

    /**
     * Verwirft eine noch nicht angewendete Wiederherstellung. Seiten, die
     * selbst gezielt scrollen (z. B. zum gerade bearbeiteten Eintrag), rufen
     * das auf, bevor sie scrollen.
     */
    protected fun cancelPendingScrollRestore() {
        restoreGeneration++
        pendingScrollRestores.clear()
    }

    /** Sammelt alle Scroll-Container der Ansicht in Tiefensuche. */
    private fun collectScrollables(view: View, out: MutableList<View>) {
        if (view is RecyclerView || view is ScrollView || view is HorizontalScrollView) {
            out.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectScrollables(view.getChildAt(i), out)
            }
        }
    }

    /** Merkt die Scroll-Position jedes Containers mit ID in der Ansicht. */
    private fun saveScrollStates(root: View) {
        val scrollables = mutableListOf<View>()
        collectScrollables(root, scrollables)
        for (scrollable in scrollables) {
            val id = scrollable.id
            if (id == View.NO_ID) continue
            when (scrollable) {
                is RecyclerView -> {
                    val layoutManager = scrollable.layoutManager as? LinearLayoutManager
                        ?: continue
                    val position = layoutManager.findFirstVisibleItemPosition()
                    if (position < 0) continue
                    val first = layoutManager.findViewByPosition(position)
                    val offset = if (first != null) {
                        first.top - scrollable.paddingTop
                    } else {
                        0
                    }
                    // Ganz oben ist die Vorgabe; das spart die Wiederherstellung.
                    if (position > 0 || offset > 0) {
                        savedScrollStates[id] = ScrollState.List(position, offset)
                    }
                }
                is HorizontalScrollView -> {
                    if (scrollable.scrollX > 0) {
                        savedScrollStates[id] = ScrollState.Horizontal(scrollable.scrollX)
                    }
                }
                is ScrollView -> {
                    if (scrollable.scrollY > 0) {
                        savedScrollStates[id] = ScrollState.Vertical(scrollable.scrollY)
                    }
                }
            }
        }
    }

    /** Stellt die gemerkten Positionen in der frisch aufgebauten Ansicht her. */
    private fun restoreScrollStates(root: View) {
        if (savedScrollStates.isEmpty()) return
        val scrollables = mutableListOf<View>()
        collectScrollables(root, scrollables)
        for (scrollable in scrollables) {
            val id = scrollable.id
            if (id == View.NO_ID) continue
            val state = savedScrollStates.remove(id) ?: continue
            pendingScrollRestores[id] = state
            when (scrollable) {
                is RecyclerView -> {
                    if (state is ScrollState.List) {
                        restoreRecyclerView(scrollable, state)
                    } else {
                        pendingScrollRestores.remove(id)
                    }
                }
                is HorizontalScrollView -> {
                    if (state is ScrollState.Horizontal) {
                        restoreHorizontalScrollView(scrollable, state)
                    } else {
                        pendingScrollRestores.remove(id)
                    }
                }
                is ScrollView -> {
                    if (state is ScrollState.Vertical) {
                        restoreScrollView(scrollable, state)
                    } else {
                        pendingScrollRestores.remove(id)
                    }
                }
            }
        }
    }

    /**
     * Stellt die Listenposition wieder her. Die Daten treffen oft erst
     * asynchron ein; bis der Adapter Einträge hat, wird im Takt des Bildaufbaus
     * erneut versucht, längstens [RESTORE_DEADLINE_MS].
     */
    private fun restoreRecyclerView(list: RecyclerView, state: ScrollState.List) {
        val generation = restoreGeneration
        val deadline = SystemClock.uptimeMillis() + RESTORE_DEADLINE_MS
        val retry = object : Runnable {
            override fun run() {
                if (generation != restoreGeneration) return
                if (pendingScrollRestores[list.id] != state) return
                val count = list.adapter?.itemCount ?: 0
                if (count > 0) {
                    pendingScrollRestores.remove(list.id)
                    // Hat sich der Bestand verkleinert, landet die Position
                    // am Ende der Liste statt außerhalb.
                    val position = state.position.coerceAtMost(count - 1)
                    (list.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(position, state.offset)
                } else if (SystemClock.uptimeMillis() < deadline) {
                    list.post(this)
                } else {
                    pendingScrollRestores.remove(list.id)
                }
            }
        }
        list.post(retry)
    }

    /**
     * Stellt die vertikale Scroll-Position wieder her. Der Inhalt wächst teils
     * erst nach dem ersten Layout (Kacheln, Bilder); gewartet wird, bis sich
     * die Inhaltshöhe zwischen zwei Durchläufen nicht mehr ändert, längstens
     * [RESTORE_DEADLINE_MS]. Danach wird die Position auf das erreichbare
     * Maximum begrenzt, falls der Inhalt inzwischen kürzer geworden ist.
     */
    private fun restoreScrollView(scroll: ScrollView, state: ScrollState.Vertical) {
        val generation = restoreGeneration
        val deadline = SystemClock.uptimeMillis() + RESTORE_DEADLINE_MS
        var lastHeight = -1
        val retry = object : Runnable {
            override fun run() {
                if (generation != restoreGeneration) return
                if (pendingScrollRestores[scroll.id] != state) return
                if (scroll.childCount == 0) {
                    if (SystemClock.uptimeMillis() < deadline) {
                        scroll.post(this)
                    } else {
                        pendingScrollRestores.remove(scroll.id)
                    }
                    return
                }
                val contentHeight = scroll.getChildAt(0).height
                val maxScroll = (contentHeight - scroll.height).coerceAtLeast(0)
                if (maxScroll <= 0) {
                    // Der Inhalt passt ganz in die Ansicht - nichts zu scrollen.
                    pendingScrollRestores.remove(scroll.id)
                    return
                }
                val settled = !scroll.isLayoutRequested && contentHeight == lastHeight
                if (settled || SystemClock.uptimeMillis() >= deadline) {
                    pendingScrollRestores.remove(scroll.id)
                    scroll.scrollTo(0, state.y.coerceAtMost(maxScroll))
                } else {
                    lastHeight = contentHeight
                    scroll.post(this)
                }
            }
        }
        scroll.post(retry)
    }

    /** Wie [restoreScrollView], waagerecht für den HorizontalScrollView. */
    private fun restoreHorizontalScrollView(scroll: HorizontalScrollView, state: ScrollState.Horizontal) {
        val generation = restoreGeneration
        val deadline = SystemClock.uptimeMillis() + RESTORE_DEADLINE_MS
        var lastWidth = -1
        val retry = object : Runnable {
            override fun run() {
                if (generation != restoreGeneration) return
                if (pendingScrollRestores[scroll.id] != state) return
                if (scroll.childCount == 0) {
                    if (SystemClock.uptimeMillis() < deadline) {
                        scroll.post(this)
                    } else {
                        pendingScrollRestores.remove(scroll.id)
                    }
                    return
                }
                val contentWidth = scroll.getChildAt(0).width
                val maxScroll = (contentWidth - scroll.width).coerceAtLeast(0)
                if (maxScroll <= 0) {
                    pendingScrollRestores.remove(scroll.id)
                    return
                }
                val settled = !scroll.isLayoutRequested && contentWidth == lastWidth
                if (settled || SystemClock.uptimeMillis() >= deadline) {
                    pendingScrollRestores.remove(scroll.id)
                    scroll.scrollTo(state.x.coerceAtMost(maxScroll), 0)
                } else {
                    lastWidth = contentWidth
                    scroll.post(this)
                }
            }
        }
        scroll.post(retry)
    }

    private companion object {
        /** Anzahl der gemerkten Scroll-Zustände im Bundle. */
        const val KEY_SCROLL_COUNT = "scrollStateCount"

        /** Präfix der Bundle-Schlüssel je Zustand. */
        const val KEY_SCROLL_ENTRY = "scrollState"

        /** Schlüssel im Zustands-Bundle: View-ID des Containers. */
        const val KEY_SCROLL_ID = "id"

        /** Schlüssel im Zustands-Bundle: Art des Containers. */
        const val KEY_SCROLL_TYPE = "type"

        /** Schlüssel im Zustands-Bundle: Position (bzw. scrollY/scrollX). */
        const val KEY_SCROLL_POSITION = "position"

        /** Schlüssel im Zustands-Bundle: Offset des ersten sichtbaren Elements. */
        const val KEY_SCROLL_OFFSET = "offset"

        const val TYPE_LIST = 1
        const val TYPE_VERTICAL = 2
        const val TYPE_HORIZONTAL = 3

        /**
         * Längste Wartezeit auf asynchron eintreffende Inhalte, bevor die
         * Position auf das erreichbare Maximum begrenzt wird.
         */
        const val RESTORE_DEADLINE_MS = 2000L
    }
}
