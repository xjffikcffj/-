package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.firebase.CatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogMapper
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class CatalogRepositoryImpl(
    private val remoteDataSource: CatalogRemoteDataSource,
    private val dispatcherProvider: DispatcherProvider
) : CatalogRepository {

    override suspend fun getPublishedBranches(): AppResult<List<StudyBranch>> {
        return executeCatalogRequest {
            remoteDataSource.fetchPublishedBranches()
        }
    }

    override suspend fun getPublishedSubjects(branchId: String): AppResult<List<StudySubject>> {
        return executeCatalogRequest {
            remoteDataSource.fetchPublishedSubjects(branchId)
        }
    }

    override suspend fun getPublishedSections(subjectId: String): AppResult<List<StudySection>> {
        return executeCatalogRequest {
            remoteDataSource.fetchPublishedSections(subjectId)
        }
    }

    override suspend fun getPublishedStudyFiles(sectionId: String): AppResult<List<StudyFileMetadata>> {
        return executeCatalogRequest {
            remoteDataSource.fetchPublishedStudyFiles(sectionId)
        }
    }

    private suspend inline fun <T> executeCatalogRequest(
        crossinline block: suspend () -> T
    ): AppResult<T> = withContext(dispatcherProvider.io) {
        try {
            AppResult.Success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (catalogError: CatalogException) {
            AppResult.Error(
                message = catalogError.message.orEmpty(),
                cause = catalogError
            )
        } catch (throwable: Throwable) {
            val mappedReason = FirestoreCatalogMapper.mapFailureReason(throwable)
            AppResult.Error(
                message = throwable.message.orEmpty(),
                cause = CatalogException(
                    reason = mappedReason,
                    message = throwable.message,
                    cause = throwable
                )
            )
        }
    }
}
