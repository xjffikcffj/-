package com.studentlearning.platform2026.domain.repository

import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession

enum class PdfBrokerConfigurationStatus {
    /** Secure media broker endpoint is not configured yet in the client app. */
    AWAITING_SECURE_BROKER_INTEGRATION,
    CONFIGURED
}

interface PdfDocumentRepository {
    fun getBrokerConfigurationStatus(): PdfBrokerConfigurationStatus

    suspend fun openDocument(
        fileId: String,
        activeVersionId: String? = null
    ): AppResult<PdfDocumentSession>
}
