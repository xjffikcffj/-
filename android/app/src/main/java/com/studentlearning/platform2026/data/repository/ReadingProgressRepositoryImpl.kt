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

    override fun observeProgress(fileId: String, versionId: String?): Flow<FileReadingProgress?> =
        localDataSource.observeProgress(fileId = fileId, versionId = versionId)

    override suspend fun getProgress(fileId: String, versionId: String?): FileReadingProgress? =
        withContext(dispatcherProvider.io) {
            accessMutex.withLock {
                localDataSource.getProgress(fileId = fileId, versionId = versionId)
            }
        }

    override suspend fun saveLastPage(
        fileId: String,
        pageNumber: Int,
        totalPages: Int,
        versionId: String?
    ) {
        withContext(dispatcherProvider.io) {
            accessMutex.withLock {
                localDataSource.saveLastPage(
                    fileId = fileId,
                    pageNumber = pageNumber,
                    totalPages = totalPages,
                    versionId = versionId
                )
            }
        }
    }

    override suspend fun clearProgress(fileId: String, versionId: String?) {
        withContext(dispatcherProvider.io) {
            accessMutex.withLock {
                localDataSource.clearProgress(fileId = fileId, versionId = versionId)
            }
        }
    }
}
