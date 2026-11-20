package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.ReadingProgressDataSource
import com.studentlearning.platform2026.domain.model.FileReadingProgress
import com.studentlearning.platform2026.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ReadingProgressRepositoryImpl(
    private val localDataSource: ReadingProgressDataSource,
    private val dispatcherProvider: DispatcherProvider
) : ReadingProgressRepository {

    override fun observeProgress(fileId: String): Flow<FileReadingProgress?> =
        localDataSource.observeProgress(fileId)

    override suspend fun getProgress(fileId: String): FileReadingProgress? =
        withContext(dispatcherProvider.io) {
            localDataSource.getProgress(fileId)
        }

    override suspend fun saveLastPage(fileId: String, pageNumber: Int, totalPages: Int) {
        withContext(dispatcherProvider.io) {
            localDataSource.saveLastPage(
                fileId = fileId,
                pageNumber = pageNumber,
                totalPages = totalPages
            )
        }
    }

    override suspend fun clearProgress(fileId: String) {
        withContext(dispatcherProvider.io) {
            localDataSource.clearProgress(fileId)
        }
    }
}
