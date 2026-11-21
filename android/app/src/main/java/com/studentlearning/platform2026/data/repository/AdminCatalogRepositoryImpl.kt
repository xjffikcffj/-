package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.firebase.AdminCatalogRemoteDataSource
import com.studentlearning.platform2026.data.remote.firebase.FirestoreAdminCatalogMapper
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.AdminCatalogRepository
import com.studentlearning.platform2026.domain.repository.AuthRepository
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
import com.studentlearning.platform2026.domain.repository.PdfVersionStorageBroker
import com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof
import com.studentlearning.platform2026.domain.validation.AdminCatalogValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class AdminCatalogRepositoryImpl(
    private val authRepository: AuthRepository,
    private val remoteDataSource: AdminCatalogRemoteDataSource,
    private val dispatcherProvider: DispatcherProvider,
    private val pdfVersionStorageBroker: PdfVersionStorageBroker
) : AdminCatalogRepository {

    override suspend fun getAllBranches(): AppResult<List<StudyBranch>> = executeAuthorized {
        remoteDataSource.fetchAllBranches()
    }

    override suspend fun createBranch(
        customId: String?,
        name: String,
        order: Int,
        isPublished: Boolean
    ): AppResult<StudyBranch> = executeOwnerAuthorized { actorUid ->
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
    ): AppResult<Unit> = executeOwnerAuthorized { actorUid ->
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
    ): AppResult<Unit> = executeOwnerAuthorized { actorUid ->
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
    ): AppResult<Unit> {
        val block: suspend (String) -> Unit = { actorUid ->
            val validSubjectId = AdminCatalogValidator.validateParentId(subjectId)
            remoteDataSource.setSubjectPublished(validSubjectId, isPublished, actorUid)
        }
        return if (isPublished) {
            executeAuthorized(block)
        } else {
            // firestore.rules limits subject unpublish (true -> false) to Owner only.
            executeOwnerAuthorized(block)
        }
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
        if (isPublished) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                message = "Cannot create a published study file before publishing a verified active PDF version."
            )
        }
        remoteDataSource.createStudyFileMetadata(
            customId = validated.customId,
            sectionId = validated.sectionId,
            title = validated.title,
            description = validated.description,
            order = validated.order,
            isPublished = false,
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
        val existingFile = remoteDataSource.fetchStudyFileMetadata(validFileId)
            ?: throw AdminException(
                reason = AdminFailureReason.NOT_FOUND,
                message = "Study file '$validFileId' does not exist."
            )
        if (existingFile.isArchiveStatusUnspecified ||
            (!existingFile.isExplicitlyUnarchived && !existingFile.isExplicitlyArchived)
        ) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                message = "Study file '$validFileId' has unspecified archive status and requires review before archiving or restoring."
            )
        }
        remoteDataSource.setStudyFileArchived(validFileId, isArchived, actorUid)
    }

    override suspend fun getStudyFileVersions(fileId: String): AppResult<List<StudyFileVersionMetadata>> =
        executeAuthorized {
            val validFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
            remoteDataSource.fetchStudyFileVersions(validFileId)
        }

    override suspend fun createStudyFileVersion(
        fileId: String,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        publishAsActive: Boolean,
        customVersionId: String?
    ): AppResult<StudyFileVersionMetadata> = executeAuthorized { actorUid ->
        val validated = AdminCatalogValidator.validateStudyFileVersionCreate(
            fileId = fileId,
            versionNumber = versionNumber,
            versionLabel = versionLabel,
            versionNotes = versionNotes,
            customVersionId = customVersionId
        )
        if (publishAsActive) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Cannot publish an active version before uploading and verifying its PDF file on Cloudflare R2."
            )
        }
        remoteDataSource.createStudyFileVersion(
            fileId = validated.fileId,
            versionNumber = validated.versionNumber,
            versionLabel = validated.versionLabel,
            versionNotes = validated.versionNotes,
            publishAsActive = false,
            actorUid = actorUid,
            customVersionId = validated.customVersionId
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
        if (pdfVersionStorageBroker.getBrokerStatus() != PdfBrokerConfigurationStatus.CONFIGURED) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Cloudflare R2 upload and verification service is not ready; cannot publish version without verified PDF binary."
            )
        }
        val verification = pdfVersionStorageBroker.verifyUploadedPdfBinary(
            fileId = validFileId,
            versionId = validVersionId
        )
        val verifiedProof = (verification as? AppResult.Success)?.data
            ?: throw unwrapBrokerError(
                result = verification,
                fallbackMessage = "Cloudflare R2 binary verification failed; cannot publish version without server-verified PDF proof."
            )
        if (verifiedProof.fileId.trim() != validFileId ||
            verifiedProof.versionId.trim() != validVersionId ||
            !AdminCatalogValidator.hasReliableUploadedPdfProof(
                fileId = validFileId,
                versionId = validVersionId,
                r2ObjectKey = verifiedProof.r2ObjectKey,
                fileSizeBytes = verifiedProof.fileSizeBytes,
                checksumSha256 = verifiedProof.checksumSha256
            )
        ) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Server PDF verification returned invalid or mismatched proof for '$validFileId/$validVersionId'."
            )
        }
        remoteDataSource.publishStudyFileVersion(
            fileId = validFileId,
            versionId = validVersionId,
            actorUid = actorUid,
            verifiedProof = verifiedProof
        )
    }

    override suspend fun uploadAndVerifyStudyFileVersionPdf(
        fileId: String,
        versionId: String,
        binarySource: PdfUploadBinarySource
    ): AppResult<VerifiedPdfBinaryProof> = executeAuthorized { _ ->
        val validFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        val validVersionId = AdminCatalogValidator.validateRequiredDocumentId(versionId)
        val normalizedSha256 = binarySource.sha256Hex.trim().lowercase()
        if (binarySource.sizeBytes <= 0L || !SHA256_HEX_REGEX.matches(normalizedSha256)) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_UPLOAD_INVALID_PDF,
                message = "Selected PDF binary has invalid size or SHA-256 checksum."
            )
        }
        if (pdfVersionStorageBroker.getBrokerStatus() != PdfBrokerConfigurationStatus.CONFIGURED) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Cloudflare R2 upload and verification service is not configured yet."
            )
        }

        val slotResult = pdfVersionStorageBroker.requestVersionUploadSlot(
            fileId = validFileId,
            versionId = validVersionId,
            expectedFileSizeBytes = binarySource.sizeBytes,
            expectedChecksumSha256 = normalizedSha256
        )
        val slot = (slotResult as? AppResult.Success)?.data
            ?: throw unwrapBrokerError(
                result = slotResult,
                fallbackMessage = "Failed to request PDF upload slot from Backend D2."
            )

        val putResult = pdfVersionStorageBroker.uploadPdfToSignedSlot(slot, binarySource)
        if (putResult !is AppResult.Success) {
            throw unwrapBrokerError(
                result = putResult,
                fallbackMessage = "Failed streaming PDF binary to Cloudflare R2 staging slot."
            )
        }

        val verifyResult = pdfVersionStorageBroker.verifyUploadedPdfBinary(
            fileId = validFileId,
            versionId = validVersionId,
            uploadSessionId = slot.uploadSessionId
        )
        val proof = (verifyResult as? AppResult.Success)?.data
            ?: throw unwrapBrokerError(
                result = verifyResult,
                fallbackMessage = "Backend PDF verification failed."
            )

        if (proof.fileId.trim() != validFileId ||
            proof.versionId.trim() != validVersionId ||
            proof.fileSizeBytes != binarySource.sizeBytes ||
            !proof.checksumSha256.equals(normalizedSha256, ignoreCase = true) ||
            !AdminCatalogValidator.hasReliableUploadedPdfProof(
                fileId = validFileId,
                versionId = validVersionId,
                r2ObjectKey = proof.r2ObjectKey,
                fileSizeBytes = proof.fileSizeBytes,
                checksumSha256 = proof.checksumSha256
            )
        ) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Backend PDF verification returned mismatched or incomplete proof."
            )
        }
        proof
    }

    override suspend fun reorderBranches(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int
    ): AppResult<Unit> = executeOwnerAuthorized { actorUid ->
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

    private fun unwrapBrokerError(
        result: AppResult<*>,
        fallbackMessage: String
    ): AdminException {
        val cause = (result as? AppResult.Error)?.cause
        return when {
            cause is AdminException -> cause
            cause?.cause is AdminException -> cause.cause as AdminException
            else -> AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = (result as? AppResult.Error)?.message ?: fallbackMessage,
                cause = cause
            )
        }
    }

    private suspend fun <T> executeOwnerAuthorized(
        block: suspend (actorUid: String) -> T
    ): AppResult<T> = executeAuthorizedInternal(requireOwner = true, block = block)

    private suspend fun <T> executeAuthorized(
        block: suspend (actorUid: String) -> T
    ): AppResult<T> = executeAuthorizedInternal(requireOwner = false, block = block)

    private suspend fun <T> executeAuthorizedInternal(
        requireOwner: Boolean,
        block: suspend (actorUid: String) -> T
    ): AppResult<T> {
        return withContext(dispatcherProvider.io) {
            try {
                val session = authRepository.requireAuthorizedAdminSession()
                if (requireOwner && !session.canPerformOwnerOnlyActions) {
                    throw AdminException(
                        reason = AdminFailureReason.INSUFFICIENT_ROLE,
                        message = "This action is restricted to Owner accounts only."
                    )
                }
                val actorUid = session.uid?.trim().orEmpty()
                if (actorUid.isEmpty()) {
                    throw AdminException(
                        reason = AdminFailureReason.UNAUTHENTICATED,
                        message = "Authenticated admin UID is required."
                    )
                }
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

    private companion object {
        val SHA256_HEX_REGEX = Regex("^[a-f0-9]{64}$")
    }
}
