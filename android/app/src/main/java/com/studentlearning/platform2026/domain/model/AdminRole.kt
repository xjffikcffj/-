package com.studentlearning.platform2026.domain.model

enum class AdminRole(val claimValue: String?) {
    OWNER("owner"),
    ADMIN("admin"),
    NONE(null);

    val canAccessAdminDashboard: Boolean
        get() = this == OWNER || this == ADMIN

    val canManageCatalog: Boolean
        get() = this == OWNER || this == ADMIN

    companion object {
        fun fromClaim(rawClaim: Any?): AdminRole {
            val normalized = (rawClaim as? String)
                ?.trim()
                ?.lowercase()
                ?: return NONE

            return when (normalized) {
                "owner" -> OWNER
                "admin" -> ADMIN
                else -> NONE
            }
        }
    }
}

/**
 * Represents the client-side authentication & custom-claim snapshot for the active Firebase user.
 *
 * IMPORTANT SECURITY NOTE:
 * Firebase Auth SDK parses custom claims from the local ID token on the device; it does NOT
 * perform cryptographic server-side JWT verification. True token verification and write authorization
 * are enforced exclusively on Firebase servers and Firestore Security Rules.
 */
data class AdminSession(
    val isSignedIn: Boolean = false,
    val uid: String? = null,
    val email: String? = null,
    val role: AdminRole = AdminRole.NONE,
    val hasLoadedTokenClaims: Boolean = false,
    val areClaimsConfirmedCurrent: Boolean = false
) {
    /**
     * Effective role tier for the current session:
     * unauthenticated -> [AppUserRole.GUEST];
     * signed-in without a verified server-side admin/owner claim -> [AppUserRole.STUDENT].
     */
    val userRole: AppUserRole
        get() = when {
            !isSignedIn -> AppUserRole.GUEST
            hasLoadedTokenClaims && role == AdminRole.OWNER -> AppUserRole.OWNER
            hasLoadedTokenClaims && role == AdminRole.ADMIN -> AppUserRole.ADMIN
            else -> AppUserRole.STUDENT
        }

    val canDownloadOfflineFiles: Boolean
        get() = userRole.canDownloadOfflineFiles

    val canManageOwnAccount: Boolean
        get() = userRole.canManageOwnAccount

    val hasOwnerPrivileges: Boolean
        get() = isSignedIn && hasLoadedTokenClaims && role == AdminRole.OWNER

    val canPerformOwnerOnlyActions: Boolean
        get() = hasOwnerPrivileges && areClaimsConfirmedCurrent

    /** True when the client has loaded claims identifying this signed-in account as owner or admin. */
    val hasRecognizedAdminRole: Boolean
        get() = isSignedIn && hasLoadedTokenClaims && role.canAccessAdminDashboard

    /**
     * True only when the user is signed in, claims were loaded with a recognized admin/owner role,
     * AND claims are confirmed current (not stale after a failed network token refresh).
     */
    val canPerformAdminActions: Boolean
        get() = hasRecognizedAdminRole && areClaimsConfirmedCurrent
}
