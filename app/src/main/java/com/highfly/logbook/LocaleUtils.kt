package com.highfly.logbook

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList

object LocaleUtils {

    fun applyLocale(context: Context, languageTag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(languageTag))
        return context.createConfigurationContext(config)
    }
}