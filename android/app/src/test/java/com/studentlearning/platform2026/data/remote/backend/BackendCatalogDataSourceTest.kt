package com.studentlearning.platform2026.data.remote.backend

import com.studentlearning.platform2026.domain.model.CatalogException
import com.studentlearning.platform2026.domain.model.CatalogFailureReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BackendCatalogDataSourceTest {

    @Test
    fun `missing backend base url fails closed with BACKEND_NOT_CONFIGURED instead of FIREBASE_NOT_CONFIGURED`() = runTest {
        val dataSource = BackendCatalogDataSource(
            httpClient = BackendHttpClient(rawBaseUrl = "")
        )

        try {
            dataSource.fetchPublishedBranches()
            fail("Expected CatalogException with BACKEND_NOT_CONFIGURED when backend URL is empty.")
        } catch (expected: CatalogException) {
            assertEquals(CatalogFailureReason.BACKEND_NOT_CONFIGURED, expected.reason)
        }
    }

    @Test
    fun `non-https backend base url fails closed with BACKEND_NOT_CONFIGURED in production mode`() = runTest {
        val dataSource = BackendCatalogDataSource(
            httpClient = BackendHttpClient(
                rawBaseUrl = "http://127.0.0.1:8081",
                allowInsecureHttpForTesting = false
            )
        )

        try {
            dataSource.fetchPublishedStudyFiles("sec-1")
            fail("Expected CatalogException with BACKEND_NOT_CONFIGURED for insecure HTTP URL.")
        } catch (expected: CatalogException) {
            assertEquals(CatalogFailureReason.BACKEND_NOT_CONFIGURED, expected.reason)
        }
    }
}
