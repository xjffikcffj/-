package com.studentlearning.platform2026.presentation.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.studentlearning.platform2026.R

sealed class AppDestination(val route: String) {
    data object Splash : AppDestination("splash")
    data object MainShell : AppDestination("main_shell")
    data object About : AppDestination("about")
    data object AdminPortal : AppDestination("admin_portal")
    data object SignIn : AppDestination("auth_sign_in")
    data object SignUp : AppDestination("auth_sign_up")
    data object ForgotPassword : AppDestination("auth_forgot_password")

    data object PdfReader : AppDestination(
        "pdf_reader/{fileId}/{fileTitle}?versionId={versionId}"
    ) {
        const val ARG_FILE_ID = "fileId"
        const val ARG_FILE_TITLE = "fileTitle"
        const val ARG_VERSION_ID = "versionId"

        fun createRoute(
            fileId: String,
            fileTitle: String,
            activeVersionId: String? = null
        ): String {
            val safeTitle = fileTitle.ifBlank { "PDF" }
            val base = "pdf_reader/${Uri.encode(fileId)}/${Uri.encode(safeTitle)}"
            return if (activeVersionId.isNullOrBlank()) {
                base
            } else {
                "$base?versionId=${Uri.encode(activeVersionId)}"
            }
        }
    }
}

sealed class LibraryDestination(val route: String) {
    data object Branches : LibraryDestination("library_branches")

    data object Subjects : LibraryDestination("library_subjects/{branchId}/{branchName}") {
        const val ARG_BRANCH_ID = "branchId"
        const val ARG_BRANCH_NAME = "branchName"

        fun createRoute(branchId: String, branchName: String): String {
            return "library_subjects/${Uri.encode(branchId)}/${Uri.encode(branchName)}"
        }
    }

    data object Sections : LibraryDestination("library_sections/{subjectId}/{subjectName}") {
        const val ARG_SUBJECT_ID = "subjectId"
        const val ARG_SUBJECT_NAME = "subjectName"

        fun createRoute(subjectId: String, subjectName: String): String {
            return "library_sections/${Uri.encode(subjectId)}/${Uri.encode(subjectName)}"
        }
    }

    data object SectionFiles : LibraryDestination("library_section_files/{sectionId}/{sectionName}") {
        const val ARG_SECTION_ID = "sectionId"
        const val ARG_SECTION_NAME = "sectionName"

        fun createRoute(sectionId: String, sectionName: String): String {
            val safeName = sectionName.ifBlank { "files" }
            return "library_section_files/${Uri.encode(sectionId)}/${Uri.encode(safeName)}"
        }
    }
}

enum class MainTab(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME(
        route = "tab_home",
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    LIBRARY(
        route = "tab_library",
        labelRes = R.string.nav_library,
        selectedIcon = Icons.Rounded.MenuBook,
        unselectedIcon = Icons.Outlined.MenuBook
    ),
    DOWNLOADS(
        route = "tab_downloads",
        labelRes = R.string.nav_downloads,
        selectedIcon = Icons.Rounded.FolderOpen,
        unselectedIcon = Icons.Outlined.FolderOpen
    ),
    SETTINGS(
        route = "tab_settings",
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings
    );

    companion object {
        fun fromRoute(route: String?): MainTab? =
            entries.firstOrNull { it.route == route }
    }
}

fun NavHostController.navigateToMainTab(tab: MainTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
