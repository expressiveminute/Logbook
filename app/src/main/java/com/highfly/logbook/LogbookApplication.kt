package com.highfly.logbook

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class LogbookApplication : Application() {

    override fun attachBaseContext(base: Context) {
        val localized = LocaleUtils.applyLocale(base, Settings.LANG_DE)
        super.attachBaseContext(
            LocaleUtils.applyTextScale(localized, Settings.isCompactText(localized))
        )
    }

    override fun onCreate() {
        super.onCreate()

        CrashLogger.init(this)
        LogbookRepository.init(this)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(Settings.LANG_DE))

        Thread {
            AirportData.location(this, "MUC")
        }.start()
    }
}