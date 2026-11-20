package com.studentlearning.platform2026.domain.model

enum class CatalogFailureReason {
    FIREBASE_NOT_CONFIGURED,
    PERMISSION_DENIED,
    NETWORK_UNAVAILABLE,
    UNKNOWN
}

class CatalogException(
    val reason: CatalogFailureReason,
    override val message: String? = null,
    override val cause: Throwable? = null
) : Exception(message, cause)
