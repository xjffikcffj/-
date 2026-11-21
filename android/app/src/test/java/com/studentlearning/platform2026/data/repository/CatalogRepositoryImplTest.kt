package com.studentlearning.platform2026.data.repository

import com.google.firebase.firestore.FirebaseFirestoreException
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.remote.firebase.CatalogRemoteDataSource
import com.studentlearning.platform2026.domain.model.ArchiveStatus
import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import com.studentlearning.platform2026.domain.model.StudyBranch
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.domain.model.StudySection
import com.studentlearning.platform2026.domain.model.StudySubject
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    @Test
    fun `returns FIREBASE_NOT_CONFIGURED error without faking fallback data`() = runTest {
        val dataSource = object : CatalogRemoteDataSource {
            override suspend fun fetchPublishedBranches(): List<StudyBranch> {
                throw CatalogException(CatalogFailureReason.FIREBASE_NOT_CONFIGURED)
            }

            override suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject> = emptyList()

            override suspend fun fetchPublishedSections(subjectId: String): List<StudySection> = emptyList()

            override suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata> = emptyList()
        }

        val repository = CatalogRepositoryImpl(dataSource, testDispatchers)
        val result = repository.getPublishedBranches()

        assertTrue(result is AppResult.Error)
        val cause = (result as AppResult.Error).cause as CatalogException
        assertEquals(CatalogFailureReason.FIREBASE_NOT_CONFIGURED, cause.reason)
    }

    @Test
    fun `maps Firestore PERMISSION_DENIED exception cleanly`() = runTest {
        val dataSource = object : CatalogRemoteDataSource {
            override suspend fun fetchPublishedBranches(): List<StudyBranch> = emptyList()

            override suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject> {
                throw FirebaseFirestoreException(
                    "Access denied by temporary rules",
                    FirebaseFirestoreException.Code.PERMISSION_DENIED
                )
            }

            override suspend fun fetchPublishedSections(subjectId: String): List<StudySection> = emptyList()

            override suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata> = emptyList()
        }

        val repository = CatalogRepositoryImpl(dataSource, testDispatchers)
        val result = repository.getPublishedSubjects("scientific")

        assertTrue(result is AppResult.Error)
        val cause = (result as AppResult.Error).cause as CatalogException
        assertEquals(CatalogFailureReason.PERMISSION_DENIED, cause.reason)
    }

    @Test
    fun `getPublishedSections delegates subjectId and returns mapped sections`() = runTest {
        var requestedSubjectId: String? = null
        val expectedSections = listOf(
            StudySection(
                id = "sec1",
                subjectId = "physics",
                name = "الفصل الأول",
                order = 1,
                isPublished = true
            )
        )
        val dataSource = object : CatalogRemoteDataSource {
            override suspend fun fetchPublishedBranches(): List<StudyBranch> = emptyList()
            override suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject> = emptyList()
            override suspend fun fetchPublishedSections(subjectId: String): List<StudySection> {
                requestedSubjectId = subjectId
                return expectedSections
            }

            override suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata> = emptyList()
        }

        val repository = CatalogRepositoryImpl(dataSource, testDispatchers)
        val result = repository.getPublishedSections("physics")

        assertEquals("physics", requestedSubjectId)
        assertTrue(result is AppResult.Success)
        assertEquals(expectedSections, (result as AppResult.Success).data)
    }

    @Test
    fun `getPublishedSections maps FIREBASE_NOT_CONFIGURED and PERMISSION_DENIED errors`() = runTest {
        val unconfiguredDataSource = object : CatalogRemoteDataSource {
            override suspend fun fetchPublishedBranches(): List<StudyBranch> = emptyList()
            override suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject> = emptyList()
            override suspend fun fetchPublishedSections(subjectId: String): List<StudySection> {
                throw CatalogException(CatalogFailureReason.FIREBASE_NOT_CONFIGURED)
            }

            override suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata> = emptyList()
        }

        val unconfiguredResult = CatalogRepositoryImpl(unconfiguredDataSource, testDispatchers)
            .getPublishedSections("physics")
        assertTrue(unconfiguredResult is AppResult.Error)
        assertEquals(
            CatalogFailureReason.FIREBASE_NOT_CONFIGURED,
            ((unconfiguredResult as AppResult.Error).cause as CatalogException).reason
        )

        val deniedDataSource = object : CatalogRemoteDataSource {
            override suspend fun fetchPublishedBranches(): List<StudyBranch> = emptyList()
            override suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject> = emptyList()
            override suspend fun fetchPublishedSections(subjectId: String): List<StudySection> {
                throw FirebaseFirestoreException(
                    "Access denied",
                    FirebaseFirestoreException.Code.PERMISSION_DENIED
                )
            }

            override suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata> = emptyList()
        }

        val deniedResult = CatalogRepositoryImpl(deniedDataSource, testDispatchers)
            .getPublishedSections("physics")
        assertTrue(deniedResult is AppResult.Error)
        assertEquals(
            CatalogFailureReason.PERMISSION_DENIED,
            ((deniedResult as AppResult.Error).cause as CatalogException).reason
        )
    }

    @Test
    fun `getPublishedStudyFiles delegates sectionId and returns mapped files`() = runTest {
        var requestedSectionId: String? = null
        val expectedFiles = listOf(
            StudyFileMetadata(
                id = "file-1",
                sectionId = "sec-1",
                title = "ملزمة الفصل الأول",
                description = "نسخة مراجعة",
                order = 1,
                isPublished = true,
                archiveStatus = ArchiveStatus.UNARCHIVED,
                activeVersionId = "v1"
            )
        )
        val dataSource = object : CatalogRemoteDataSource {
            override suspend fun fetchPublishedBranches(): List<StudyBranch> = emptyList()
            override suspend fun fetchPublishedSubjects(branchId: String): List<StudySubject> = emptyList()
            override suspend fun fetchPublishedSections(subjectId: String): List<StudySection> = emptyList()
            override suspend fun fetchPublishedStudyFiles(sectionId: String): List<StudyFileMetadata> {
                requestedSectionId = sectionId
                return expectedFiles
            }
        }

        val repository = CatalogRepositoryImpl(dataSource, testDispatchers)
        val result = repository.getPublishedStudyFiles("sec-1")

        assertEquals("sec-1", requestedSectionId)
        assertTrue(result is AppResult.Success)
        assertEquals(expectedFiles, (result as AppResult.Success).data)
    }
}
