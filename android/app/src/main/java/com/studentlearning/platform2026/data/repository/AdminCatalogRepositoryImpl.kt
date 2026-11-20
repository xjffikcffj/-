package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.firebase.AdminCatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogMapper
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.AdminCatalogRepository
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.validation.AdminCatalogValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class AdminCatalogRepositoryImpl(
    private val authRepository: AuthRepository,
    private val remoteDataSource: AdminCatalogRemoteDataSource,
    private val dispatcherProvider: DispatcherProvider
) : AdminCatalogRepository {

    override suspend fun getAllBranches(): AppResult<List<StudyBranch>> = executeAuthorized {
        remoteDataSource.fetchAllBranches()
    }

    override suspend fun createBranch(
        customId: String?,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudyBranch> = executeAuthorized { actorUid ->
        val validated = AdminCatalogValidator.validateCatalogEntry(customId, name, order)
        remoteDataSource.createBranch(
            customId = validated.customId,
            name = validated.name,
            order = validated.order,
            isPublished = isPublished,
            actorUid = actorUid
        )
    }

    override suspend fun updateBranch(
        branchId: String,
        name: String,
        order: Int
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validBranchId = AdminCatalogValidator.validateParentId(branchId)
        val validated = AdminCatalogValidator.validateCatalogEntry(name = name, order = order)
        remoteDataSource.updateBranch(
            branchId = validBranchId,
            name = validated.name,
            order = validated.order,
            actorUid = actorUid
        )
    }

    override suspend fun setBranchPublished(
        branchId: String,
        isPublished: Boolean
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validBranchId = AdminCatalogValidator.validateParentId(branchId)
        remoteDataSource.setBranchPublished(validBranchId, isPublished, actorUid)
    }

    override suspend fun getAllSubjects(branchId: String): AppResult<List<StudySubject>> = executeAuthorized {
        val validBranchId = AdminCatalogValidator.validateParentId(branchId)
        remoteDataSource.fetchAllSubjects(validBranchId)
    }

    override suspend fun createSubject(
        customId: String?,
        branchId: String,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudySubject> = executeAuthorized { actorUid ->
        val validBranchId = AdminCatalogValidator.validateParentId(branchId)
        val validated = AdminCatalogValidator.validateCatalogEntry(customId, name, order)
        remoteDataSource.createSubject(
            customId = validated.customId,
            branchId = validBranchId,
            name = validated.name,
            order = validated.order,
            isPublished = isPublished,
            actorUid = actorUid
        )
    }

    override suspend fun updateSubject(
        subjectId: String,
        name: String,
        order: Int
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validSubjectId = AdminCatalogValidator.validateParentId(subjectId)
        val validated = AdminCatalogValidator.validateCatalogEntry(name = name, order = order)
        remoteDataSource.updateSubject(
            subjectId = validSubjectId,
            name = validated.name,
            order = validated.order,
            actorUid = actorUid
        )
    }

    override suspend fun setSubjectPublished(
        subjectId: String,
        isPublished: Boolean
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validSubjectId = AdminCatalogValidator.validateParentId(subjectId)
        remoteDataSource.setSubjectPublished(validSubjectId, isPublished, actorUid)
    }

    override suspend fun getAllSections(subjectId: String): AppResult<List<StudySection>> = executeAuthorized {
        val validSubjectId = AdminCatalogValidator.validateParentId(subjectId)
        remoteDataSource.fetchAllSections(validSubjectId)
    }

    override suspend fun createSection(
        customId: String?,
        subjectId: String,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudySection> = executeAuthorized { actorUid ->
        val validSubjectId = AdminCatalogValidator.validateParentId(subjectId)
        val validated = AdminCatalogValidator.validateCatalogEntry(customId, name, order)
        remoteDataSource.createSection(
            customId = validated.customId,
            subjectId = validSubjectId,
            name = validated.name,
            order = validated.order,
            isPublished = isPublished,
            actorUid = actorUid
        )
    }

    override suspend fun updateSection(
        sectionId: String,
        name: String,
        order: Int
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validSectionId = AdminCatalogValidator.validateParentId(sectionId)
        val validated = AdminCatalogValidator.validateCatalogEntry(name = name, order = order)
        remoteDataSource.updateSection(
            sectionId = validSectionId,
            name = validated.name,
            order = validated.order,
            actorUid = actorUid
        )
    }

    override suspend fun setSectionPublished(
        sectionId: String,
        isPublished: Boolean
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validSectionId = AdminCatalogValidator.validateParentId(sectionId)
        remoteDataSource.setSectionPublished(validSectionId, isPublished, actorUid)
    }

    override suspend fun getStudyFilesForSection(sectionId: String): AppResult<List<StudyFileMetadata>> =
        executeAuthorized {
            val validSectionId = AdminCatalogValidator.validateParentId(sectionId)
            remoteDataSource.fetchStudyFilesForSection(validSectionId)
        }

    override suspend fun createStudyFileMetadata(
        customId: String?,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudyFileMetadata> = executeAuthorized { actorUid ->
        val validated = AdminCatalogValidator.validateStudyFileCreate(
            customId = customId,
            sectionId = sectionId,
            title = title,
            description = description,
            order = order
        )
        remoteDataSource.createStudyFileMetadata(
            customId = validated.customId,
            sectionId = validated.sectionId,
            title = validated.title,
            description = validated.description,
            order = validated.order,
            isPublished = isPublished,
            actorUid = actorUid
        )
    }

    override suspend fun updateStudyFileMetadata(
        fileId: String,
        sectionId: String,
        title: String,
        description: String?,
        order: Int
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validated = AdminCatalogValidator.validateStudyFileUpdate(
            fileId = fileId,
            sectionId = sectionId,
            title = title,
            description = description,
            order = order
        )
        remoteDataSource.updateStudyFileMetadata(
            fileId = validated.fileId,
            sectionId = validated.sectionId,
            title = validated.title,
            description = validated.description,
            order = validated.order,
            actorUid = actorUid
        )
    }

    override suspend fun setStudyFilePublished(
        fileId: String,
        isPublished: Boolean
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        remoteDataSource.setStudyFilePublished(validFileId, isPublished, actorUid)
    }

    override suspend fun setStudyFileArchived(
        fileId: String,
        isArchived: Boolean
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        remoteDataSource.setStudyFileArchived(validFileId, isArchived, actorUid)
    }

    override suspend fun getStudyFileVersions(fileId: String): AppResult<List<StudyFileVersionMetadata>> =
        executeAuthorized {
            val validFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
            remoteDataSource.fetchStudyFileVersions(validFileId)
        }

    override suspend fun createStudyFileVersion(
        customId: String?,
        fileId: String,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        publishImmediately: Boolean
    ): AppResult<StudyFileVersionMetadata> = executeAuthorized { actorUid ->
        val validated = AdminCatalogValidator.validateStudyFileVersionCreate(
            customId = customId,
            fileId = fileId,
            versionNumber = versionNumber,
            versionLabel = versionLabel,
            versionNotes = versionNotes
        )
        remoteDataSource.createStudyFileVersion(
            customId = validated.customId,
            fileId = validated.fileId,
            versionNumber = validated.versionNumber,
            versionLabel = validated.versionLabel,
            versionNotes = validated.versionNotes,
            publishImmediately = publishImmediately,
            actorUid = actorUid
        )
    }

    override suspend fun updateStudyFileVersionMetadata(
        fileId: String,
        versionId: String,
        versionLabel: String,
        versionNotes: String?
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validated = AdminCatalogValidator.validateStudyFileVersionUpdate(
            fileId = fileId,
            versionId = versionId,
            versionLabel = versionLabel,
            versionNotes = versionNotes
        )
        remoteDataSource.updateStudyFileVersionMetadata(
            fileId = validated.fileId,
            versionId = validated.versionId,
            versionLabel = validated.versionLabel,
            versionNotes = validated.versionNotes,
            actorUid = actorUid
        )
    }

    override suspend fun publishStudyFileVersion(
        fileId: String,
        versionId: String
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        val validVersionId = AdminCatalogValidator.validateRequiredDocumentId(versionId)
        remoteDataSource.publishStudyFileVersion(
            fileId = validFileId,
            versionId = validVersionId,
            actorUid = actorUid
        )
    }

    override suspend fun reorderBranches(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validFirstId = AdminCatalogValidator.validateRequiredDocumentId(firstId)
        val validSecondId = AdminCatalogValidator.validateRequiredDocumentId(secondId)
        val validFirstOrder = AdminCatalogValidator.validateNonNegativeOrder(firstNewOrder)
        val validSecondOrder = AdminCatalogValidator.validateNonNegativeOrder(secondNewOrder)
        remoteDataSource.reorderBranches(validFirstId, validFirstOrder, validSecondId, validSecondOrder, actorUid)
    }

    override suspend fun reorderSubjects(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validFirstId = AdminCatalogValidator.validateRequiredDocumentId(firstId)
        val validSecondId = AdminCatalogValidator.validateRequiredDocumentId(secondId)
        val validFirstOrder = AdminCatalogValidator.validateNonNegativeOrder(firstNewOrder)
        val validSecondOrder = AdminCatalogValidator.validateNonNegativeOrder(secondNewOrder)
        remoteDataSource.reorderSubjects(validFirstId, validFirstOrder, validSecondId, validSecondOrder, actorUid)
    }

    override suspend fun reorderSections(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validFirstId = AdminCatalogValidator.validateRequiredDocumentId(firstId)
        val validSecondId = AdminCatalogValidator.validateRequiredDocumentId(secondId)
        val validFirstOrder = AdminCatalogValidator.validateNonNegativeOrder(firstNewOrder)
        val validSecondOrder = AdminCatalogValidator.validateNonNegativeOrder(secondNewOrder)
        remoteDataSource.reorderSections(validFirstId, validFirstOrder, validSecondId, validSecondOrder, actorUid)
    }

    override suspend fun reorderStudyFiles(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> = executeAuthorized { actorUid ->
        val validFirstId = AdminCatalogValidator.validateRequiredDocumentId(firstId)
        val validSecondId = AdminCatalogValidator.validateRequiredDocumentId(secondId)
        val validFirstOrder = AdminCatalogValidator.validateNonNegativeOrder(firstNewOrder)
        val validSecondOrder = AdminCatalogValidator.validateNonNegativeOrder(secondNewOrder)
        remoteDataSource.reorderStudyFiles(validFirstId, validFirstOrder, validSecondId, validSecondOrder, actorUid)
    }

    private suspend fun <T> executeAuthorized(
        block: suspend (actorUid: String) -> T
    ): AppResult<T> {
        return withContext(dispatcherProvider.io) {
            try {
                val session = authRepository.requireAuthorizedAdminSession()
                val actorUid = session.uid.orEmpty()
                AppResult.Success(block(actorUid))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val adminException = when {
                    throwable is AdminException -> throwable
                    throwable.cause is AdminException -> throwable.cause as AdminException
                    else -> AdminException(
                        reason = FirestoreAdminCatalogMapper.mapThrowableToAdminReason(throwable),
                        message = throwable.localizedMessage,
                        cause = throwable
                    )
                }
                AppResult.Error(
                    message = adminException.message ?: "Admin catalog operation failed",
                    cause = adminException
                )
            }
        }
    }
}
