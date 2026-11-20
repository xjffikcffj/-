package com.studentlearning.platform2026.data.remote.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Collections
import com.studentlearning.platform2026.data.remote.firebase.FirestoreCatalogSchema.Fields
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import java.io.IOException
import java.util.Date

data class FirestoreReferenceTarget(
    val collectionId: String,
    val documentId: String,
    val path: String
)

object FirestoreCatalogMapper {

    fun mapBranch(id: String, data: Map<String, Any?>): StudyBranch? {
        if (id.isBlank()) return null

        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false
        if (!isPublished) return null

        val name = extractArabicName(data) ?: return null
        val order = (data[Fields.ORDER] as? Number)?.toInt() ?: Int.MAX_VALUE

        return StudyBranch(
            id = id,
            name = name,
            order = order,
            isPublished = true,
            referencePath = "${Collections.BRANCHES}/$id"
        )
    }

    fun mapBranches(documents: List<Pair<String, Map<String, Any?>>>): List<StudyBranch> {
        return documents
            .mapNotNull { (id, data) -> mapBranch(id, data) }
            .sortedWith(compareBy<StudyBranch> { it.order }.thenBy { it.name })
    }

    fun mapSubject(
        id: String,
        data: Map<String, Any?>,
        expectedBranchId: String
    ): StudySubject? {
        if (id.isBlank() || expectedBranchId.isBlank()) return null

        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false
        if (!isPublished) return null

        // Confirmed schema: subjects.branchId is a DocumentReference to branches/{branchId}.
        val branchRef = extractReferenceTarget(data[Fields.BRANCH_ID]) ?: return null
        if (branchRef.collectionId != Collections.BRANCHES || branchRef.documentId != expectedBranchId) {
            return null
        }

        val name = extractArabicName(data) ?: return null
        val order = (data[Fields.ORDER] as? Number)?.toInt() ?: Int.MAX_VALUE

        return StudySubject(
            id = id,
            branchId = branchRef.documentId,
            name = name,
            order = order,
            isPublished = true,
            branchReferencePath = branchRef.path
        )
    }

    fun mapSubjects(
        documents: List<Pair<String, Map<String, Any?>>>,
        expectedBranchId: String
    ): List<StudySubject> {
        return documents
            .mapNotNull { (id, data) -> mapSubject(id, data, expectedBranchId) }
            .sortedWith(compareBy<StudySubject> { it.order }.thenBy { it.name })
    }

    fun mapSection(
        id: String,
        data: Map<String, Any?>,
        expectedSubjectId: String
    ): StudySection? {
        if (id.isBlank() || expectedSubjectId.isBlank()) return null

        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false
        if (!isPublished) return null

        // Confirmed schema: sections.subjectId is a DocumentReference to subjects/{subjectId}.
        val subjectRef = extractReferenceTarget(data[Fields.SUBJECT_ID]) ?: return null
        if (subjectRef.collectionId != Collections.SUBJECTS || subjectRef.documentId != expectedSubjectId) {
            return null
        }

        val name = extractArabicName(data) ?: return null
        val order = (data[Fields.ORDER] as? Number)?.toInt() ?: Int.MAX_VALUE

        return StudySection(
            id = id,
            subjectId = subjectRef.documentId,
            name = name,
            order = order,
            isPublished = true,
            subjectReferencePath = subjectRef.path
        )
    }

    fun mapSections(
        documents: List<Pair<String, Map<String, Any?>>>,
        expectedSubjectId: String
    ): List<StudySection> {
        return documents
            .mapNotNull { (id, data) -> mapSection(id, data, expectedSubjectId) }
            .sortedWith(compareBy<StudySection> { it.order }.thenBy { it.name })
    }

    fun mapStudyFile(
        id: String,
        data: Map<String, Any?>,
        expectedSectionId: String
    ): StudyFileMetadata? {
        if (id.isBlank() || expectedSectionId.isBlank()) return null

        val isPublished = (data[Fields.IS_PUBLISHED] as? Boolean) ?: false
        val isArchived = (data[Fields.IS_ARCHIVED] as? Boolean) ?: false
        if (!isPublished || isArchived) return null

        val sectionRef = extractReferenceTarget(data[Fields.SECTION_ID]) ?: return null
        if (sectionRef.collectionId != Collections.SECTIONS || sectionRef.documentId != expectedSectionId) {
            return null
        }

        val title = (data[Fields.TITLE] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractArabicName(data)
            ?: return null
        val description = (data[Fields.DESCRIPTION] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val order = (data[Fields.ORDER] as? Number)?.toInt() ?: Int.MAX_VALUE
        val mimeType = (data[Fields.MIME_TYPE] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: FirestoreCatalogSchema.Defaults.MIME_TYPE_PDF
        val storageProvider = (data[Fields.STORAGE_PROVIDER] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: FirestoreCatalogSchema.Defaults.STORAGE_PROVIDER_CLOUDFLARE_R2
        val activeVersionId = (data[Fields.ACTIVE_VERSION_ID] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val activeVersionNumber = (data[Fields.ACTIVE_VERSION_NUMBER] as? Number)?.toInt()?.takeIf { it >= 1 }
        val activeVersionLabel = (data[Fields.ACTIVE_VERSION_LABEL] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val activeVersionPublishedAt = extractEpochMillis(data[Fields.ACTIVE_VERSION_PUBLISHED_AT])

        return StudyFileMetadata(
            id = id,
            sectionId = sectionRef.documentId,
            sectionReferencePath = sectionRef.path,
            title = title,
            description = description,
            order = order,
            isPublished = true,
            isArchived = false,
            mimeType = mimeType,
            storageProvider = storageProvider,
            activeVersionId = activeVersionId,
            activeVersionNumber = activeVersionNumber,
            activeVersionLabel = activeVersionLabel,
            activeVersionPublishedAtEpochMillis = activeVersionPublishedAt
        )
    }

    fun mapStudyFiles(
        documents: List<Pair<String, Map<String, Any?>>>,
        expectedSectionId: String
    ): List<StudyFileMetadata> {
        return documents
            .mapNotNull { (id, data) -> mapStudyFile(id, data, expectedSectionId) }
            .sortedWith(compareBy<StudyFileMetadata> { it.order }.thenBy { it.title })
    }

    fun mapFailureReason(throwable: Throwable): CatalogFailureReason {
        return when (throwable) {
            is FirebaseFirestoreException -> when (throwable.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> CatalogFailureReason.PERMISSION_DENIED
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> CatalogFailureReason.NETWORK_UNAVAILABLE
                else -> CatalogFailureReason.UNKNOWN
            }
            is IOException -> CatalogFailureReason.NETWORK_UNAVAILABLE
            else -> CatalogFailureReason.UNKNOWN
        }
    }

    internal fun extractReferenceTarget(value: Any?): FirestoreReferenceTarget? {
        return when (value) {
            is DocumentReference -> FirestoreReferenceTarget(
                collectionId = value.parent.id,
                documentId = value.id,
                path = value.path
            )
            is FirestoreReferenceTarget -> value
            else -> null
        }
    }

    internal fun extractEpochMillis(value: Any?): Long? {
        return when (value) {
            is Timestamp -> value.toDate().time
            is Date -> value.time
            is Number -> value.toLong().takeIf { it > 0L }
            else -> null
        }
    }

    private fun extractArabicName(data: Map<String, Any?>): String? {
        val raw = (data[Fields.NAME_AR] as? String)
            ?: (data[Fields.NAME] as? String)
            ?: (data[Fields.TITLE] as? String)
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}
