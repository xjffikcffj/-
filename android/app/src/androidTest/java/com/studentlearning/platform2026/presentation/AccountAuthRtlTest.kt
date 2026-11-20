package com.studentlearning.platform2026.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.presentation.account.AccountUiState
import com.studentlearning.platform2026.presentation.account.ForgotPasswordScreenContent
import com.studentlearning.platform2026.presentation.account.SignInScreenContent
import com.studentlearning.platform2026.presentation.account.SignUpScreenContent
import com.studentlearning.platform2026.presentation.downloads.DownloadsScreenContent
import com.studentlearning.platform2026.presentation.downloads.DownloadsUiState
import com.studentlearning.platform2026.presentation.settings.SettingsScreenContent
import com.studentlearning.platform2026.presentation.settings.SettingsUiState
import com.studentlearning.platform2026.presentation.theme.HaqibatiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountAuthRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun signInScreen_rendersArabicSignInAndNavigationActions() {
        composeTestRule.setContent {
            HaqibatiTheme {
                SignInScreenContent(
                    uiState = AccountUiState(),
                    onNavigateBack = {},
                    onSignIn = { _, _ -> },
                    onSignOut = {},
                    onNavigateToSignUp = {},
                    onNavigateToForgotPassword = {}
                )
            }
        }

        composeTestRule.onNodeWithText("تسجيل الدخول", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("نسيت كلمة المرور؟ استعادة كلمة المرور").assertIsDisplayed()
        composeTestRule.onNodeWithText("ليس لديك حساب؟ إنشاء حساب طالب جديد").assertIsDisplayed()
        composeTestRule.onNodeWithText("المتابعة كزائر").assertIsDisplayed()
    }

    @Test
    fun signUpScreen_rendersPasswordRulesAndSuccessStateForStudent() {
        composeTestRule.setContent {
            HaqibatiTheme {
                SignUpScreenContent(
                    uiState = AccountUiState(),
                    onNavigateBack = {},
                    onCreateAccount = { _, _, _ -> },
                    onNavigateToSignIn = {},
                    onContinueToApp = {}
                )
            }
        }

        composeTestRule.onNodeWithText("8 أحرف على الأقل وتتضمن حرفاً ورقماً على الأقل.").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "تُنشأ الحسابات الجديدة بدور «طالب» افتراضياً",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun signUpScreen_showsArabicSuccessCardAfterRegistration() {
        composeTestRule.setContent {
            HaqibatiTheme {
                SignUpScreenContent(
                    uiState = AccountUiState(
                        session = AdminSession(
                            isSignedIn = true,
                            uid = "student-1",
                            email = "student@example.com",
                            role = AdminRole.NONE,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = true
                        ),
                        signUpSucceeded = true
                    ),
                    onNavigateBack = {},
                    onCreateAccount = { _, _, _ -> },
                    onNavigateToSignIn = {},
                    onContinueToApp = {}
                )
            }
        }

        composeTestRule.onNodeWithText("تم إنشاء حساب الطالب بنجاح").assertIsDisplayed()
        composeTestRule.onNodeWithText("متابعة إلى حقيبتي").assertIsDisplayed()
    }

    @Test
    fun forgotPasswordScreen_showsArabicTitleAndSentBanner() {
        composeTestRule.setContent {
            HaqibatiTheme {
                ForgotPasswordScreenContent(
                    uiState = AccountUiState(
                        passwordResetEmailSentTo = "student@example.com"
                    ),
                    onNavigateBack = {},
                    onSendResetEmail = {}
                )
            }
        }

        composeTestRule.onNodeWithText("استعادة كلمة المرور").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "أُرسلت رسالة إعادة تعيين كلمة المرور",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun settingsScreen_hidesAdminPortalForConfirmedStudentAndShowsLocalIndependence() {
        composeTestRule.setContent {
            HaqibatiTheme {
                SettingsScreenContent(
                    uiState = SettingsUiState(
                        isLoading = false,
                        adminSession = AdminSession(
                            isSignedIn = true,
                            uid = "student-1",
                            email = "student@example.com",
                            role = AdminRole.NONE,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = true
                        )
                    ),
                    onToggleDarkMode = {},
                    onNavigateToAbout = {},
                    onNavigateToAdminPortal = {}
                )
            }
        }

        composeTestRule.onNodeWithText("طالب (Student)").assertIsDisplayed()
        composeTestRule.onNodeWithText("student@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("تسجيل الخروج").assertIsDisplayed()
        composeTestRule.onNodeWithText("لوحة المشرفين وإدارة المحتوى").assertDoesNotExist()
    }

    @Test
    fun settingsScreen_showsSignInAndSignUpForGuestAndAdminPortalForOwner() {
        composeTestRule.setContent {
            HaqibatiTheme {
                SettingsScreenContent(
                    uiState = SettingsUiState(
                        isLoading = false,
                        adminSession = AdminSession()
                    ),
                    onToggleDarkMode = {},
                    onNavigateToAbout = {},
                    onNavigateToAdminPortal = {}
                )
            }
        }

        composeTestRule.onNodeWithText("زائر (Guest)").assertIsDisplayed()
        composeTestRule.onNodeWithText("تسجيل الدخول").assertIsDisplayed()
        composeTestRule.onNodeWithText("إنشاء حساب طالب").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_showsAdminPortalForVerifiedOwner() {
        composeTestRule.setContent {
            HaqibatiTheme {
                SettingsScreenContent(
                    uiState = SettingsUiState(
                        isLoading = false,
                        adminSession = AdminSession(
                            isSignedIn = true,
                            uid = "owner-1",
                            email = "owner@example.com",
                            role = AdminRole.OWNER,
                            hasLoadedTokenClaims = true,
                            areClaimsConfirmedCurrent = true
                        )
                    ),
                    onToggleDarkMode = {},
                    onNavigateToAbout = {},
                    onNavigateToAdminPortal = {}
                )
            }
        }

        composeTestRule.onNodeWithText("المالك (Owner)").assertIsDisplayed()
        composeTestRule.onNodeWithText("لوحة المشرفين وإدارة المحتوى").assertIsDisplayed()
    }

    @Test
    fun downloadsScreen_showsGuestRestrictionNoticeAlongsideEmptyState() {
        composeTestRule.setContent {
            HaqibatiTheme {
                DownloadsScreenContent(
                    uiState = DownloadsUiState(
                        isInitialized = true,
                        hasSavedFiles = false,
                        userRole = AppUserRole.GUEST
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("التنزيلات متاحة للحسابات المسجّلة").assertIsDisplayed()
        composeTestRule.onNodeWithText("لا توجد ملفات محفوظة حالياً").assertIsDisplayed()
    }

    @Test
    fun downloadsScreen_hidesGuestNoticeForSignedInStudent() {
        composeTestRule.setContent {
            HaqibatiTheme {
                DownloadsScreenContent(
                    uiState = DownloadsUiState(
                        isInitialized = true,
                        hasSavedFiles = false,
                        userRole = AppUserRole.STUDENT
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("التنزيلات متاحة للحسابات المسجّلة").assertDoesNotExist()
        composeTestRule.onNodeWithText("لا توجد ملفات محفوظة حالياً").assertIsDisplayed()
    }
}
