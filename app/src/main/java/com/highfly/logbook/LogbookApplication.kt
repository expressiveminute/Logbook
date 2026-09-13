package com.highfly.logbook

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class LogbookApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleUtils.applyLocale(base, Settings.LANG_DE))
    }

    override fun onCreate() {
        super.onCreate()

        LogbookRepository.init(this)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(Settings.LANG_DE))

        Thread {
            AirportData.location(this, "MUC")
        }.start()
    }
}