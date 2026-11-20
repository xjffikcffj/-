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
        val sectionReference: DocumentReference = firestore
            .collection(Collections.SECTIONS)
            .document(sectionId)

        val snapshot = firestore.collection(Collections.STUDY_FILES)
            .whereEqualTo(Fields.SECTION_ID, sectionReference)
            .whereEqualTo(Fields.IS_PUBLISHED, true)
            .get()
            .awaitResult()

        val rawDocuments = snapshot.documents.map { doc ->
            doc.id to (doc.data ?: emptyMap())
        }
        return FirestoreCatalogMapper.mapStudyFiles(
            documents = rawDocuments,
            expectedSectionId = sectionId
        )
    }

    private fun requireConfiguredFirestore() =
        firestoreProvider.getFirestoreOrNull()
            ?: throw CatalogException(
                reason = CatalogFailureReason.FIREBASE_NOT_CONFIGURED,
                message = "Firebase is not configured locally yet."
            )
}
