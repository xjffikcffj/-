package com.studentlearning.platform2026.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestoreException
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class FirestoreCatalogMapperTest {

    @Test
    fun `mapBranches excludes unpublished items and sorts ascending by order`() {
        val docs = listOf(
            "literary" to mapOf("name" to "السادس الادبي", "order" to 2L, "isPublished" to true),
            "hidden" to mapOf("name" to "فرع مخفي", "order" to 0L, "isPublished" to false),
            "missingFlag" to mapOf("name" to "بدون حالة نشر", "order" to 3L),
            "scientific" to mapOf("name" to "السادس العلمي", "order" to 1L, "isPublished" to true)
        )

        val result = FirestoreCatalogMapper.mapBranches(docs)

        assertEquals(2, result.size)
        assertEquals(listOf("scientific", "literary"), result.map { it.id })
        assertEquals(listOf("branches/scientific", "branches/literary"), result.map { it.referencePath })
        assertEquals(listOf("السادس العلمي", "السادس الادبي"), result.map { it.name })
    }

    @Test
    fun `mapSubjects requires DocumentReference to branches and rejects plain string or wrong collection`() {
        val scientificRef = FirestoreReferenceTarget(
            collectionId = "branches",
            documentId = "scientific",
            path = "branches/scientific"
        )
        val literaryRef = FirestoreReferenceTarget(
            collectionId = "branches",
            documentId = "literary",
            path = "branches/literary"
        )
        val wrongCollectionRef = FirestoreReferenceTarget(
            collectionId = "other_collection",
            documentId = "scientific",
            path = "other_collection/scientific"
        )

        val docs = listOf(
            "physics" to mapOf(
                "branchId" to scientificRef,
                "name" to "الفيزياء",
                "order" to 6L,
                "isPublished" to true
            ),
            "math" to mapOf(
                "branchId" to scientificRef,
                "name" to "الرياضيات",
                "order" to 1L,
                "isPublished" to true
            ),
            "plainStringRejected" to mapOf(
                "branchId" to "scientific",
                "name" to "مادة بنص غير مرجعي",
                "order" to 2L,
                "isPublished" to true
            ),
            "wrongCollectionRejected" to mapOf(
                "branchId" to wrongCollectionRef,
                "name" to "مادة بمرجع خاطئ",
                "order" to 3L,
                "isPublished" to true
            ),
            "history" to mapOf(
                "branchId" to literaryRef,
                "name" to "التاريخ",
                "order" to 1L,
                "isPublished" to true
            ),
            "draft_chem" to mapOf(
                "branchId" to scientificRef,
                "name" to "الكيمياء",
                "order" to 5L,
                "isPublished" to false
            )
        )

        val result = FirestoreCatalogMapper.mapSubjects(docs, expectedBranchId = "scientific")

        assertEquals(listOf("math", "physics"), result.map { it.id })
        assertEquals(listOf("الرياضيات", "الفيزياء"), result.map { it.name })
        assertEquals("scientific", result[0].branchId)
        assertEquals("branches/scientific", result[0].branchReferencePath)
    }

    @Test
    fun `mapSections requires DocumentReference to subjects and rejects plain string or wrong collection`() {
        val physicsRef = FirestoreReferenceTarget(
            collectionId = "subjects",
            documentId = "physics",
            path = "subjects/physics"
        )
        val mathRef = FirestoreReferenceTarget(
            collectionId = "subjects",
            documentId = "math",
            path = "subjects/math"
        )
        val wrongCollectionRef = FirestoreReferenceTarget(
            collectionId = "branches",
            documentId = "physics",
            path = "branches/physics"
        )

        val docs = listOf(
            "sec2" to mapOf(
                "subjectId" to physicsRef,
                "name" to "الفصل الثاني",
                "order" to 2L,
                "isPublished" to true
            ),
            "sec1" to mapOf(
                "subjectId" to physicsRef,
                "name" to "الفصل الأول",
                "order" to 1L,
                "isPublished" to true
            ),
            "secPlainStringRejected" to mapOf(
                "subjectId" to "physics",
                "name" to "قسم بنص غير مرجعي",
                "order" to 1L,
                "isPublished" to true
            ),
            "secWrongCollectionRejected" to mapOf(
                "subjectId" to wrongCollectionRef,
                "name" to "قسم بمرجع من مجموعة خاطئة",
                "order" to 1L,
                "isPublished" to true
            ),
            "secOtherSubjectRef" to mapOf(
                "subjectId" to mathRef,
                "name" to "الأعداد المركبة",
                "order" to 1L,
                "isPublished" to true
            ),
            "secHidden" to mapOf(
                "subjectId" to physicsRef,
                "name" to "قسم غير منشور",
                "order" to 0L,
                "isPublished" to false
            )
        )

        val result = FirestoreCatalogMapper.mapSections(
            documents = docs,
            expectedSubjectId = "physics"
        )

        assertEquals(listOf("sec1", "sec2"), result.map { it.id })
        assertEquals(listOf("الفصل الأول", "الفصل الثاني"), result.map { it.name })
        assertEquals("physics", result[0].subjectId)
        assertEquals("subjects/physics", result[0].subjectReferencePath)
        assertEquals("physics", result[1].subjectId)
        assertEquals("subjects/physics", result[1].subjectReferencePath)
    }

    @Test
    fun `mapFailureReason maps firestore permission and network errors accurately`() {
        val permissionError = FirebaseFirestoreException(
            "Missing or insufficient permissions.",
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        val unavailableError = FirebaseFirestoreException(
            "Service unavailable.",
            FirebaseFirestoreException.Code.UNAVAILABLE
        )

        assertEquals(
            CatalogFailureReason.PERMISSION_DENIED,
            FirestoreCatalogMapper.mapFailureReason(permissionError)
        )
        assertEquals(
            CatalogFailureReason.NETWORK_UNAVAILABLE,
            FirestoreCatalogMapper.mapFailureReason(unavailableError)
        )
        assertEquals(
            CatalogFailureReason.NETWORK_UNAVAILABLE,
            FirestoreCatalogMapper.mapFailureReason(IOException("Timeout"))
        )
    }

    @Test
    fun `mapStudyFiles excludes archived and unpublished study files from student catalog results`() {
        val sectionRef = FirestoreReferenceTarget(
            collectionId = "sections",
            documentId = "sec-1",
            path = "sections/sec-1"
        )
        val docs = listOf(
            "live-file" to mapOf(
                "sectionId" to sectionRef,
                "title" to "ملزمة منشورة",
                "order" to 1L,
                "isPublished" to true,
                "isArchived" to false,
                "activeVersionId" to "v1",
                "activeVersionNumber" to 1L,
                "activeVersionLabel" to "1.0"
            ),
            "archived-even-if-published" to mapOf(
                "sectionId" to sectionRef,
                "title" to "ملزمة مؤرشفة",
                "order" to 2L,
                "isPublished" to true,
                "isArchived" to true,
                "activeVersionId" to "v1",
                "activeVersionNumber" to 1L,
                "activeVersionLabel" to "1.0"
            ),
            "missing-archive-even-if-published" to mapOf(
                "sectionId" to sectionRef,
                "title" to "ملزمة بلا حقل أرشفة",
                "order" to 2L,
                "isPublished" to true,
                "activeVersionId" to "v1",
                "activeVersionNumber" to 1L,
                "activeVersionLabel" to "1.0"
            ),
            "null-archive-even-if-published" to mapOf(
                "sectionId" to sectionRef,
                "title" to "ملزمة بحقل فارغ",
                "order" to 2L,
                "isPublished" to true,
                "isArchived" to null,
                "activeVersionId" to "v1",
                "activeVersionNumber" to 1L,
                "activeVersionLabel" to "1.0"
            ),
            "string-false-even-if-published" to mapOf(
                "sectionId" to sectionRef,
                "title" to "ملزمة بنص غير منطقي",
                "order" to 2L,
                "isPublished" to true,
                "isArchived" to "false",
                "activeVersionId" to "v1",
                "activeVersionNumber" to 1L,
                "activeVersionLabel" to "1.0"
            ),
            "number-zero-even-if-published" to mapOf(
                "sectionId" to sectionRef,
                "title" to "ملزمة برقم غير منطقي",
                "order" to 2L,
                "isPublished" to true,
                "isArchived" to 0,
                "activeVersionId" to "v1",
                "activeVersionNumber" to 1L,
                "activeVersionLabel" to "1.0"
            ),
            "unpublished-draft" to mapOf(
                "sectionId" to sectionRef,
                "title" to "مسودة",
                "order" to 3L,
                "isPublished" to false,
                "isArchived" to false
            )
        )

        val result = FirestoreCatalogMapper.mapStudyFiles(docs, expectedSectionId = "sec-1")

        assertEquals(listOf("live-file"), result.map { it.id })
        assertEquals("v1", result.single().activeVersionId)
        assertEquals(ArchiveStatus.UNARCHIVED, result.single().archiveStatus)
    }
}
