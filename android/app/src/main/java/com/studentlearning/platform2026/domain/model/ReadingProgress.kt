package com.studentlearning.platform2026.domain.model

/**
 * Device-local reading progress anchored strictly to the stable [fileId].
 *
 * Intentionally independent of Firebase Auth UIDs, user profiles, or Firestore sync:
 * reading progress lives only on this device and survives account sign-out or switching.
 */
data class FileReadingProgress(
    val fileId: String,
    val lastPageNumber: Int,
    val totalPages: Int = 0,
    val updatedAtEpochMillis: Long = 0L
)
