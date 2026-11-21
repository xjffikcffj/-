package com.studentlearning.platform2026.presentation.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.core.di.AppContainer
import com.studentlearning.platform2026.domain.model.UserPreferences
import com.studentlearning.platform2026.presentation.components.HaqibatiBottomBar
import com.studentlearning.platform2026.presentation.components.HaqibatiTopBar
import com.studentlearning.platform2026.presentation.downloads.DownloadsScreen
import com.studentlearning.platform2026.presentation.downloads.DownloadsViewModel
import com.studentlearning.platform2026.presentation.home.HomeScreen
import com.studentlearning.platform2026.presentation.home.HomeViewModel
import com.studentlearning.platform2026.presentation.library.LibraryScreen
import com.studentlearning.platform2026.presentation.library.LibraryViewModel
import com.studentlearning.platform2026.presentation.library.SectionFilesScreen
import com.studentlearning.platform2026.presentation.library.SectionFilesViewModel
import com.studentlearning.platform2026.presentation.library.SectionsScreen
import com.studentlearning.platform2026.presentation.library.SectionsViewModel
import com.studentlearning.platform2026.presentation.library.SubjectsScreen
import com.studentlearning.platform2026.presentation.library.SubjectsViewModel
import com.studentlearning.platform2026.presentation.navigation.LibraryDestination
import com.studentlearning.platform2026.presentation.navigation.MainTab
import com.studentlearning.platform2026.presentation.navigation.navigateToMainTab
import com.studentlearning.platform2026.presentation.settings.SettingsScreen
import com.studentlearning.platform2026.presentation.settings.SettingsViewModel

@Composable
fun MainShellScreen(
    appContainer: AppContainer,
    onNavigateToAbout: () -> Unit,
    onNavigateToAdminPortal: () -> Unit,
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToSignUp: () -> Unit = {},
    onNavigateToPdfReader: (fileId: String, fileTitle: String, activeVersionId: String?) -> Unit = { _, _, _ -> },
    onNavigateToPdfReaderWithLabel: ((fileId: String, fileTitle: String, activeVersionId: String?, versionLabel: String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    tabNavController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentTab = MainTab.entries.firstOrNull { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    } ?: MainTab.HOME

    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.provideFactory(
            preferencesRepository = appContainer.preferencesRepository,
            systemStatusRepository = appContainer.systemStatusRepository
        )
    )
    val preferences by appContainer.preferencesRepository.preferencesFlow
        .collectAsStateWithLifecycle(initialValue = UserPreferences())
    val isDarkMode = preferences.darkModeEnabled

    val topBarTitle = when (currentDestination?.route) {
        LibraryDestination.Subjects.route -> {
            navBackStackEntry?.arguments?.getString(LibraryDestination.Subjects.ARG_BRANCH_NAME)
                ?: stringResource(R.string.nav_library)
        }
        LibraryDestination.Sections.route -> {
            navBackStackEntry?.arguments?.getString(LibraryDestination.Sections.ARG_SUBJECT_NAME)
                ?: stringResource(R.string.nav_library)
        }
        LibraryDestination.SectionFiles.route -> {
            navBackStackEntry?.arguments?.getString(LibraryDestination.SectionFiles.ARG_SECTION_NAME)
                ?: stringResource(R.string.nav_library)
        }
        else -> {
            if (currentTab == MainTab.HOME) {
                stringResource(R.string.app_name)
            } else {
                stringResource(currentTab.labelRes)
            }
        }
    }

    val canNavigateBackWithinTab = when (currentDestination?.route) {
        LibraryDestination.Subjects.route,
        LibraryDestination.Sections.route,
        LibraryDestination.SectionFiles.route -> true
        else -> false
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HaqibatiTopBar(
                title = topBarTitle,
                showBrandIcon = currentTab == MainTab.HOME,
                onNavigateBack = if (canNavigateBackWithinTab) {
                    { tabNavController.popBackStack() }
                } else {
                    null
                },
                actions = {
                    IconButton(onClick = { homeViewModel.onToggleDarkMode(!isDarkMode) }) {
                        Icon(
                            imageVector = if (isDarkMode) {
                                Icons.Rounded.LightMode
                            } else {
                                Icons.Rounded.DarkMode
                            },
                            contentDescription = if (isDarkMode) {
                                stringResource(R.string.home_toggle_light_mode)
                            } else {
                                stringResource(R.string.home_toggle_dark_mode)
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            HaqibatiBottomBar(
                currentTab = currentTab,
                onSelectTab = { targetTab ->
                    tabNavController.navigateToMainTab(targetTab)
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = tabNavController,
            startDestination = MainTab.HOME.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(route = MainTab.HOME.route) {
                HomeScreen(
                    viewModel = homeViewModel,
                    onNavigateToTab = { targetTab ->
                        tabNavController.navigateToMainTab(targetTab)
                    }
                )
            }

            navigation(
                startDestination = LibraryDestination.Branches.route,
                route = MainTab.LIBRARY.route
            ) {
                composable(route = LibraryDestination.Branches.route) {
                    val libraryViewModel: LibraryViewModel = viewModel(
                        factory = LibraryViewModel.provideFactory(
                            catalogRepository = appContainer.catalogRepository
                        )
                    )
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        onSelectBranch = { branch ->
                            tabNavController.navigate(
                                LibraryDestination.Subjects.createRoute(
                                    branchId = branch.id,
                                    branchName = branch.name
                                )
                            ) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(
                    route = LibraryDestination.Subjects.route,
                    arguments = listOf(
                        navArgument(LibraryDestination.Subjects.ARG_BRANCH_ID) {
                            type = NavType.StringType
                        },
                        navArgument(LibraryDestination.Subjects.ARG_BRANCH_NAME) {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val branchId = backStackEntry.arguments
                        ?.getString(LibraryDestination.Subjects.ARG_BRANCH_ID)
                        .orEmpty()
                    val subjectsViewModel: SubjectsViewModel = viewModel(
                        factory = SubjectsViewModel.provideFactory(
                            branchId = branchId,
                            catalogRepository = appContainer.catalogRepository
                        )
                    )
                    SubjectsScreen(
                        viewModel = subjectsViewModel,
                        onSelectSubject = { subject ->
                            tabNavController.navigate(
                                LibraryDestination.Sections.createRoute(
                                    subjectId = subject.id,
                                    subjectName = subject.name
                                )
                            ) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(
                    route = LibraryDestination.Sections.route,
                    arguments = listOf(
                        navArgument(LibraryDestination.Sections.ARG_SUBJECT_ID) {
                            type = NavType.StringType
                        },
                        navArgument(LibraryDestination.Sections.ARG_SUBJECT_NAME) {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val subjectId = backStackEntry.arguments
                        ?.getString(LibraryDestination.Sections.ARG_SUBJECT_ID)
                        .orEmpty()
                    val sectionsViewModel: SectionsViewModel = viewModel(
                        factory = SectionsViewModel.provideFactory(
                            subjectId = subjectId,
                            catalogRepository = appContainer.catalogRepository
                        )
                    )
                    SectionsScreen(
                        viewModel = sectionsViewModel,
                        onSelectSection = { section ->
                            tabNavController.navigate(
                                LibraryDestination.SectionFiles.createRoute(
                                    sectionId = section.id,
                                    sectionName = section.name
                                )
                            ) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(
                    route = LibraryDestination.SectionFiles.route,
                    arguments = listOf(
                        navArgument(LibraryDestination.SectionFiles.ARG_SECTION_ID) {
                            type = NavType.StringType
                        },
                        navArgument(LibraryDestination.SectionFiles.ARG_SECTION_NAME) {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val sectionId = backStackEntry.arguments
                        ?.getString(LibraryDestination.SectionFiles.ARG_SECTION_ID)
                        .orEmpty()
                    val sectionFilesViewModel: SectionFilesViewModel = viewModel(
                        factory = SectionFilesViewModel.provideFactory(
                            sectionId = sectionId,
                            catalogRepository = appContainer.catalogRepository,
                            authRepository = appContainer.authRepository,
                            offlineDownloadsRepository = appContainer.offlineDownloadsRepository
                        )
                    )
                    SectionFilesScreen(
                        viewModel = sectionFilesViewModel,
                        onOpenStudyVersion = { fileId, fileTitle, versionId ->
                            onNavigateToPdfReader(
                                fileId,
                                fileTitle,
                                versionId
                            )
                        },
                        onNavigateToSignIn = onNavigateToSignIn,
                        onNavigateToSignUp = onNavigateToSignUp,
                        onOpenStudyVersionWithLabel = { fileId, fileTitle, versionId, versionLabel ->
                            if (onNavigateToPdfReaderWithLabel != null) {
                                onNavigateToPdfReaderWithLabel(fileId, fileTitle, versionId, versionLabel)
                            } else {
                                onNavigateToPdfReader(fileId, fileTitle, versionId)
                            }
                        }
                    )
                }
            }

            composable(route = MainTab.DOWNLOADS.route) {
                val downloadsViewModel: DownloadsViewModel = viewModel(
                    factory = DownloadsViewModel.provideFactory(
                        authRepository = appContainer.authRepository,
                        offlineDownloadsRepository = appContainer.offlineDownloadsRepository
                    )
                )
                DownloadsScreen(
                    viewModel = downloadsViewModel,
                    onNavigateToSignIn = onNavigateToSignIn,
                    onNavigateToSignUp = onNavigateToSignUp,
                    onOpenLocalVersion = onNavigateToPdfReader,
                    onOpenLocalVersionWithLabel = { fileId, fileTitle, versionId, versionLabel ->
                        if (onNavigateToPdfReaderWithLabel != null) {
                            onNavigateToPdfReaderWithLabel(fileId, fileTitle, versionId, versionLabel)
                        } else {
                            onNavigateToPdfReader(fileId, fileTitle, versionId)
                        }
                    }
                )
            }

            composable(route = MainTab.SETTINGS.route) {
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.provideFactory(
                        preferencesRepository = appContainer.preferencesRepository,
                        systemStatusRepository = appContainer.systemStatusRepository,
                        authRepository = appContainer.authRepository
                    )
                )
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateToAbout = onNavigateToAbout,
                    onNavigateToAdminPortal = onNavigateToAdminPortal,
                    onNavigateToSignIn = onNavigateToSignIn,
                    onNavigateToSignUp = onNavigateToSignUp
                )
            }
        }
    }
}
