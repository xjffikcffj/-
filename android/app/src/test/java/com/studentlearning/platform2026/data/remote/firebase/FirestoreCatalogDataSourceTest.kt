package com.studentlearning.platform2026.data.remote.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreCatalogDataSourceTest {

    private val unconfiguredDataSource = FirestoreCatalogDataSource(
        firestoreProvider = object : FirestoreProvider {
            override fun getFirestoreOrNull(): FirebaseFirestore? = null
        }
    )

    @Test
    fun `fetchPublishedBranches throws FIREBASE_NOT_CONFIGURED when Firestore is unavailable`() = runTest {
        try {
            unconfiguredDataSource.fetchPublishedBranches()
            fail("Expected CatalogException")
        } catch (error: CatalogException) {
            assertEquals(CatalogFailureReason.FIREBASE_NOT_CONFIGURED, error.reason)
        }
    }

    @Test
    fun `fetchPublishedSubjects throws FIREBASE_NOT_CONFIGURED when Firestore is unavailable`() = runTest {
        try {
            unconfiguredDataSource.fetchPublishedSubjects("scientific")
            fail("Expected CatalogException")
        } catch (error: CatalogException) {
            assertEquals(CatalogFailureReason.FIREBASE_NOT_CONFIGURED, error.reason)
        }
    }

    @Test
    fun `fetchPublishedSections throws FIREBASE_NOT_CONFIGURED when Firestore is unavailable`() = runTest {
        try {
            unconfiguredDataSource.fetchPublishedSections("physics")
            fail("Expected CatalogException")
        } catch (error: CatalogException) {
            assertEquals(CatalogFailureReason.FIREBASE_NOT_CONFIGURED, error.reason)
        }
    }

    @Test
    fun `fetchPublishedStudyFiles throws FIREBASE_NOT_CONFIGURED when Firestore is unavailable`() = runTest {
        try {
            unconfiguredDataSource.fetchPublishedStudyFiles("sec-1")
            fail("Expected CatalogException")
        } catch (error: CatalogException) {
            assertEquals(CatalogFailureReason.FIREBASE_NOT_CONFIGURED, error.reason)
        }
    }

    @Test
    fun `blank branchId subjectId and sectionId return emptyList without querying Firestore`() = runTest {
        val subjects = unconfiguredDataSource.fetchPublishedSubjects("   ")
        val sections = unconfiguredDataSource.fetchPublishedSections("   ")
        val studyFiles = unconfiguredDataSource.fetchPublishedStudyFiles("   ")

        assertTrue(subjects.isEmpty())
        assertTrue(sections.isEmpty())
        assertTrue(studyFiles.isEmpty())
    }

    @Test
    fun `buildPublishedStudyFilesQuerySpec enforces both isPublished true and isArchived false at Firestore query level`() {
        val spec = unconfiguredDataSource.buildPublishedStudyFilesQuerySpec(" sec-1 ")

        assertEquals(FirestoreCatalogSchema.Collections.STUDY_FILES, spec.collection)
        assertEquals(FirestoreCatalogSchema.Fields.SECTION_ID, spec.sectionIdField)
        assertEquals("sec-1", spec.sectionId)
        assertEquals(FirestoreCatalogSchema.Fields.IS_PUBLISHED, spec.isPublishedField)
        assertEquals(true, spec.isPublishedValue)
        assertEquals(FirestoreCatalogSchema.Fields.IS_ARCHIVED, spec.isArchivedField)
        assertEquals(false, spec.isArchivedValue)
    }
}
