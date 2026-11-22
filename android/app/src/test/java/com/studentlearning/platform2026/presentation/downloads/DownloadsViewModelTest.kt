package com.studentlearning.platform2026.presentation.downloads

import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.domain.model.FavoriteStudyFile
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.fakes.FakeAuthRepository
import com.studentlearning.platform2026.fakes.FakeLocalFileOrganizationRepository
import com.studentlearning.platform2026.fakes.FakeOfflineDownloadsRepository
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial downloads state is initialized without fake saved files`() {
        val viewModel = DownloadsViewModel()
        val state = viewModel.uiState.value

        assertTrue(state.isInitialized)
        assertFalse(state.hasSavedFiles)
        assertEquals(AppUserRole.GUEST, state.userRole)
        assertFalse(state.canDownloadOfflineFiles)
    }

    @Test
    fun `downloads capability reflects guest restriction vs signed in student`() = runTest {
        val authRepository = FakeAuthRepository(initialSession = AdminSession())
        val viewModel = DownloadsViewModel(authRepository = authRepository)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        assertEquals(AppUserRole.GUEST, viewModel.uiState.value.userRole)
        assertFalse(viewModel.uiState.value.canDownloadOfflineFiles)

        authRepository.setSession(
            AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        advanceUntilIdle()

        assertEquals(AppUserRole.STUDENT, viewModel.uiState.value.userRole)
        assertTrue(viewModel.uiState.value.canDownloadOfflineFiles)
    }

    @Test
    fun `lists multiple saved versions independently and deletes only target fileId plus versionId`() =
        runTest {
            val authRepository = FakeAuthRepository(
                initialSession = AdminSession(
                    isSignedIn = true,
                    uid = "student-1",
                    email = "student@example.com",
                    role = AdminRole.NONE,
                    hasLoadedTokenClaims = true,
                    areClaimsConfirmedCurrent = true
                )
            )
            val downloadsRepo = FakeOfflineDownloadsRepository(authRepository).apply {
                putSavedVersion(
                    LocalDownloadedFileVersion(
                        fileId = "file-1",
                        versionId = "v1",
                        versionNumber = 1,
                        versionLabel = "1.0",
                        fileTitle = "ملخص الرياضيات",
                        sectionId = "sec-1",
                        localFilePath = "/private/file-1/v1.pdf",
                        fileSizeBytes = 2048L,
                        checksumSha256 = "sha1",
                        downloadedAtEpochMillis = 1_700_000_000_000L
                    )
                )
                putSavedVersion(
                    LocalDownloadedFileVersion(
                        fileId = "file-1",
                        versionId = "v2",
                        versionNumber = 2,
                        versionLabel = "2.0",
                        fileTitle = "ملخص الرياضيات",
                        sectionId = "sec-1",
                        localFilePath = "/private/file-1/v2.pdf",
                        fileSizeBytes = 4096L,
                        checksumSha256 = "sha2",
                        downloadedAtEpochMillis = 1_700_000_050_000L
                    )
                )
            }
            val viewModel = DownloadsViewModel(
                authRepository = authRepository,
                offlineDownloadsRepository = downloadsRepo
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect()
            }
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hasSavedFiles)
            assertEquals(
                listOf("file-1" to "v2", "file-1" to "v1"),
                viewModel.uiState.value.savedVersions.map { it.fileId to it.versionId }
            )

            viewModel.deleteLocalVersion(fileId = "file-1", versionId = "v1")
            advanceUntilIdle()

            assertEquals(
                listOf("file-1" to "v2"),
                viewModel.uiState.value.savedVersions.map { it.fileId to it.versionId }
            )
        }

    @Test
    fun `favorites list reflects local removals and downloads reorder supports save cancel and reset`() = runTest {
        val authRepository = FakeAuthRepository(
            initialSession = AdminSession(
                isSignedIn = true,
                uid = "student-1",
                email = "student@example.com",
                role = AdminRole.NONE,
                hasLoadedTokenClaims = true,
                areClaimsConfirmedCurrent = true
            )
        )
        val downloadsRepo = FakeOfflineDownloadsRepository(authRepository).apply {
            putSavedVersion(
                LocalDownloadedFileVersion(
                    fileId = "file-a",
                    versionId = "v1",
                    versionNumber = 1,
                    versionLabel = "1.0",
                    fileTitle = "ملف أ",
                    sectionId = "sec-1",
                    localFilePath = "/private/a.pdf",
                    fileSizeBytes = 1024L,
                    checksumSha256 = "sha-a",
                    downloadedAtEpochMillis = 1_700_000_010_000L
                )
            )
            putSavedVersion(
                LocalDownloadedFileVersion(
                    fileId = "file-b",
                    versionId = "v1",
                    versionNumber = 1,
                    versionLabel = "1.0",
                    fileTitle = "ملف ب",
                    sectionId = "sec-1",
                    localFilePath = "/private/b.pdf",
                    fileSizeBytes = 1024L,
                    checksumSha256 = "sha-b",
                    downloadedAtEpochMillis = 1_700_000_020_000L
                )
            )
        }
        val orgRepo = FakeLocalFileOrganizationRepository(
            initialFavorites = listOf(
                FavoriteStudyFile(
                    fileId = "file-a",
                    sectionId = "sec-1",
                    title = "ملف أ",
                    savedAtEpochMillis = 1_700_000_000_000L
                )
            )
        )
        val viewModel = DownloadsViewModel(
            authRepository = authRepository,
            offlineDownloadsRepository = downloadsRepo,
            localFileOrganizationRepository = orgRepo
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        // Initial downloads are ordered newest-first (file-b, file-a) and favorite is exposed
        assertEquals(listOf("file-a"), viewModel.uiState.value.favoriteFiles.map { it.fileId })
        assertEquals(listOf("file-b", "file-a"), viewModel.uiState.value.savedVersions.map { it.fileId })

        viewModel.removeFavorite("file-a")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.favoriteFiles.isEmpty())

        // Cancel reorder discards draft move
        viewModel.enterReorderMode()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReorderMode)
        viewModel.moveSavedVersionInDraftOrder(fileId = "file-a", versionId = "v1", moveUp = true)
        advanceUntilIdle()
        assertEquals(listOf("file-a", "file-b"), viewModel.uiState.value.savedVersions.map { it.fileId })

        viewModel.cancelReorder()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isReorderMode)
        assertEquals(listOf("file-b", "file-a"), viewModel.uiState.value.savedVersions.map { it.fileId })
        assertEquals(0, orgRepo.saveOrderCalls)

        // Save reorder persists locally
        viewModel.enterReorderMode()
        advanceUntilIdle()
        viewModel.moveSavedVersionInDraftOrder(fileId = "file-a", versionId = "v1", moveUp = true)
        viewModel.saveReorder()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isReorderMode)
        assertTrue(viewModel.uiState.value.hasCustomOrder)
        assertEquals(listOf("file-a", "file-b"), viewModel.uiState.value.savedVersions.map { it.fileId })
        assertEquals(1, orgRepo.saveOrderCalls)

        // Reset custom order restores default order
        viewModel.resetCustomOrder()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasCustomOrder)
        assertEquals(listOf("file-b", "file-a"), viewModel.uiState.value.savedVersions.map { it.fileId })
        assertEquals(1, orgRepo.clearOrderCalls)
    }
}
