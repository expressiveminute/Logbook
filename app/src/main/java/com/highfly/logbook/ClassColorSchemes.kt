package com.highfly.logbook

object ClassColorSchemes {

    fun colorsFor(scheme: String): List<Int> = when (scheme) {
        else -> listOf(
            R.color.class_economy_bg,
            R.color.class_premium_economy_bg,
            R.color.class_business_bg,
            R.color.class_first_bg,
        )
    }
}