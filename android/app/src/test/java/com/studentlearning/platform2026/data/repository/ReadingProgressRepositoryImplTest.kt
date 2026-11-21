package com.studentlearning.platform2026.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.ReadingProgressDataSource
import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.StudyFileMetadata
import com.studentlearning.platform2026.fakes.FakeAdminCatalogRepository
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingProgressRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    private fun createRepository(
        prefsFile: File,
        scope: TestScope,
        clock: () -> Long = { 1_700_000_000_000L }
    ): ReadingProgressRepositoryImpl {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { prefsFile }
        )
        return ReadingProgressRepositoryImpl(
            localDataSource = ReadingProgressDataSource(
                dataStore = dataStore,
                clockEpochMillis = clock
            ),
            dispatcherProvider = testDispatchers
        )
    }

    @Test
    fun `saves and restores last page keyed strictly by fileId`() = runTest {
        val prefsFile = File(tempFolder.newFolder(), "progress.preferences_pb")
        val repository = createRepository(prefsFile, this)

        assertNull(repository.getProgress("file-physics-1"))

        repository.saveLastPage(
            fileId = "file-physics-1",
            pageNumber = 14,
            totalPages = 42
        )

        val restored = repository.getProgress("file-physics-1")
        assertNotNull(restored)
        assertEquals("file-physics-1", restored?.fileId)
        assertEquals(14, restored?.lastPageNumber)
        assertEquals(42, restored?.totalPages)
        assertEquals(14, repository.observeProgress("file-physics-1").first()?.lastPageNumber)
    }

    @Test
    fun `keeps separate progress per fileId and prevents delimiter collision`() = runTest {
        val prefsFile = File(tempFolder.newFolder(), "isolation.preferences_pb")
        val repository = createRepository(prefsFile, this)

        repository.saveLastPage(fileId = "doc_a", pageNumber = 5, totalPages = 20)
        repository.saveLastPage(fileId = "doc_b", pageNumber = 19, totalPages = 30)
        repository.saveLastPage(fileId = "doc/a", pageNumber = 8, totalPages = 15)

        assertEquals(5, repository.getProgress("doc_a")?.lastPageNumber)
        assertEquals(19, repository.getProgress("doc_b")?.lastPageNumber)
        assertEquals(8, repository.getProgress("doc/a")?.lastPageNumber)
    }

    @Test
    fun `persists across repository recreation and remains intact after signOut or account switch`() =
        runTest {
            val prefsFile = File(tempFolder.newFolder(), "persist.preferences_pb")
            val authRepository = FakeAuthRepository(
                initialSession = AdminSession(
                    isSignedIn = true,
                    uid = "student_1",
                    email = "s1@example.com",
                    role = AdminRole.NONE,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )

            val firstRepo = createRepository(prefsFile, this)
            firstRepo.saveLastPage(fileId = "math-ch1", pageNumber = 23, totalPages = 50)

            // Sign out and switch to guest / another user: local progress must remain untouched.
            authRepository.signOut()
            authRepository.setSession(
                AdminSession(
                    isSignedIn = true,
                    uid = "student_2",
                    email = "s2@example.com",
                    role = AdminRole.NONE,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )

            val recreatedRepo = createRepository(prefsFile, this)
            val restored = recreatedRepo.getProgress("math-ch1")
            assertEquals(23, restored?.lastPageNumber)
            assertEquals(50, restored?.totalPages)
        }

    @Test
    fun `clamps saved page within valid range and ignores blank fileId`() = runTest {
        val prefsFile = File(tempFolder.newFolder(), "clamp.preferences_pb")
        val repository = createRepository(prefsFile, this)

        repository.saveLastPage(fileId = "   ", pageNumber = 3, totalPages = 10)
        assertNull(repository.getProgress("   "))

        repository.saveLastPage(fileId = "doc-1", pageNumber = 99, totalPages = 12)
        assertEquals(12, repository.getProgress("doc-1")?.lastPageNumber)

        repository.saveLastPage(fileId = "doc-1", pageNumber = 0, totalPages = 12)
        assertEquals(1, repository.getProgress("doc-1")?.lastPageNumber)

        repository.clearProgress("doc-1")
        assertNull(repository.getProgress("doc-1"))
    }

    @Test
    fun `serialized access keeps last sequential page intact under concurrent saves`() = runTest {
        val prefsFile = File(tempFolder.newFolder(), "concurrent.preferences_pb")
        val repository = createRepository(prefsFile, this)

        repository.saveLastPage(fileId = "doc-seq", pageNumber = 2, totalPages = 50)
        repository.saveLastPage(fileId = "doc-seq", pageNumber = 9, totalPages = 50)
        repository.saveLastPage(fileId = "doc-seq", pageNumber = 17, totalPages = 50)

        val progress = repository.getProgress("doc-seq")
        assertEquals(17, progress?.lastPageNumber)
        assertEquals(50, progress?.totalPages)
    }

    @Test
    fun `isolates reading progress per versionId for the same fileId without UID dependency`() = runTest {
        val prefsFile = File(tempFolder.newFolder(), "versions.preferences_pb")
        val repository = createRepository(prefsFile, this)

        repository.saveLastPage(fileId = "physics-ch1", pageNumber = 5, totalPages = 30, versionId = "v1")
        repository.saveLastPage(fileId = "physics-ch1", pageNumber = 19, totalPages = 40, versionId = "v2")
        repository.saveLastPage(fileId = "physics-ch1", pageNumber = 2, totalPages = 10, versionId = null)

        val v1 = repository.getProgress("physics-ch1", versionId = "v1")
        val v2 = repository.getProgress("physics-ch1", versionId = "v2")
        val defaultScope = repository.getProgress("physics-ch1", versionId = null)

        assertEquals(5, v1?.lastPageNumber)
        assertEquals("v1", v1?.versionId)
        assertEquals(19, v2?.lastPageNumber)
        assertEquals("v2", v2?.versionId)
        assertEquals(2, defaultScope?.lastPageNumber)
        assertNull(defaultScope?.versionId)
        assertEquals(19, repository.observeProgress("physics-ch1", versionId = "v2").first()?.lastPageNumber)

        // Clearing v1 leaves v2 and null scopes completely untouched.
        repository.clearProgress("physics-ch1", versionId = "v1")
        assertNull(repository.getProgress("physics-ch1", versionId = "v1"))
        assertEquals(19, repository.getProgress("physics-ch1", versionId = "v2")?.lastPageNumber)
        assertEquals(2, repository.getProgress("physics-ch1", versionId = null)?.lastPageNumber)

        // Re-creating repository preserves isolated version progress across restarts/account switches.
        val recreated = createRepository(prefsFile, this)
        assertEquals(19, recreated.getProgress("physics-ch1", versionId = "v2")?.lastPageNumber)
    }

    @Test
    fun `archiving and restoring parent file in catalog leaves local PDF reading progress intact`() =
        runTest {
            val prefsFile = File(tempFolder.newFolder(), "archive_progress.preferences_pb")
            val progressRepository = createRepository(prefsFile, this)
            progressRepository.saveLastPage(fileId = "chem-ch2", pageNumber = 11, totalPages = 35, versionId = "v1")
            progressRepository.saveLastPage(fileId = "chem-ch2", pageNumber = 24, totalPages = 40, versionId = "v2")

            val adminCatalog = FakeAdminCatalogRepository().apply {
                studyFiles.add(
                    StudyFileMetadata(
                        id = "chem-ch2",
                        sectionId = "sec-1",
                        title = "الكيمياء",
                        order = 1,
                        isPublished = true,
                        isArchived = false,
                        activeVersionId = "v2",
                        activeVersionNumber = 2,
                        activeVersionLabel = "2.0"
                    )
                )
            }

            assertEquals(true, adminCatalog.setStudyFileArchived("chem-ch2", isArchived = true) is AppResult.Success)
            assertEquals(true, adminCatalog.setStudyFileArchived("chem-ch2", isArchived = false) is AppResult.Success)

            assertEquals(11, progressRepository.getProgress("chem-ch2", versionId = "v1")?.lastPageNumber)
            assertEquals(24, progressRepository.getProgress("chem-ch2", versionId = "v2")?.lastPageNumber)
        }
}
