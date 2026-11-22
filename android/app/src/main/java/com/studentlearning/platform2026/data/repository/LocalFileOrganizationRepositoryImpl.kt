package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.LocalFileOrganizationDataSource
import com.studentlearning.platform2026.domain.model.FavoriteStudyFile
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.repository.LocalFileOrganizationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class LocalFileOrganizationRepositoryImpl(
    private val localDataSource: LocalFileOrganizationDataSource,
    private val dispatcherProvider: DispatcherProvider
) : LocalFileOrganizationRepository {

    override fun observeFavorites(): Flow<List<FavoriteStudyFile>> =
        localDataSource.observeFavorites()

    override fun observeFavoriteIds(): Flow<Set<String>> =
        localDataSource.observeFavoriteIds()

    override suspend fun toggleFavorite(file: StudyFileMetadata) {
        withContext(dispatcherProvider.io) {
            localDataSource.toggleFavorite(file)
        }
    }

    override suspend fun addFavorite(file: StudyFileMetadata) {
        withContext(dispatcherProvider.io) {
            localDataSource.addFavorite(file)
        }
    }

    override suspend fun removeFavorite(fileId: String) {
        withContext(dispatcherProvider.io) {
            localDataSource.removeFavorite(fileId)
        }
    }

    override fun observeCustomOrder(scopeKey: String): Flow<List<String>> =
        localDataSource.observeCustomOrder(scopeKey)

    override suspend fun saveCustomOrder(scopeKey: String, orderedFileIds: List<String>) {
        withContext(dispatcherProvider.io) {
            localDataSource.saveCustomOrder(scopeKey, orderedFileIds)
        }
    }

    override suspend fun clearCustomOrder(scopeKey: String) {
        withContext(dispatcherProvider.io) {
            localDataSource.clearCustomOrder(scopeKey)
        }
    }
}
