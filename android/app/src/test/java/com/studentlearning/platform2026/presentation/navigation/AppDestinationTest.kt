package com.studentlearning.platform2026.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDestinationTest {

    @Test
    fun `main tabs have unique non-blank routes in expected RTL order`() {
        val tabs = MainTab.entries
        assertEquals(listOf(MainTab.HOME, MainTab.LIBRARY, MainTab.DOWNLOADS, MainTab.SETTINGS), tabs)

        val uniqueRoutes = tabs.map { it.route }.toSet()
        assertEquals(tabs.size, uniqueRoutes.size)
        uniqueRoutes.forEach { route ->
            assert(route.isNotBlank())
        }
    }

    @Test
    fun `fromRoute resolves known tabs and returns null for unknown routes`() {
        assertEquals(MainTab.HOME, MainTab.fromRoute("tab_home"))
        assertEquals(MainTab.LIBRARY, MainTab.fromRoute("tab_library"))
        assertEquals(MainTab.DOWNLOADS, MainTab.fromRoute("tab_downloads"))
        assertEquals(MainTab.SETTINGS, MainTab.fromRoute("tab_settings"))
        assertNull(MainTab.fromRoute("unknown"))
        assertNull(MainTab.fromRoute(null))
    }

    @Test
    fun `root app destinations have unique routes`() {
        val routes = listOf(
            AppDestination.Splash.route,
            AppDestination.MainShell.route,
            AppDestination.About.route,
            AppDestination.AdminPortal.route,
            AppDestination.SignIn.route,
            AppDestination.SignUp.route,
            AppDestination.ForgotPassword.route,
            AppDestination.PdfReader.route
        )
        assertEquals(routes.size, routes.toSet().size)
        routes.forEach { route ->
            assert(route.isNotBlank())
        }
    }

    @Test
    fun `library nested destinations have unique non-blank routes`() {
        val routes = listOf(
            LibraryDestination.Branches.route,
            LibraryDestination.Subjects.route,
            LibraryDestination.Sections.route,
            LibraryDestination.SectionFiles.route
        )
        assertEquals(routes.size, routes.toSet().size)
        routes.forEach { route ->
            assert(route.isNotBlank())
        }
    }

    @Test
    fun `pdf reader destination route exposes optional versionId and versionLabel arguments`() {
        val route = AppDestination.PdfReader.route
        assert(route.contains("${AppDestination.PdfReader.ARG_VERSION_ID}={${AppDestination.PdfReader.ARG_VERSION_ID}}"))
        assert(route.contains("${AppDestination.PdfReader.ARG_VERSION_LABEL}={${AppDestination.PdfReader.ARG_VERSION_LABEL}}"))
    }
}
