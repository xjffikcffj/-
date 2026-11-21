package com.studentlearning.platform2026.data.pdf

import com.studentlearning.platform2026.data.remote.firebase.AuthTokenProvider
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PdfSourceAndFetcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `unconfigured broker reports NO_PUBLISHED_VERSION when versionId is missing and SECURE_SOURCE_NOT_CONFIGURED when present`() =
        runBlocking {
            val source = BrokeredPdfFileSourceDataSource(brokerEndpointBaseUrl = null)
            assertEquals(
                PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION,
                source.getBrokerStatus()
            )

            try {
                source.resolvePdfSource(fileId = "file-1", activeVersionId = null)
                fail("Expected NO_PUBLISHED_VERSION exception")
            } catch (expected: PdfLoadException) {
                assertEquals(PdfFailureReason.NO_PUBLISHED_VERSION, expected.reason)
            }

            try {
                source.resolvePdfSource(fileId = "file-1", activeVersionId = "v1")
                fail("Expected SECURE_SOURCE_NOT_CONFIGURED exception")
            } catch (expected: PdfLoadException) {
                assertEquals(PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED, expected.reason)
            }
        }

    @Test
    fun `configured https broker generates authorized stream and download descriptors`() =
        runBlocking {
            var currentToken: String? = null
            val tokenProvider = object : AuthTokenProvider {
                override suspend fun getIdTokenOrNull(forceRefresh: Boolean): String? = currentToken
            }
            val source = BrokeredPdfFileSourceDataSource(
                brokerEndpointBaseUrl = "https://media.example.invalid/",
                authTokenProvider = tokenProvider
            )
            assertEquals(PdfBrokerConfigurationStatus.CONFIGURED, source.getBrokerStatus())

            val descriptor = source.resolvePdfSource(fileId = "file 1", activeVersionId = "v2")
            assertTrue(descriptor is PdfSourceDescriptor.AuthorizedRemoteUrl)
            val remote = descriptor as PdfSourceDescriptor.AuthorizedRemoteUrl
            assertEquals(
                "https://media.example.invalid/api/study-files/file%201/versions/v2/stream",
                remote.url
            )
            assertTrue(remote.headers.isEmpty())

            // Guest / missing token must be rejected before calling /download.
            try {
                source.resolveDownloadPdfSource(fileId = "file 1", activeVersionId = "v2")
                fail("Expected UNAUTHORIZED_ACCESS when token is missing")
            } catch (expected: PdfLoadException) {
                assertEquals(PdfFailureReason.UNAUTHORIZED_ACCESS, expected.reason)
            }

            currentToken = "jwt-student-1"
            val downloadDescriptor = source.resolveDownloadPdfSource(fileId = "file 1", activeVersionId = "v2")
            assertTrue(downloadDescriptor is PdfSourceDescriptor.AuthorizedRemoteUrl)
            val remoteDownload = downloadDescriptor as PdfSourceDescriptor.AuthorizedRemoteUrl
            assertEquals(
                "https://media.example.invalid/api/study-files/file%201/versions/v2/download",
                remoteDownload.url
            )
            assertEquals("Bearer jwt-student-1", remoteDownload.headers["Authorization"])
        }

    @Test
    fun `fetcher validates PDF magic header and rejects corrupted local files`() = runBlocking {
        val cacheDir = tempFolder.newFolder("pdf_cache")
        val fetcher = HttpPdfContentFetcher(sessionCacheDir = cacheDir)

        val validPdf = File(tempFolder.root, "valid.pdf").apply {
            writeText("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF")
        }
        val prepared = fetcher.prepareReadableFile(
            source = PdfSourceDescriptor.LocalFilePath(validPdf.absolutePath, deleteOnSessionClose = false),
            fileId = "file-valid"
        )
        assertEquals(validPdf.absolutePath, prepared.file.absolutePath)

        val corrupted = File(tempFolder.root, "bad.pdf").apply {
            writeText("NOT_A_PDF_DOCUMENT")
        }
        try {
            fetcher.prepareReadableFile(
                source = PdfSourceDescriptor.LocalFilePath(corrupted.absolutePath),
                fileId = "file-bad"
            )
            fail("Expected CORRUPTED_OR_UNSUPPORTED_FILE")
        } catch (expected: PdfLoadException) {
            assertEquals(PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE, expected.reason)
        }
    }
}
