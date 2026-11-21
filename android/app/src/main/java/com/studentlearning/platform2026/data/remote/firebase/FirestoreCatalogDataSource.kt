package com.studentlearning.platform2026.data.remote.firebase

import com.google.firebase.firestore.DocumentReference
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Collections
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Fields
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject

interface CatalogRemoteDataSource {
    suspend fun fetchPublishedBranches(): List<StudyBranch>
    suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject>
    suspend fun fetchPublishedSections(subjectId: String): List<StudySection>
    suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata>
}

class FirestoreCatalogDataSource(
    private val firestoreProvider: FirestoreProvider
) : CatalogRemoteDataSource {

    override suspend fun fetchPublishedBranches(): List<StudyBranch> {
        val firestore = requireConfiguredFirestore()
        val snapshot = firestore.collection(Collections.BRANCHES)
            .whereEqualTo(Fields.IS_PUBLISHED, true)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { doc ->
            doc.id to (doc.data ?: emptyMap())
        }
        return FirestoreCatalogMapper.mapBranches(rawDocuments)
    }

    override suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject> {
        if (branchId.isBlank()) return emptyList()

        val firestore = requireConfiguredFirestore()
        val branchReference: DocumentReference = firestore
            .collection(Collections.BRANCHES)
            .document(branchId)

        val snapshot = firestore.collection(Collections.SUBJECTS)
            .whereEqualTo(Fields.BRANCH_ID, branchReference)
            .whereEqualTo(Fields.IS_PUBLISHED, true)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { doc ->
            doc.id to (doc.data ?: emptyMap())
        }
        return FirestoreCatalogMapper.mapSubjects(rawDocuments, expectedBranchId = branchId)
    }

    override suspend fun fetchPublishedSections(subjectId: String): List<StudySection> {
        if (subjectId.isBlank()) return emptyList()

        val firestore = requireConfiguredFirestore()
        val subjectReference: DocumentReference = firestore
            .collection(Collections.SUBJECTS)
            .document(subjectId)

        val snapshot = firestore.collection(Collections.SECTIONS)
            .whereEqualTo(Fields.SUBJECT_ID, subjectReference)
            .whereEqualTo(Fields.IS_PUBLISHED, true)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { doc ->
            doc.id to (doc.data ?: emptyMap())
        }
        return FirestoreCatalogMapper.mapSections(
            documents = rawDocuments,
            expectedSubjectId = subjectId
        )
    }

    override suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata> {
        if (sectionId.isBlank()) return emptyList()

        val firestore = requireConfiguredFirestore()
        val spec = buildPublishedStudyFilesQuerySpec(sectionId)
        val sectionReference: DocumentReference = firestore
            .collection(Collections.SECTIONS)
            .document(spec.sectionId)

        val snapshot = firestore.collection(spec.collection)
            .whereEqualTo(spec.sectionIdField, sectionReference)
            .whereEqualTo(spec.isPublishedField, spec.isPublishedValue)
            .whereEqualTo(spec.isArchivedField, spec.isArchivedValue)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { doc ->
            doc.id to (doc.data ?: emptyMap())
        }
        return FirestoreCatalogMapper.mapStudyFiles(
            documents = rawDocuments,
            expectedSectionId = spec.sectionId
        )
    }

    internal data class PublishedStudyFilesQuerySpec(
        val collection: String,
        val sectionIdField: String,
        val sectionId: String,
        val isPublishedField: String,
        val isPublishedValue: Boolean,
        val isArchivedField: String,
        val isArchivedValue: Boolean
    )

    internal fun buildPublishedStudyFilesQuerySpec(sectionId: String): PublishedStudyFilesQuerySpec {
        return PublishedStudyFilesQuerySpec(
            collection = Collections.STUDY_FILES,
            sectionIdField = Fields.SECTION_ID,
            sectionId = sectionId.trim(),
            isPublishedField = Fields.IS_PUBLISHED,
            isPublishedValue = true,
            isArchivedField = Fields.IS_ARCHIVED,
            isArchivedValue = false
        )
    }

    private fun requireConfiguredFirestore() =
        firestoreProvider.getFirestoreOrNull()
            ?: throw CatalogException(
                reason = CatalogFailureReason.FIREBASE_NOT_CONFIGURED,
                message = "Firebase is not configured locally yet."
            )
}
