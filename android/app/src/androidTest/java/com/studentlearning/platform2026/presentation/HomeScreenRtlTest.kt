package com.studentlearning.platform2026.presentation

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.FirebaseConfigurationStatus
import com.studentlearning.platform2026.domain.model.FirebaseReadiness
import com.studentlearning.platform2026.presentation.home.HomeScreenContent
import com.studentlearning.platform2026.presentation.home.HomeUiState
import com.studentlearning.platform2026.presentation.navigation.MainTab
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_displaysArabicContentEnforcesRtlAndTriggersQuickNavigation() {
        var observedLayoutDirection: LayoutDirection? = null
        var navigatedTab: MainTab? = null

        composeTestRule.setContent {
            HaqibatiTheme {
                observedLayoutDirection = LocalLayoutDirection.current
                HomeScreenContent(
                    uiState = HomeUiState(
                        isLoading = false,
                        firebaseReadiness = FirebaseReadiness(
                            status = FirebaseConfigurationStatus.AWAITING_GOOGLE_SERVICES_FILE
                        ),
                        applicationPackageId = "com.studentlearning.platform2026"
                    ),
                    onNavigateToTab = { navigatedTab = it }
                )
            }
        }

        assertEquals(LayoutDirection.Rtl, observedLayoutDirection)
        composeTestRule.onNodeWithText("مرحباً بك في حقيبتي").assertIsDisplayed()
        composeTestRule.onNodeWithText("student-learning-platform-2026").assertIsDisplayed()

        composeTestRule.onNodeWithText("المكتبة").performClick()
        assertEquals(MainTab.LIBRARY, navigatedTab)
    }
}
