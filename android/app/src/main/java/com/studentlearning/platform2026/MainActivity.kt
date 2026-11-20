package com.studentlearning.platform2026

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.core.locale.ArabicLocaleHelper
import com.studentlearning.platform2026.domain.model.UserPreferences
import com.studentlearning.platform2026.presentation.navigation.HaqibatiNavHost
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ArabicLocaleHelper.wrapContextWithArabicLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = (application as HaqibatiApplication).appContainer

        setContent {
            val preferences by appContainer.preferencesRepository.preferencesFlow
                .collectAsStateWithLifecycle(initialValue = UserPreferences())

            val isDarkMode = preferences.darkModeEnabled
            LaunchedEffect(isDarkMode) {
                val barStyle = SystemBarStyle.auto(
                    lightScrim = Color.TRANSPARENT,
                    darkScrim = Color.TRANSPARENT,
                    detectDarkMode = { isDarkMode }
                )
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle
                )
            }

            HaqibatiTheme(darkTheme = isDarkMode) {
                HaqibatiNavHost(appContainer = appContainer)
            }
        }
    }
}
