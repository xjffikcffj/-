package com.studentlearning.platform2026.data.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.pdf.PdfContentFetcher
import com.studentlearning.platform2026.data.pdf.PdfDocumentSessionFactory
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.data.pdf.PreparedPdfFile
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.fakes.FakePdfDocumentSession
import com.studentlearning.platform2026.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PdfDocumentRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val io: CoroutineDispatcher = mainDispatcherRule.testDispatcher
        override val default: CoroutineDispatcher = mainDispatcherRule.testDispatcher
    }

    @Test
    fun `deletes ephemeral temp file when session opening fails or throws cancellation`() = runTest {
        val tempFile1 = File(tempFolder.root, "temp_corrupt.pdf").apply { writeText("%PDF-1.4") }
        val tempFile2 = File(tempFolder.root, "temp_cancel.pdf").apply { writeText("%PDF-1.4") }

        val sourceDataSource = object : PdfFileSourceDataSource {
            override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
            override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                PdfSourceDescriptor.AuthorizedRemoteUrl(
                    url = "https://example.invalid/$fileId.pdf",
                    cacheKey = fileId
                )
        }

        var nextTempFile = tempFile1
        val fetcher = object : PdfContentFetcher {
            override suspend fun prepareReadableFile(source: PdfSourceDescriptor, fileId: String) =
                PreparedPdfFile(file = nextTempFile, deleteOnClose = true)
        }

        var failureToThrow: Throwable = PdfLoadException(PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE)
        val factory = object : PdfDocumentSessionFactory {
            override suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile): PdfDocumentSession {
                throw failureToThrow
            }
        }

        val repo = PdfDocumentRepositoryImpl(sourceDataSource, fetcher, factory, testDispatchers)

        val errResult = repo.openDocument("file-1", "v1")
        assertTrue(errResult is AppResult.Error)
        assertFalse(tempFile1.exists())

        nextTempFile = tempFile2
        failureToThrow = CancellationException("Aborted during open")
        try {
            repo.openDocument("file-2", "v1")
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            assertFalse(tempFile2.exists())
        }
    }

    @Test
    fun `preserves non-ephemeral local file when opening fails and returns session on success`() = runTest {
        val persistentLocalFile = File(tempFolder.root, "local_book.pdf").apply { writeText("%PDF-1.7") }

        val sourceDataSource = object : PdfFileSourceDataSource {
            override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
            override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                PdfSourceDescriptor.LocalFilePath(
                    absolutePath = persistentLocalFile.absolutePath,
                    deleteOnSessionClose = false
                )
        }
        val fetcher = object : PdfContentFetcher {
            override suspend fun prepareReadableFile(source: PdfSourceDescriptor, fileId: String) =
                PreparedPdfFile(file = persistentLocalFile, deleteOnClose = false)
        }

        var shouldFail = true
        val expectedSession = FakePdfDocumentSession(fileId = "local-1", pageCount = 5)
        val factory = object : PdfDocumentSessionFactory {
            override suspend fun openSession(fileId: String, preparedFile: PreparedPdfFile): PdfDocumentSession {
                if (shouldFail) throw IOException("Transient read error")
                return expectedSession
            }
        }

        val repo = PdfDocumentRepositoryImpl(sourceDataSource, fetcher, factory, testDispatchers)

        val failed = repo.openDocument("local-1", "v1")
        assertTrue(failed is AppResult.Error)
        assertTrue(persistentLocalFile.exists())

        shouldFail = false
        val succeeded = repo.openDocument("local-1", "v1")
        assertTrue(succeeded is AppResult.Success)
        assertEquals(expectedSession, (succeeded as AppResult.Success).data)
    }
}
