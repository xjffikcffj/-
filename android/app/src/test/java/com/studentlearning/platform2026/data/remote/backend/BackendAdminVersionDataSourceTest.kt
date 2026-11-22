package com.studentlearning.platform2026.data.remote.backend

import com.studentlearning.platform2026.data.pdf.BrokeredPdfVersionStorageDataSource
import com.studentlearning.platform2026.data.remote.firebase.AuthTokenProvider
import com.studentlearning.platform2026.domain.model.AdminException
import com.studentlearning.platform2026.domain.model.AdminFailureReason
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
import com.studentlearning.platform2026.domain.repository.PdfVersionUploadSlot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BackendAdminVersionDataSourceTest {

    private val unusedTokenProvider = object : AuthTokenProvider {
        override suspend fun getIdTokenOrNull(forceRefresh: Boolean): String? =
            error("Token provider must not be called before backend configuration check.")
    }

    @Test
    fun `missing backend base url fails closed with BACKEND_NOT_CONFIGURED instead of FIREBASE_NOT_CONFIGURED`() = runTest {
        val dataSource = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = ""),
            authTokenProvider = unusedTokenProvider
        )

        try {
            dataSource.fetchStudyFileVersions("file-1")
            fail("Expected AdminException with BACKEND_NOT_CONFIGURED on fetchStudyFileVersions.")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.BACKEND_NOT_CONFIGURED, expected.reason)
        }

        try {
            dataSource.createStudyFileVersion(
                fileId = "file-1",
                versionNumber = 1,
                versionLabel = "1.0",
                versionNotes = null,
                customVersionId = null
            )
            fail("Expected AdminException with BACKEND_NOT_CONFIGURED on createStudyFileVersion.")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.BACKEND_NOT_CONFIGURED, expected.reason)
        }

        try {
            dataSource.publishStudyFileVersion("file-1", "v1")
            fail("Expected AdminException with BACKEND_NOT_CONFIGURED on publishStudyFileVersion.")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.BACKEND_NOT_CONFIGURED, expected.reason)
        }
    }

    @Test
    fun `non-https backend base url fails closed with BACKEND_NOT_CONFIGURED in production mode`() = runTest {
        val dataSource = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(
                rawBaseUrl = "http://127.0.0.1:8081",
                allowInsecureHttpForTesting = false
            ),
            authTokenProvider = unusedTokenProvider
        )

        try {
            dataSource.fetchStudyFileVersions("file-1")
            fail("Expected AdminException with BACKEND_NOT_CONFIGURED for insecure HTTP URL.")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.BACKEND_NOT_CONFIGURED, expected.reason)
        }
    }

    @Test
    fun `non-https upload slot url throws BACKEND_NOT_CONFIGURED in production mode`() = runTest {
        val dataSource = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(
                rawBaseUrl = "https://api.example.com",
                allowInsecureHttpForTesting = false
            ),
            authTokenProvider = unusedTokenProvider
        )
        val insecureSlot = PdfVersionUploadSlot(
            fileId = "file-1",
            versionId = "v1",
            r2ObjectKey = "uploads-staging/file-1/v1/sess.pdf",
            signedUploadUrl = "http://127.0.0.1:8081/uploads-staging/file-1/v1/sess.pdf",
            uploadSessionId = "11111111-1111-4111-8111-111111111111"
        )
        val payload = "%PDF-1.7\n%%EOF\n".toByteArray(Charsets.UTF_8)
        val binary = PdfUploadBinarySource(
            sizeBytes = payload.size.toLong(),
            sha256Hex = "a".repeat(64),
            displayName = "sample.pdf",
            openInputStream = { payload.inputStream() }
        )

        try {
            dataSource.uploadPdfToSlot(insecureSlot, binary)
            fail("Expected AdminException with BACKEND_NOT_CONFIGURED for non-HTTPS upload slot.")
        } catch (expected: AdminException) {
            assertEquals(AdminFailureReason.BACKEND_NOT_CONFIGURED, expected.reason)
        }
    }

    @Test
    fun `broker remains AWAITING_SECURE_BROKER_INTEGRATION when backend endpoint is missing`() {
        val unconfiguredAdminDataSource = BackendAdminVersionDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = ""),
            authTokenProvider = unusedTokenProvider
        )
        val brokerWithoutClient = BrokeredPdfVersionStorageDataSource(
            brokerEndpointBaseUrl = "",
            backendUploadClient = null
        )
        val brokerWithUnconfiguredClient = BrokeredPdfVersionStorageDataSource(
            brokerEndpointBaseUrl = "https://api.example.com",
            backendUploadClient = unconfiguredAdminDataSource
        )

        assertEquals(
            PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION,
            brokerWithoutClient.getBrokerStatus()
        )
        assertEquals(
            PdfBrokerConfigurationStatus.AWAITING_SECURE_BROKER_INTEGRATION,
            brokerWithUnconfiguredClient.getBrokerStatus()
        )
    }
}
