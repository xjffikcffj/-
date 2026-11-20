package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.domain.model.FileReadingProgress
import kotlinx.coroutines.flow.Flow

interface ReadingProgressRepository {
    fun observeProgress(fileId: String): Flow<FileReadingProgress?>

    suspend fun getProgress(fileId: String): FileReadingProgress?

    suspend fun saveLastPage(fileId: String, pageNumber: Int, totalPages: Int)

    suspend fun clearProgress(fileId: String)
}
