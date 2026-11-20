package com.studentlearning.platform2026.domain.model

/**
 * Canonical application access tiers for Haqibati:
 * - [GUEST]: Unauthenticated visitor. May browse published catalog & view files online once reader
 *   is added; cannot download offline files or access admin tools.
 * - [STUDENT]: Default role for standard registered Email/Password accounts. May additionally
 *   download files for offline study once downloads are implemented and manage own account.
 *   Cannot access Admin Portal or mutate catalog content.
 * - [ADMIN]: Server-assigned custom claim `role = "admin"`. Granted catalog management access
 *   without automatically receiving [OWNER] privileges.
 * - [OWNER]: Server-assigned custom claim `role = "owner"`. Full administrative authority.
 *
 * Client code can never assign [ADMIN] or [OWNER]; custom claims are provisioned solely by
 * server-side tooling and enforced on Firebase servers / Firestore Security Rules.
 */
enum class AppUserRole {
    GUEST,
    STUDENT,
    ADMIN,
    OWNER;

    val canBrowsePublishedCatalog: Boolean
        get() = true

    val canViewPublishedFilesOnline: Boolean
        get() = true

    val canDownloadOfflineFiles: Boolean
        get() = this != GUEST

    val canManageOwnAccount: Boolean
        get() = this != GUEST

    val canAccessAdminPortal: Boolean
        get() = this == ADMIN || this == OWNER

    val canManageCatalogContent: Boolean
        get() = this == ADMIN || this == OWNER

    val hasFullOwnerPrivileges: Boolean
        get() = this == OWNER
}
