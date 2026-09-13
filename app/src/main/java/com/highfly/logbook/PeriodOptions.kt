package com.highfly.logbook

import android.content.Context

object PeriodOptions {

    const val KEY_ALL = "all"
    const val KEY_THIS_MONTH = "this_month"
    const val KEY_YTD = "ytd"

    fun keys(context: Context): List<String> = buildList {
        add(KEY_ALL)
        add(KEY_THIS_MONTH)
        add(KEY_YTD)
        addAll(LogbookRepository.getYears().map { it.toString() })
    }

    fun label(context: Context, key: String): String = when (key) {
        KEY_ALL -> context.getString(R.string.period_all)
        KEY_THIS_MONTH -> context.getString(R.string.period_this_month)
        KEY_YTD -> context.getString(R.string.period_ytd)
        else -> key
    }

    fun keyForLabel(context: Context, label: String): String? =
        keys(context).firstOrNull { this.label(context, it) == label }
}