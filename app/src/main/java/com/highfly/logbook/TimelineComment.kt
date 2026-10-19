package com.highfly.logbook

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.highfly.logbook.databinding.ItemLayoverTimelineBinding

/**
 * Bindet den Kommentar einer Zeitstrahl-Zeile (item_layover_timeline) rechts in
 * der Zeile, falls der Eintrag einen hat.
 *
 * Ohne Buddy in der Zeile (Reisebuddy-Detailseite) spannt der Kommentar von
 * der Mitte bis zum rechten Rand: Das Buddy-Symbol ist eingeklappt und liegt
 * als Punkt in der Mitte, die Textspalte davor endet davor. Mit Buddy
 * (Layover-Seite) steht der Kommentar rechts neben dem Buddy-Namen: Der Name
 * endet an der Kommentarspalte, der Kommentar startet an der Ankerlinie
 * `comment_anchor` und spannt bis zum rechten Rand. Ohne Kommentar ended der
 * Buddy-Name wie bisher am rechten Rand.
 */
fun ItemLayoverTimelineBinding.bindTimelineComment(comment: String?, showBuddy: Boolean) {
    val text = comment?.takeIf { it.isNotBlank() }
    val buddyParams = tvBuddy.layoutParams as ConstraintLayout.LayoutParams
    val commentParams = tvComment.layoutParams as ConstraintLayout.LayoutParams
    if (text == null) {
        tvComment.visibility = View.GONE
        // Ohne Kommentar läuft der Buddy-Name wie bisher bis zum rechten Rand.
        buddyParams.endToStart = ConstraintLayout.LayoutParams.UNSET
        buddyParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        buddyParams.marginEnd = dp(16f)
        buddyParams.goneEndMargin = 0
    } else {
        tvComment.visibility = View.VISIBLE
        tvComment.text = text
        // Der Buddy-Name endet an der Kommentarspalte.
        buddyParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
        buddyParams.endToStart = R.id.tv_comment
        buddyParams.marginEnd = dp(8f)
        buddyParams.goneEndMargin = 0
        // Kommentar-Anker: mit Buddy rechts neben dem Namen (ab der Ankerlinie),
        // ohne Buddy ab der eingeklappten Buddy-Marke in der Mitte.
        commentParams.startToEnd = if (showBuddy) R.id.comment_anchor else R.id.iv_buddy
    }
    tvBuddy.layoutParams = buddyParams
    tvComment.layoutParams = commentParams
}

private fun ItemLayoverTimelineBinding.dp(value: Float): Int =
    (value * root.resources.displayMetrics.density).toInt()
