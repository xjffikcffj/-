package com.studentlearning.platform2026.presentation

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.core.di.AppContainer
import com.studentlearning.platform2026.presentation.about.AboutScreen
import com.studentlearning.platform2026.presentation.main.MainShellScreen
import com.studentlearning.platform2026.presentation.navigation.AppDestination
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainShellRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainShell_switchesTabsAndNavigatesToAboutWithRtlBackPreservingTab() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appContainer = AppContainer(context)

        composeTestRule.setContent {
            HaqibatiTheme {
                val rootNavController = rememberNavController()
                NavHost(
                    navController = rootNavController,
                    startDestination = AppDestination.MainShell.route
                ) {
                    composable(AppDestination.MainShell.route) {
                        MainShellScreen(
                            appContainer = appContainer,
                            onNavigateToAbout = {
                                rootNavController.navigate(AppDestination.About.route)
                            },
                            onNavigateToAdminPortal = {}
                        )
                    }
                    composable(AppDestination.About.route) {
                        AboutScreen(
                            onNavigateBack = { rootNavController.popBackStack() }
                        )
                    }
                }
            }
        }

        // Starts on Home tab
        composeTestRule.onNodeWithText("مرحباً بك في حقيبتي").assertIsDisplayed()

        // Navigate via bottom bar to Library (Branches screen)
        composeTestRule.onNodeWithText("المكتبة").performClick()
        composeTestRule.onNodeWithText("الفروع الدراسية").assertIsDisplayed()

        // Navigate via bottom bar to Downloads
        composeTestRule.onNodeWithText("التنزيلات").performClick()
        composeTestRule.onNodeWithText("لا توجد ملفات محفوظة حالياً").assertIsDisplayed()

        // Navigate via bottom bar to Settings, then open About
        composeTestRule.onNodeWithText("الإعدادات").performClick()
        composeTestRule.onNodeWithText("حول تطبيق حقيبتي").performClick()
        composeTestRule.onNodeWithText("حول التطبيق").assertIsDisplayed()
        composeTestRule.onNodeWithText("رقم الإصدار").assertIsDisplayed()

        // Press RTL back button and verify Settings tab is restored
        composeTestRule.onNodeWithContentDescription("رجوع").performClick()
        composeTestRule.onNodeWithText("حول تطبيق حقيبتي").assertIsDisplayed()
    }
}
