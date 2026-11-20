package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.domain.model.FileReadingProgress
import kotlinx.coroutines.flow.Flow

interface ReadingProgressRepository {
    fun observeProgress(fileId: String, versionId: String? = null): Flow<FileReadingProgress?>

    suspend fun getProgress(fileId: String, versionId: String? = null): FileReadingProgress?

    suspend fun saveLastPage(
        fileId: String,
        pageNumber: Int,
        totalPages: Int,
        versionId: String? = null
    )

    suspend fun clearProgress(fileId: String, versionId: String? = null)
}
