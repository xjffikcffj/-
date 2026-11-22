package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.domain.model.FavoriteStudyFile
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import kotlinx.coroutines.flow.Flow

interface LocalFileOrganizationRepository {
    fun observeFavorites(): Flow<List<FavoriteStudyFile>>
    fun observeFavoriteIds(): Flow<Set<String>>
    suspend fun toggleFavorite(file: StudyFileMetadata)
    suspend fun addFavorite(file: StudyFileMetadata)
    suspend fun removeFavorite(fileId: String)

    fun observeCustomOrder(scopeKey: String): Flow<List<String>>
    suspend fun saveCustomOrder(scopeKey: String, orderedFileIds: List<String>)
    suspend fun clearCustomOrder(scopeKey: String)

    companion object {
        const val SCOPE_DOWNLOADS = "downloads"

        fun sectionScope(sectionId: String): String = "section:${sectionId.trim()}"
    }
}
