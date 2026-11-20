package com.studentlearning.platform2026.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.studentlearning.platform2026.core.di.AppContainer
import com.studentlearning.platform2026.presentation.about.AboutScreen
import com.studentlearning.platform2026.presentation.account.AccountViewModel
import com.studentlearning.platform2026.presentation.account.ForgotPasswordScreen
import com.studentlearning.platform2026.presentation.account.SignInScreen
import com.studentlearning.platform2026.presentation.account.SignUpScreen
import com.studentlearning.platform2026.presentation.admin.AdminPortalScreen
import com.studentlearning.platform2026.presentation.admin.AdminPortalViewModel
import com.studentlearning.platform2026.presentation.main.MainShellScreen
import com.studentlearning.platform2026.presentation.pdfreader.PdfReaderScreen
import com.studentlearning.platform2026.presentation.pdfreader.PdfReaderViewModel
import com.studentlearning.platform2026.presentation.splash.SplashScreen
import com.studentlearning.platform2026.presentation.splash.SplashViewModel

@Composable
fun HaqibatiNavHost(
    appContainer: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val accountViewModel: AccountViewModel = viewModel(
        factory = AccountViewModel.provideFactory(
            authRepository = appContainer.authRepository
        )
    )

    NavHost(
        navController = navController,
        startDestination = AppDestination.Splash.route,
        modifier = modifier
    ) {
        composable(route = AppDestination.Splash.route) {
            val splashViewModel: SplashViewModel = viewModel(
                factory = SplashViewModel.provideFactory(
                    preferencesRepository = appContainer.preferencesRepository,
                    systemStatusRepository = appContainer.systemStatusRepository,
                    dispatcherProvider = appContainer.dispatcherProvider
                )
            )
            SplashScreen(
                viewModel = splashViewModel,
                onNavigateToHome = {
                    navController.navigate(AppDestination.MainShell.route) {
                        popUpTo(AppDestination.Splash.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(route = AppDestination.MainShell.route) {
            MainShellScreen(
                appContainer = appContainer,
                onNavigateToAbout = {
                    navController.navigate(AppDestination.About.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAdminPortal = {
                    navController.navigate(AppDestination.AdminPortal.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSignIn = {
                    accountViewModel.clearSignInFeedback()
                    navController.navigate(AppDestination.SignIn.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSignUp = {
                    accountViewModel.clearSignUpFeedback()
                    navController.navigate(AppDestination.SignUp.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToPdfReader = { fileId, fileTitle, activeVersionId ->
                    navController.navigate(
                        AppDestination.PdfReader.createRoute(
                            fileId = fileId,
                            fileTitle = fileTitle,
                            activeVersionId = activeVersionId
                        )
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(route = AppDestination.SignIn.route) {
            SignInScreen(
                viewModel = accountViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSignUp = {
                    navController.navigate(AppDestination.SignUp.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToForgotPassword = {
                    accountViewModel.clearPasswordResetFeedback()
                    navController.navigate(AppDestination.ForgotPassword.route) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(route = AppDestination.SignUp.route) {
            SignUpScreen(
                viewModel = accountViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSignIn = {
                    navController.navigate(AppDestination.SignIn.route) {
                        popUpTo(AppDestination.MainShell.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onAccountReady = {
                    navController.popBackStack(
                        route = AppDestination.MainShell.route,
                        inclusive = false
                    )
                }
            )
        }

        composable(route = AppDestination.ForgotPassword.route) {
            ForgotPasswordScreen(
                viewModel = accountViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(route = AppDestination.About.route) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(route = AppDestination.AdminPortal.route) {
            val adminViewModel: AdminPortalViewModel = viewModel(
                factory = AdminPortalViewModel.provideFactory(
                    authRepository = appContainer.authRepository,
                    adminCatalogRepository = appContainer.adminCatalogRepository,
                    r2BrokerStatus = appContainer.pdfVersionStorageBroker.getBrokerStatus()
                )
            )
            AdminPortalScreen(
                viewModel = adminViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = AppDestination.PdfReader.route,
            arguments = listOf(
                navArgument(AppDestination.PdfReader.ARG_FILE_ID) {
                    type = NavType.StringType
                },
                navArgument(AppDestination.PdfReader.ARG_FILE_TITLE) {
                    type = NavType.StringType
                },
                navArgument(AppDestination.PdfReader.ARG_VERSION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments
                ?.getString(AppDestination.PdfReader.ARG_FILE_ID)
                .orEmpty()
            val fileTitle = backStackEntry.arguments
                ?.getString(AppDestination.PdfReader.ARG_FILE_TITLE)
                .orEmpty()
            val activeVersionId = backStackEntry.arguments
                ?.getString(AppDestination.PdfReader.ARG_VERSION_ID)
                ?.takeIf { it.isNotBlank() }

            val pdfReaderViewModel: PdfReaderViewModel = viewModel(
                factory = PdfReaderViewModel.provideFactory(
                    pdfDocumentRepository = appContainer.pdfDocumentRepository,
                    readingProgressRepository = appContainer.readingProgressRepository,
                    fileId = fileId,
                    fileTitle = fileTitle,
                    activeVersionId = activeVersionId
                )
            )
            PdfReaderScreen(
                viewModel = pdfReaderViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
