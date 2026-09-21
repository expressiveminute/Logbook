package com.highfly.logbook

object ClassColorSchemes {

    fun colorsFor(scheme: String): List<Int> = when (scheme) {
        else -> listOf(
            R.color.class_economy_bg,
            R.color.class_premium_economy_bg,
            R.color.class_business_bg,
            R.color.class_first_bg,
            R.color.class_jump_bg,
        )
    }

    /**
     * Text colours for the class label in the entries list. In night mode these
     * resolve to lighter variants (values-night) so they stay readable on the
     * dark card background, while the original colours keep being used for
     * charts and highlight tiles.
     */
    fun textColorsFor(scheme: String): List<Int> = when (scheme) {
        else -> listOf(
            R.color.class_economy_text,
            R.color.class_premium_economy_text,
            R.color.class_business_text,
            R.color.class_first_text,
            R.color.class_jump_text,
        )
    }
}