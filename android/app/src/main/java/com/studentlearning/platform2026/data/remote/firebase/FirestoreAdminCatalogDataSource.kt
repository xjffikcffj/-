package com.studentlearning.platform2026.data.remote.firebase

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Collections
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Defaults
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Fields
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Subcollections
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.model.AdminValidationError
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudyFileVersionMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.domain.repository.VerifiedPdfBinaryProof
import com.studentlearning.platform2026.domain.validation.AdminCatalogValidator

interface AdminCatalogRemoteDataSource {
    suspend fun fetchAllBranches(): List<StudyBranch>
    suspend fun createBranch(
        customId: String?,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudyBranch
    suspend fun updateBranch(branchId: String, name: String, order: Int, actorUid: String)
    suspend fun setBranchPublished(branchId: String, isPublished: Boolean, actorUid: String)

    suspend fun fetchAllSubjects(branchId: String): List<StudySubject>
    suspend fun createSubject(
        customId: String?,
        branchId: String,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudySubject
    suspend fun updateSubject(subjectId: String, name: String, order: Int, actorUid: String)
    suspend fun setSubjectPublished(subjectId: String, isPublished: Boolean, actorUid: String)

    suspend fun fetchAllSections(subjectId: String): List<StudySection>
    suspend fun createSection(
        customId: String?,
        subjectId: String,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudySection
    suspend fun updateSection(sectionId: String, name: String, order: Int, actorUid: String)
    suspend fun setSectionPublished(sectionId: String, isPublished: Boolean, actorUid: String)

    suspend fun fetchStudyFilesForSection(sectionId: String): List<StudyFileMetadata>
    suspend fun fetchStudyFileMetadata(fileId: String): StudyFileMetadata? = null
    suspend fun createStudyFileMetadata(
        customId: String?,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudyFileMetadata
    suspend fun updateStudyFileMetadata(
        fileId: String,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        actorUid: String
    )
    suspend fun setStudyFilePublished(fileId: String, isPublished: Boolean, actorUid: String)
    suspend fun setStudyFileArchived(fileId: String, isArchived: Boolean, actorUid: String)

    suspend fun fetchStudyFileVersions(fileId: String): List<StudyFileVersionMetadata>
    suspend fun createStudyFileVersion(
        fileId: String,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        publishAsActive: Boolean,
        actorUid: String,
        customVersionId: String? = null
    ): StudyFileVersionMetadata
    suspend fun updateStudyFileVersionMetadata(
        fileId: String,
        versionId: String,
        versionLabel: String,
        versionNotes: String?,
        actorUid: String
    )
    suspend fun publishStudyFileVersion(
        fileId: String,
        versionId: String,
        actorUid: String,
        verifiedProof: VerifiedPdfBinaryProof
    )

    suspend fun reorderBranches(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    )
    suspend fun reorderSubjects(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    )
    suspend fun reorderSections(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    )
    suspend fun reorderStudyFiles(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    )
}

internal data class CatalogDocSnapshot(
    val id: String,
    val exists: Boolean,
    val data: Map<String, Any?>,
    val path: String
)

internal interface AdminCatalogTransactionScope {
    fun get(docPath: String): CatalogDocSnapshot
    fun set(docPath: String, payload: Map<String, Any?>)
    fun update(docPath: String, payload: Map<String, Any?>)
}

internal interface AdminCatalogFirestoreClient {
    fun serverTimestamp(): Any
    fun documentRef(docPath: String): Any
    fun newDocumentId(collectionPath: String): String
    suspend fun listCollectionDocPaths(collectionPath: String): List<String>
    suspend fun <T> runTransaction(block: (AdminCatalogTransactionScope) -> T): T
}

private class FirebaseAdminCatalogFirestoreClient(
    private val firestoreProvider: FirestoreProvider
) : AdminCatalogFirestoreClient {

    override fun serverTimestamp(): Any = FieldValue.serverTimestamp()

    override fun documentRef(docPath: String): Any =
        requireConfiguredFirestore().document(docPath)

    override fun newDocumentId(collectionPath: String): String =
        requireConfiguredFirestore().collection(collectionPath).document().id

    override suspend fun listCollectionDocPaths(collectionPath: String): List<String> {
        val firestore = requireConfiguredFirestore()
        return firestore.collection(collectionPath)
            .get()
            .awaitResult()
            .documents
            .map { it.reference.path }
    }

    override suspend fun <T> runTransaction(block: (AdminCatalogTransactionScope) -> T): T {
        val firestore = requireConfiguredFirestore()
        return firestore.runTransaction { transaction ->
            val scope = object : AdminCatalogTransactionScope {
                override fun get(docPath: String): CatalogDocSnapshot {
                    val snap = transaction.get(firestore.document(docPath))
                    return CatalogDocSnapshot(
                        id = snap.id,
                        exists = snap.exists(),
                        data = snap.data ?: emptyMap(),
                        path = snap.reference.path
                    )
                }

                override fun set(docPath: String, payload: Map<String, Any?>) {
                    transaction.set(firestore.document(docPath), payload)
                }

                override fun update(docPath: String, payload: Map<String, Any?>) {
                    transaction.update(firestore.document(docPath), payload)
                }
            }
            block(scope)
        }.awaitResult()
    }

    private fun requireConfiguredFirestore(): FirebaseFirestore {
        return firestoreProvider.getFirestoreOrNull()
            ?: throw AdminException(
                reason = AdminFailureReason.FIREBASE_NOT_CONFIGURED,
                message = "Firebase is not configured locally yet."
            )
    }
}

class FirestoreAdminCatalogDataSource internal constructor(
    private val firestoreProvider: FirestoreProvider,
    private val firestoreClient: AdminCatalogFirestoreClient,
    private val actorRoleResolver: (suspend () -> String?)? = null
) : AdminCatalogRemoteDataSource {

    constructor(
        firestoreProvider: FirestoreProvider,
        actorRoleResolver: (suspend () -> String?)? = null
    ) : this(
        firestoreProvider = firestoreProvider,
        firestoreClient = FirebaseAdminCatalogFirestoreClient(firestoreProvider),
        actorRoleResolver = actorRoleResolver
    )

    override suspend fun fetchAllBranches(): List<StudyBranch> {
        val firestore = requireConfiguredFirestore()
        val snapshot = firestore.collection(Collections.BRANCHES)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { it.id to (it.data ?: emptyMap()) }
        return FirestoreAdminCatalogMapper.mapBranchesForAdmin(rawDocuments)
    }

    override suspend fun createBranch(
        customId: String?,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudyBranch {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val payload = FirestoreAdminCatalogMapper.buildBranchCreatePayload(
            name = name,
            order = order,
            isPublished = isPublished,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        val docRef = createDocumentAtomically(firestore, Collections.BRANCHES, customId, payload)

        return StudyBranch(
            id = docRef.id,
            name = name,
            order = order,
            isPublished = isPublished,
            referencePath = docRef.path
        )
    }

    override suspend fun updateBranch(branchId: String, name: String, order: Int, actorUid: String) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val payload = FirestoreAdminCatalogMapper.buildCatalogItemUpdatePayload(
            name = name,
            order = order,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        firestore.collection(Collections.BRANCHES)
            .document(branchId)
            .update(payload)
            .awaitResult()
    }

    override suspend fun setBranchPublished(branchId: String, isPublished: Boolean, actorUid: String) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val payload = FirestoreAdminCatalogMapper.buildPublishStateUpdatePayload(
            isPublished = isPublished,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        firestore.collection(Collections.BRANCHES)
            .document(branchId)
            .update(payload)
            .awaitResult()
    }

    override suspend fun fetchAllSubjects(branchId: String): List<StudySubject> {
        if (branchId.isBlank()) return emptyList()

        val firestore = requireConfiguredFirestore()
        val branchReference: DocumentReference = firestore
            .collection(Collections.BRANCHES)
            .document(branchId)

        val snapshot = firestore.collection(Collections.SUBJECTS)
            .whereEqualTo(Fields.BRANCH_ID, branchReference)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { it.id to (it.data ?: emptyMap()) }
        return FirestoreAdminCatalogMapper.mapSubjectsForAdmin(rawDocuments, expectedBranchId = branchId)
    }

    override suspend fun createSubject(
        customId: String?,
        branchId: String,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudySubject {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val branchReference = firestore.collection(Collections.BRANCHES).document(branchId)
        val payload = FirestoreAdminCatalogMapper.buildSubjectCreatePayload(
            branchReference = branchReference,
            name = name,
            order = order,
            isPublished = isPublished,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        val docRef = createDocumentAtomically(firestore, Collections.SUBJECTS, customId, payload)

        return StudySubject(
            id = docRef.id,
            branchId = branchId,
            name = name,
            order = order,
            isPublished = isPublished,
            branchReferencePath = branchReference.path
        )
    }

    override suspend fun updateSubject(subjectId: String, name: String, order: Int, actorUid: String) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val payload = FirestoreAdminCatalogMapper.buildCatalogItemUpdatePayload(
            name = name,
            order = order,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        firestore.collection(Collections.SUBJECTS)
            .document(subjectId)
            .update(payload)
            .awaitResult()
    }

    override suspend fun setSubjectPublished(subjectId: String, isPublished: Boolean, actorUid: String) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val payload = FirestoreAdminCatalogMapper.buildPublishStateUpdatePayload(
            isPublished = isPublished,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        firestore.collection(Collections.SUBJECTS)
            .document(subjectId)
            .update(payload)
            .awaitResult()
    }

    override suspend fun fetchAllSections(subjectId: String): List<StudySection> {
        if (subjectId.isBlank()) return emptyList()

        val firestore = requireConfiguredFirestore()
        val subjectReference: DocumentReference = firestore
            .collection(Collections.SUBJECTS)
            .document(subjectId)

        val snapshot = firestore.collection(Collections.SECTIONS)
            .whereEqualTo(Fields.SUBJECT_ID, subjectReference)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { it.id to (it.data ?: emptyMap()) }
        return FirestoreAdminCatalogMapper.mapSectionsForAdmin(rawDocuments, expectedSubjectId = subjectId)
    }

    override suspend fun createSection(
        customId: String?,
        subjectId: String,
        name: String,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudySection {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val subjectReference = firestore.collection(Collections.SUBJECTS).document(subjectId)
        val payload = FirestoreAdminCatalogMapper.buildSectionCreatePayload(
            subjectReference = subjectReference,
            name = name,
            order = order,
            isPublished = isPublished,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        val docRef = createDocumentAtomically(firestore, Collections.SECTIONS, customId, payload)

        return StudySection(
            id = docRef.id,
            subjectId = subjectId,
            name = name,
            order = order,
            isPublished = isPublished,
            subjectReferencePath = subjectReference.path
        )
    }

    override suspend fun updateSection(sectionId: String, name: String, order: Int, actorUid: String) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val payload = FirestoreAdminCatalogMapper.buildCatalogItemUpdatePayload(
            name = name,
            order = order,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        firestore.collection(Collections.SECTIONS)
            .document(sectionId)
            .update(payload)
            .awaitResult()
    }

    override suspend fun setSectionPublished(sectionId: String, isPublished: Boolean, actorUid: String) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val payload = FirestoreAdminCatalogMapper.buildPublishStateUpdatePayload(
            isPublished = isPublished,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        firestore.collection(Collections.SECTIONS)
            .document(sectionId)
            .update(payload)
            .awaitResult()
    }

    override suspend fun fetchStudyFilesForSection(sectionId: String): List<StudyFileMetadata> {
        if (sectionId.isBlank()) return emptyList()

        val firestore = requireConfiguredFirestore()
        val sectionReference: DocumentReference = firestore
            .collection(Collections.SECTIONS)
            .document(sectionId)

        val snapshot = firestore.collection(Collections.STUDY_FILES)
            .whereEqualTo(Fields.SECTION_ID, sectionReference)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { it.id to (it.data ?: emptyMap()) }
        return FirestoreAdminCatalogMapper.mapStudyFilesForAdmin(rawDocuments, expectedSectionId = sectionId)
    }

    override suspend fun fetchStudyFileMetadata(fileId: String): StudyFileMetadata? {
        val cleanFileId = fileId.trim()
        if (cleanFileId.isEmpty()) return null

        val firestore = requireConfiguredFirestore()
        val fileSnap = firestore.collection(Collections.STUDY_FILES)
            .document(cleanFileId)
            .get()
            .awaitResult()
        if (!fileSnap.exists()) {
            throw AdminException(
                reason = AdminFailureReason.NOT_FOUND,
                message = "Study file '$cleanFileId' does not exist."
            )
        }
        val data = fileSnap.data ?: emptyMap()
        val sectionRef = FirestoreCatalogMapper.extractReferenceTarget(data[Fields.SECTION_ID])
            ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.EMPTY_PARENT_REFERENCE,
                message = "Study file '$cleanFileId' is missing a valid sectionId reference."
            )
        return FirestoreAdminCatalogMapper.mapStudyFileForAdmin(
            id = fileSnap.id,
            data = data,
            expectedSectionId = sectionRef.documentId
        )
    }

    override suspend fun createStudyFileMetadata(
        customId: String?,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        isPublished: Boolean,
        actorUid: String
    ): StudyFileMetadata {
        val validActorUid = requireNonBlankActorUid(actorUid)
        if (isPublished) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.STUDY_FILE_NO_ACTIVE_VERSION,
                message = "Cannot create a published study file without an active verified PDF version."
            )
        }
        val firestore = requireConfiguredFirestore()
        val sectionReference = firestore.collection(Collections.SECTIONS).document(sectionId)
        val payload = FirestoreAdminCatalogMapper.buildStudyFileCreatePayload(
            sectionReference = sectionReference,
            title = title,
            description = description,
            order = order,
            isPublished = isPublished,
            actorUid = validActorUid,
            timestampValue = FieldValue.serverTimestamp()
        )
        val docRef = createDocumentAtomically(firestore, Collections.STUDY_FILES, customId, payload)

        return StudyFileMetadata(
            id = docRef.id,
            sectionId = sectionId,
            sectionReferencePath = sectionReference.path,
            title = title,
            description = description,
            order = order,
            isPublished = isPublished,
            archiveStatus = ArchiveStatus.UNARCHIVED,
            mimeType = Defaults.MIME_TYPE_PDF,
            storageProvider = Defaults.STORAGE_PROVIDER_CLOUDFLARE_R2,
            activeVersionId = null,
            activeVersionNumber = null,
            activeVersionLabel = null,
            createdByUid = validActorUid,
            updatedByUid = validActorUid
        )
    }

    override suspend fun updateStudyFileMetadata(
        fileId: String,
        sectionId: String,
        title: String,
        description: String?,
        order: Int,
        actorUid: String
    ) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val cleanFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        val cleanSectionId = AdminCatalogValidator.validateRequiredDocumentId(sectionId)
        val expectedSectionPath = "${Collections.SECTIONS}/$cleanSectionId"
        val filePath = "${Collections.STUDY_FILES}/$cleanFileId"
        val payload = FirestoreAdminCatalogMapper.buildStudyFileUpdatePayload(
            title = title,
            description = description,
            order = order,
            actorUid = validActorUid,
            timestampValue = firestoreClient.serverTimestamp()
        )

        // Verify atomically that the file exists and belongs to the expected section,
        // without altering activeVersionId or any version subcollection document.
        firestoreClient.runTransaction { transaction ->
            val snapshot = transaction.get(filePath)
            if (!snapshot.exists) {
                throw AdminException(
                    reason = AdminFailureReason.NOT_FOUND,
                    message = "Study file '$cleanFileId' does not exist."
                )
            }
            val currentSectionRef = FirestoreCatalogMapper.extractReferenceTarget(snapshot.data[Fields.SECTION_ID])
            if (currentSectionRef?.path != expectedSectionPath) {
                throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.EMPTY_PARENT_REFERENCE,
                    message = "Study file '$cleanFileId' does not belong to section '$cleanSectionId'."
                )
            }
            transaction.update(filePath, payload)
            null
        }
    }

    override suspend fun setStudyFilePublished(fileId: String, isPublished: Boolean, actorUid: String) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val cleanFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        val filePath = "${Collections.STUDY_FILES}/$cleanFileId"
        val versionsPath = "$filePath/${Subcollections.VERSIONS}"
        val payload = FirestoreAdminCatalogMapper.buildPublishStateUpdatePayload(
            isPublished = isPublished,
            actorUid = validActorUid,
            timestampValue = firestoreClient.serverTimestamp()
        )
        firestoreClient.runTransaction { transaction ->
            val fileSnap = transaction.get(filePath)
            val activeVersionId = (fileSnap.data[Fields.ACTIVE_VERSION_ID] as? String)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val activeVersionNumber = (fileSnap.data[Fields.ACTIVE_VERSION_NUMBER] as? Number)
                ?.toInt()
                ?.takeIf { it >= 1 }
            val mappedActiveVersion = if (isPublished && fileSnap.exists && activeVersionId != null) {
                val activeVersionSnap = transaction.get("$versionsPath/$activeVersionId")
                if (activeVersionSnap.exists) {
                    FirestoreAdminCatalogMapper.mapStudyFileVersionForAdmin(
                        id = activeVersionSnap.id,
                        data = activeVersionSnap.data,
                        expectedFileId = cleanFileId
                    )
                } else {
                    null
                }
            } else {
                null
            }
            val fileArchiveStatus = if (fileSnap.exists) {
                ArchiveStatus.fromIsArchivedValue(fileSnap.data[Fields.IS_ARCHIVED])
            } else {
                null
            }
            FirestoreAdminCatalogMapper.validateStudyFilePublishTransition(
                fileId = cleanFileId,
                exists = fileSnap.exists,
                archiveStatus = fileArchiveStatus,
                targetPublished = isPublished,
                activeVersionId = activeVersionId,
                activeVersionNumber = activeVersionNumber,
                activeVersion = mappedActiveVersion
            )
            transaction.update(filePath, payload)
            null
        }
    }

    override suspend fun setStudyFileArchived(fileId: String, isArchived: Boolean, actorUid: String) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val cleanFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        val filePath = "${Collections.STUDY_FILES}/$cleanFileId"
        val payload = FirestoreAdminCatalogMapper.buildArchiveStateUpdatePayload(
            isArchived = isArchived,
            actorUid = validActorUid,
            timestampValue = firestoreClient.serverTimestamp()
        )
        firestoreClient.runTransaction { transaction ->
            val snapshot = transaction.get(filePath)
            if (!snapshot.exists) {
                throw AdminException(
                    reason = AdminFailureReason.NOT_FOUND,
                    message = "Study file '$cleanFileId' does not exist."
                )
            }
            when (ArchiveStatus.fromIsArchivedValue(snapshot.data[Fields.IS_ARCHIVED])) {
                ArchiveStatus.UNSPECIFIED -> throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                    message = "Study file '$cleanFileId' has unspecified archive status and cannot be archived or restored automatically."
                )
                ArchiveStatus.UNARCHIVED,
                ArchiveStatus.ARCHIVED -> Unit
            }
            // Writes after reads: sets isArchived and forces isPublished = false atomically,
            // preserving version documents, activeVersion pointers, and student local files.
            transaction.update(filePath, payload)
            null
        }
    }

    override suspend fun fetchStudyFileVersions(fileId: String): List<StudyFileVersionMetadata> {
        if (fileId.isBlank()) return emptyList()

        val firestore = requireConfiguredFirestore()
        val fileRef = firestore.collection(Collections.STUDY_FILES).document(fileId)
        val fileSnap = fileRef.get().awaitResult()
        if (!fileSnap.exists()) {
            throw AdminException(
                reason = AdminFailureReason.NOT_FOUND,
                message = "Study file '$fileId' does not exist."
            )
        }

        val snapshot = fileRef.collection(Subcollections.VERSIONS)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { it.id to (it.data ?: emptyMap()) }
        return FirestoreAdminCatalogMapper.mapStudyFileVersionsForAdmin(
            documents = rawDocuments,
            expectedFileId = fileId
        )
    }

    override suspend fun createStudyFileVersion(
        fileId: String,
        versionNumber: Int,
        versionLabel: String,
        versionNotes: String?,
        publishAsActive: Boolean,
        actorUid: String,
        customVersionId: String?
    ): StudyFileVersionMetadata {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val cleanFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        if (publishAsActive) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Cannot publish an active version before its PDF binary is uploaded to Cloudflare R2."
            )
        }

        val filePath = "${Collections.STUDY_FILES}/$cleanFileId"
        val versionsPath = "$filePath/${Subcollections.VERSIONS}"
        val targetVersionId = customVersionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: firestoreClient.newDocumentId(versionsPath)
        val newVersionPath = "$versionsPath/$targetVersionId"

        // Pre-fetched paths are ONLY a legacy fallback for docs written before registeredVersionNumbers;
        // atomic uniqueness is enforced inside the transaction via filePath[REGISTERED_VERSION_NUMBERS],
        // because reading and writing filePath serializes concurrent transactions for the same file.
        val legacyVersionPaths = firestoreClient.listCollectionDocPaths(versionsPath)

        val timestamp = firestoreClient.serverTimestamp()
        val createPayload = FirestoreAdminCatalogMapper.buildStudyFileVersionCreatePayload(
            fileReference = firestoreClient.documentRef(filePath),
            versionNumber = versionNumber,
            versionLabel = versionLabel,
            versionNotes = versionNotes,
            isPublished = false,
            actorUid = validActorUid,
            timestampValue = timestamp
        )

        firestoreClient.runTransaction { transaction ->
            val fileSnap = transaction.get(filePath)
            if (!fileSnap.exists) {
                throw AdminException(
                    reason = AdminFailureReason.NOT_FOUND,
                    message = "Study file '$cleanFileId' does not exist."
                )
            }
            when (ArchiveStatus.fromIsArchivedValue(fileSnap.data[Fields.IS_ARCHIVED])) {
                ArchiveStatus.ARCHIVED -> throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION,
                    message = "Cannot create version for archived study file '$cleanFileId'."
                )
                ArchiveStatus.UNSPECIFIED -> throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                    message = "Study file '$cleanFileId' has unspecified archive status."
                )
                ArchiveStatus.UNARCHIVED -> Unit
            }

            val candidateSnap = transaction.get(newVersionPath)
            if (candidateSnap.exists) {
                throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.INVALID_DOCUMENT_ID,
                    message = "A version with ID '$targetVersionId' already exists."
                )
            }

            val pathsToRead = buildList {
                addAll(legacyVersionPaths)
                val activeId = (fileSnap.data[Fields.ACTIVE_VERSION_ID] as? String)?.trim().orEmpty()
                if (activeId.isNotEmpty()) {
                    add("$versionsPath/$activeId")
                }
            }.distinct()

            val existingSnapshots = pathsToRead
                .filter { it != newVersionPath }
                .map { transaction.get(it) }

            val registeredOnParent = FirestoreAdminCatalogMapper.extractRegisteredVersionNumbers(
                fileSnap.data
            )
            val numbersFromDocs = existingSnapshots
                .filter { it.exists }
                .mapNotNull { (it.data[Fields.VERSION_NUMBER] as? Number)?.toInt()?.takeIf { n -> n >= 1 } }
            val knownNumbers = (registeredOnParent + numbersFromDocs).toSet()

            if (versionNumber in knownNumbers) {
                throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.INVALID_VERSION_NUMBER,
                    message = "Version number $versionNumber already exists for file '$cleanFileId'."
                )
            }

            // Writes after all reads: atomically reserve versionNumber on filePath and create draft version.
            transaction.set(newVersionPath, createPayload)
            transaction.update(
                filePath,
                mapOf(
                    Fields.REGISTERED_VERSION_NUMBERS to (knownNumbers + versionNumber).sorted(),
                    Fields.UPDATED_BY_UID to validActorUid,
                    Fields.UPDATED_AT to timestamp
                )
            )
            null
        }

        return StudyFileVersionMetadata(
            id = targetVersionId,
            fileId = cleanFileId,
            versionNumber = versionNumber,
            versionLabel = versionLabel,
            versionNotes = versionNotes,
            r2ObjectKey = null,
            fileSizeBytes = null,
            checksumSha256 = null,
            isPublished = false,
            createdByUid = validActorUid,
            fileReferencePath = filePath,
            updatedByUid = validActorUid,
            archiveStatus = ArchiveStatus.UNARCHIVED
        )
    }

    override suspend fun updateStudyFileVersionMetadata(
        fileId: String,
        versionId: String,
        versionLabel: String,
        versionNotes: String?,
        actorUid: String
    ) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val fileRef = firestore.collection(Collections.STUDY_FILES).document(fileId)
        val versionRef = fileRef.collection(Subcollections.VERSIONS).document(versionId)
        val timestamp = FieldValue.serverTimestamp()
        val updatePayload = FirestoreAdminCatalogMapper.buildStudyFileVersionMetadataUpdatePayload(
            versionLabel = versionLabel,
            versionNotes = versionNotes,
            actorUid = validActorUid,
            timestampValue = timestamp
        )

        firestore.runTransaction { transaction ->
            val fileSnap = transaction.get(fileRef)
            if (!fileSnap.exists()) {
                throw AdminException(
                    reason = AdminFailureReason.NOT_FOUND,
                    message = "Study file '$fileId' does not exist."
                )
            }
            val versionSnap = transaction.get(versionRef)
            if (!versionSnap.exists()) {
                throw AdminException(
                    reason = AdminFailureReason.NOT_FOUND,
                    message = "Study file version '$versionId' does not exist."
                )
            }
            val parentRef = versionSnap.getDocumentReference(Fields.FILE_ID)
            if (parentRef?.path != fileRef.path) {
                throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.EMPTY_PARENT_REFERENCE,
                    message = "Version '$versionId' does not belong to file '$fileId'."
                )
            }

            transaction.update(versionRef, updatePayload)

            val isCurrentActive = fileSnap.getString(Fields.ACTIVE_VERSION_ID)?.trim() == versionId &&
                versionSnap.getBoolean(Fields.IS_PUBLISHED) == true
            if (isCurrentActive) {
                transaction.update(
                    fileRef,
                    mapOf(
                        Fields.ACTIVE_VERSION_LABEL to versionLabel,
                        Fields.UPDATED_BY_UID to validActorUid,
                        Fields.UPDATED_AT to timestamp
                    )
                )
            }
            null
        }.awaitResult()
    }

    override suspend fun publishStudyFileVersion(
        fileId: String,
        versionId: String,
        actorUid: String,
        verifiedProof: VerifiedPdfBinaryProof
    ) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val cleanFileId = AdminCatalogValidator.validateRequiredDocumentId(fileId)
        val cleanVersionId = AdminCatalogValidator.validateRequiredDocumentId(versionId)
        if (!FirestoreAdminCatalogMapper.hasReliableUploadedPdfProof(
                fileId = cleanFileId,
                versionId = cleanVersionId,
                r2ObjectKey = verifiedProof.r2ObjectKey,
                fileSizeBytes = verifiedProof.fileSizeBytes,
                checksumSha256 = verifiedProof.checksumSha256
            ) ||
            verifiedProof.fileId.trim() != cleanFileId ||
            verifiedProof.versionId.trim() != cleanVersionId
        ) {
            throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                message = "Cannot publish version '$cleanVersionId' without a valid server-verified PDF binary proof."
            )
        }
        val filePath = "${Collections.STUDY_FILES}/$cleanFileId"
        val versionsPath = "$filePath/${Subcollections.VERSIONS}"
        val targetVersionPath = "$versionsPath/$cleanVersionId"

        val existingVersionPaths = firestoreClient.listCollectionDocPaths(versionsPath)
        val timestamp = firestoreClient.serverTimestamp()

        firestoreClient.runTransaction { transaction ->
            val fileSnap = transaction.get(filePath)
            if (!fileSnap.exists) {
                throw AdminException(
                    reason = AdminFailureReason.NOT_FOUND,
                    message = "Study file '$cleanFileId' does not exist."
                )
            }
            val fileArchiveStatus = ArchiveStatus.fromIsArchivedValue(fileSnap.data[Fields.IS_ARCHIVED])
            when (fileArchiveStatus) {
                ArchiveStatus.ARCHIVED -> throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.FILE_ARCHIVED_CANNOT_PUBLISH_VERSION,
                    message = "Cannot publish a version while parent file '$cleanFileId' is archived."
                )
                ArchiveStatus.UNSPECIFIED -> throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                    message = "Cannot publish a version while parent file '$cleanFileId' has unspecified archive status."
                )
                ArchiveStatus.UNARCHIVED -> Unit
            }

            val pathsToRead = buildList {
                add(targetVersionPath)
                addAll(existingVersionPaths)
                val currentActiveId = (fileSnap.data[Fields.ACTIVE_VERSION_ID] as? String)
                    ?.trim()
                    .orEmpty()
                if (currentActiveId.isNotEmpty()) {
                    add("$versionsPath/$currentActiveId")
                }
            }.distinct()

            val snapshotsByPath = pathsToRead.associateWith { path ->
                transaction.get(path)
            }

            val targetSnap = snapshotsByPath[targetVersionPath]
            if (targetSnap == null || !targetSnap.exists) {
                throw AdminException(
                    reason = AdminFailureReason.NOT_FOUND,
                    message = "Study file version '$cleanVersionId' does not exist."
                )
            }

            val mappedTarget = FirestoreAdminCatalogMapper.mapStudyFileVersionForAdmin(
                id = targetSnap.id,
                data = targetSnap.data,
                expectedFileId = cleanFileId
            ) ?: throw AdminException(
                reason = AdminFailureReason.VALIDATION_ERROR,
                validationError = AdminValidationError.EMPTY_PARENT_REFERENCE,
                message = "Version '$cleanVersionId' has invalid parent link or metadata."
            )

            when {
                mappedTarget.isExplicitlyArchived -> throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.VERSION_ARCHIVED_CANNOT_PUBLISH,
                    message = "Cannot publish archived version '$cleanVersionId'."
                )
                !mappedTarget.isExplicitlyUnarchived -> throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.ARCHIVE_STATE_UNSPECIFIED,
                    message = "Cannot publish version '$cleanVersionId' with unspecified archive status."
                )
            }

            val matchesProof = FirestoreAdminCatalogMapper.matchesVerifiedPdfBinaryProof(
                version = mappedTarget,
                verifiedProof = verifiedProof,
                expectedFileId = cleanFileId
            )
            if (!matchesProof) {
                throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.VERSION_PDF_BINARY_MISSING,
                    message = "Cannot publish version '${mappedTarget.id}' because Firestore metadata does not match a server-verified Cloudflare R2 PDF binary."
                )
            }

            // All writes after reads: enforce at most ONE active published version without
            // deleting any historical version documents or overwriting their R2 object keys.
            for ((path, snap) in snapshotsByPath) {
                if (path == targetVersionPath || !snap.exists) continue
                if (snap.data[Fields.IS_PUBLISHED] == true) {
                    transaction.update(
                        path,
                        mapOf(
                            Fields.IS_PUBLISHED to false,
                            Fields.UPDATED_BY_UID to validActorUid,
                            Fields.UPDATED_AT to timestamp
                        )
                    )
                }
            }

            transaction.update(
                targetVersionPath,
                mapOf(
                    Fields.IS_PUBLISHED to true,
                    Fields.PUBLISHED_AT to timestamp,
                    Fields.UPDATED_BY_UID to validActorUid,
                    Fields.UPDATED_AT to timestamp
                )
            )

            val pointerPayload = FirestoreAdminCatalogMapper.buildActiveVersionPointerPayload(
                versionId = mappedTarget.id,
                versionNumber = mappedTarget.versionNumber,
                versionLabel = mappedTarget.versionLabel,
                publishedAtValue = timestamp,
                actorUid = validActorUid,
                timestampValue = timestamp
            )
            transaction.update(filePath, pointerPayload)
            null
        }
    }

    override suspend fun reorderBranches(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) {
        reorderCollectionItemsAtomically(
            collectionName = Collections.BRANCHES,
            firstId = firstId,
            firstNewOrder = firstNewOrder,
            secondId = secondId,
            secondNewOrder = secondNewOrder,
            actorUid = actorUid
        )
    }

    override suspend fun reorderSubjects(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) {
        reorderCollectionItemsAtomically(
            collectionName = Collections.SUBJECTS,
            firstId = firstId,
            firstNewOrder = firstNewOrder,
            secondId = secondId,
            secondNewOrder = secondNewOrder,
            actorUid = actorUid
        )
    }

    override suspend fun reorderSections(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) {
        reorderCollectionItemsAtomically(
            collectionName = Collections.SECTIONS,
            firstId = firstId,
            firstNewOrder = firstNewOrder,
            secondId = secondId,
            secondNewOrder = secondNewOrder,
            actorUid = actorUid
        )
    }

    override suspend fun reorderStudyFiles(
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) {
        reorderCollectionItemsAtomically(
            collectionName = Collections.STUDY_FILES,
            firstId = firstId,
            firstNewOrder = firstNewOrder,
            secondId = secondId,
            secondNewOrder = secondNewOrder,
            actorUid = actorUid
        )
    }

    private suspend fun createDocumentAtomically(
        firestore: FirebaseFirestore,
        collectionName: String,
        customId: String?,
        payload: Map<String, Any?>
    ): DocumentReference {
        val collection = firestore.collection(collectionName)
        if (customId == null) {
            val docRef = collection.document()
            docRef.set(payload).awaitResult()
            return docRef
        }

        val candidate = collection.document(customId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(candidate)
            if (snapshot.exists()) {
                throw AdminException(
                    reason = AdminFailureReason.VALIDATION_ERROR,
                    validationError = AdminValidationError.INVALID_DOCUMENT_ID,
                    message = "A document with ID '$customId' already exists."
                )
            }
            transaction.set(candidate, payload)
            null
        }.awaitResult()
        return candidate
    }

    private suspend fun reorderCollectionItemsAtomically(
        collectionName: String,
        firstId: String,
        firstNewOrder: Int,
        secondId: String,
        secondNewOrder: Int,
        actorUid: String
    ) {
        val validActorUid = requireNonBlankActorUid(actorUid)
        val firestore = requireConfiguredFirestore()
        val collection = firestore.collection(collectionName)
        val timestamp = FieldValue.serverTimestamp()
        val batch = firestore.batch()
        batch.update(
            collection.document(firstId),
            FirestoreAdminCatalogMapper.buildOrderUpdatePayload(
                order = firstNewOrder,
                actorUid = validActorUid,
                timestampValue = timestamp
            )
        )
        batch.update(
            collection.document(secondId),
            FirestoreAdminCatalogMapper.buildOrderUpdatePayload(
                order = secondNewOrder,
                actorUid = validActorUid,
                timestampValue = timestamp
            )
        )
        batch.commit().awaitResult()
    }

    private fun requireNonBlankActorUid(actorUid: String): String {
        val cleanUid = actorUid.trim()
        if (cleanUid.isEmpty()) {
            throw AdminException(
                reason = AdminFailureReason.UNAUTHENTICATED,
                message = "Authenticated admin UID is required."
            )
        }
        return cleanUid
    }

    private fun requireConfiguredFirestore(): FirebaseFirestore {
        return firestoreProvider.getFirestoreOrNull()
            ?: throw AdminException(
                reason = AdminFailureReason.FIREBASE_NOT_CONFIGURED,
                message = "Firebase is not configured locally yet."
            )
    }
}
