package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.ReadingProgressDataSource
import com.studentlearning.platform2026.domain.model.FileReadingProgress
import com.studentlearning.platform2026.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReadingProgressRepositoryImpl(
    private val localDataSource: ReadingProgressDataSource,
    private val dispatcherProvider: DispatcherProvider
) : ReadingProgressRepository {

    private val accessMutex = Mutex()

    override fun observeProgress(fileId: String): Flow<FileReadingProgress?> =
        localDataSource.observeProgress(fileId)

    override suspend fun getProgress(fileId: String): FileReadingProgress? =
        withContext(dispatcherProvider.io) {
            accessMutex.withLock {
                localDataSource.getProgress(fileId)
            }
        }

    override suspend fun saveLastPage(fileId: String, pageNumber: Int, totalPages: Int) {
        withContext(dispatcherProvider.io) {
            accessMutex.withLock {
                localDataSource.saveLastPage(
                    fileId = fileId,
                    pageNumber = pageNumber,
                    totalPages = totalPages
                )
            }
        }
    }

    override suspend fun clearProgress(fileId: String) {
        withContext(dispatcherProvider.io) {
            accessMutex.withLock {
                localDataSource.clearProgress(fileId)
            }
        }
    }
}
