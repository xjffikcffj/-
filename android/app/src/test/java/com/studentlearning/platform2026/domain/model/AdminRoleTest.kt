package com.studentlearning.platform2026.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminRoleTest {

    @Test
    fun `fromClaim parses owner and admin claims accurately`() {
        assertEquals(AdminRole.OWNER, AdminRole.fromClaim("owner"))
        assertEquals(AdminRole.OWNER, AdminRole.fromClaim(" OWNER "))
        assertEquals(AdminRole.ADMIN, AdminRole.fromClaim("admin"))
        assertEquals(AdminRole.ADMIN, AdminRole.fromClaim("Admin"))

        assertTrue(AdminRole.OWNER.canAccessAdminDashboard)
        assertTrue(AdminRole.OWNER.canManageCatalog)
        assertTrue(AdminRole.ADMIN.canAccessAdminDashboard)
        assertTrue(AdminRole.ADMIN.canManageCatalog)
    }

    @Test
    fun `fromClaim denies unknown, student, null, and non-string claims`() {
        assertEquals(AdminRole.NONE, AdminRole.fromClaim(null))
        assertEquals(AdminRole.NONE, AdminRole.fromClaim(""))
        assertEquals(AdminRole.NONE, AdminRole.fromClaim("student"))
        assertEquals(AdminRole.NONE, AdminRole.fromClaim("moderator"))
        assertEquals(AdminRole.NONE, AdminRole.fromClaim(123))

        assertFalse(AdminRole.NONE.canAccessAdminDashboard)
        assertFalse(AdminRole.NONE.canManageCatalog)
    }

    @Test
    fun `AdminSession separates sign-in, claim loading, recognized role, and safe admin actions`() {
        val signedInStudent = AdminSession(
            isSignedIn = true,
            uid = "u1",
            email = "student@example.com",
            role = AdminRole.NONE,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )
        assertFalse(signedInStudent.hasRecognizedAdminRole)
        assertFalse(signedInStudent.canPerformAdminActions)
        assertEquals(AppUserRole.STUDENT, signedInStudent.userRole)

        val unconfirmedClaimsOwner = AdminSession(
            isSignedIn = true,
            uid = "u2",
            email = "owner@example.com",
            role = AdminRole.OWNER,
            hasLoadedTokenClaims = false,
            areClaimsConfirmedCurrent = false
        )
        assertFalse(unconfirmedClaimsOwner.hasRecognizedAdminRole)
        assertFalse(unconfirmedClaimsOwner.canPerformAdminActions)
        // Until claims are loaded, signed-in accounts never gain admin/owner tier.
        assertEquals(AppUserRole.STUDENT, unconfirmedClaimsOwner.userRole)

        val staleNetworkOwner = unconfirmedClaimsOwner.copy(
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = false
        )
        assertTrue(staleNetworkOwner.hasRecognizedAdminRole)
        assertFalse(staleNetworkOwner.canPerformAdminActions)
        assertFalse(staleNetworkOwner.canPerformOwnerOnlyActions)

        val confirmedOwner = staleNetworkOwner.copy(areClaimsConfirmedCurrent = true)
        assertTrue(confirmedOwner.hasRecognizedAdminRole)
        assertTrue(confirmedOwner.canPerformAdminActions)
        assertTrue(confirmedOwner.canPerformOwnerOnlyActions)
    }

    @Test
    fun `AppUserRole strictly separates Guest, Student, Admin, and Owner capabilities`() {
        val guestSession = AdminSession(isSignedIn = false)
        assertEquals(AppUserRole.GUEST, guestSession.userRole)
        assertTrue(guestSession.userRole.canBrowsePublishedCatalog)
        assertTrue(guestSession.userRole.canViewPublishedFilesOnline)
        assertFalse(guestSession.canDownloadOfflineFiles)
        assertFalse(guestSession.canManageOwnAccount)
        assertFalse(guestSession.userRole.canAccessAdminPortal)
        assertFalse(guestSession.hasOwnerPrivileges)

        val studentSession = AdminSession(
            isSignedIn = true,
            uid = "stu-1",
            email = "student@example.com",
            role = AdminRole.NONE,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )
        assertEquals(AppUserRole.STUDENT, studentSession.userRole)
        assertTrue(studentSession.userRole.canBrowsePublishedCatalog)
        assertTrue(studentSession.userRole.canViewPublishedFilesOnline)
        assertTrue(studentSession.canDownloadOfflineFiles)
        assertTrue(studentSession.canManageOwnAccount)
        assertFalse(studentSession.userRole.canAccessAdminPortal)
        assertFalse(studentSession.hasRecognizedAdminRole)
        assertFalse(studentSession.hasOwnerPrivileges)

        val adminSession = AdminSession(
            isSignedIn = true,
            uid = "adm-1",
            email = "admin@example.com",
            role = AdminRole.ADMIN,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )
        assertEquals(AppUserRole.ADMIN, adminSession.userRole)
        assertTrue(adminSession.canPerformAdminActions)
        assertFalse(adminSession.userRole.hasFullOwnerPrivileges)
        assertFalse(adminSession.hasOwnerPrivileges)
        assertFalse(adminSession.canPerformOwnerOnlyActions)

        val ownerSession = AdminSession(
            isSignedIn = true,
            uid = "own-1",
            email = "owner@example.com",
            role = AdminRole.OWNER,
            hasLoadedTokenClaims = true,
            areClaimsConfirmedCurrent = true
        )
        assertEquals(AppUserRole.OWNER, ownerSession.userRole)
        assertTrue(ownerSession.canPerformAdminActions)
        assertTrue(ownerSession.userRole.hasFullOwnerPrivileges)
        assertTrue(ownerSession.hasOwnerPrivileges)
        assertTrue(ownerSession.canPerformOwnerOnlyActions)
    }
}
