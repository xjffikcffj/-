package com.studentlearning.platform2026.core.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object ArabicLocaleHelper {
    val arabicLocale: Locale = Locale.Builder().setLanguage("ar").build()

    fun wrapContextWithArabicLocale(baseContext: Context): Context {
        Locale.setDefault(arabicLocale)
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocale(arabicLocale)
            setLayoutDirection(arabicLocale)
        }
        return baseContext.createConfigurationContext(configuration)
    }
}
