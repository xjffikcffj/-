package com.studentlearning.platform2026.presentation.downloads

import com.studentlearning.platform2026.domain.model.AdminRole
import com.studentlearning.platform2026.domain.model.AdminSession
import com.studentlearning.platform2026.domain.model.AppUserRole
import com.studentlearning.platform2026.domain.model.LocalDownloadedFileVersion
import com.studentlearning.platform2026.fakes.FakeAuthRepository
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
                listOf("file-1" to "v1", "file-1" to "v2"),
                viewModel.uiState.value.savedVersions.map { it.fileId to it.versionId }
            )

            viewModel.deleteLocalVersion(fileId = "file-1", versionId = "v1")
            advanceUntilIdle()

            assertEquals(
                listOf("file-1" to "v2"),
                viewModel.uiState.value.savedVersions.map { it.fileId to it.versionId }
            )
        }
}
