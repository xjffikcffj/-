package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.DownloadTaskState
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileOfflineState
import kotlinx.coroutines.flow.Flow

interface OfflineDownloadsRepository {

    fun observeAllSavedVersions(): Flow<List<LocalDownloadedFileVersion>>

    fun observeFileOfflineState(
        fileId: String,
        activeVersionId: String?
    ): Flow<StudyFileOfflineState>

    fun observeSectionOfflineStates(
        files: List<StudyFileMetadata>
    ): Flow<Map<String, StudyFileOfflineState>>

    suspend fun getVerifiedLocalVersion(
        fileId: String,
        versionId: String?
    ): LocalDownloadedFileVersion?

    suspend fun getSavedVersionsForFile(fileId: String): List<LocalDownloadedFileVersion>

    suspend fun getSavedVersionsForSection(sectionId: String): List<LocalDownloadedFileVersion>

    suspend fun startDownload(
        file: StudyFileMetadata,
        expectedFileSizeBytes: Long? = null,
        expectedChecksumSha256: String? = null
    ): AppResult<DownloadTaskState>

    suspend fun startDownload(request: OfflineDownloadRequest): AppResult<DownloadTaskState>

    suspend fun retryDownload(
        file: StudyFileMetadata,
        expectedFileSizeBytes: Long? = null,
        expectedChecksumSha256: String? = null
    ): AppResult<DownloadTaskState>

    suspend fun cancelDownload(fileId: String, versionId: String): AppResult<Unit>

    suspend fun deleteLocalVersion(fileId: String, versionId: String): AppResult<Unit>

    suspend fun cleanupOrphanedTempFiles()
}
