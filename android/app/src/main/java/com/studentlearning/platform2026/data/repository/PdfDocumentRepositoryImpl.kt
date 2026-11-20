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
import com.studentlearning.platform2026.domain.repository.OfflineDownloadsRepository
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import com.studentlearning.platform2026.domain.repository.PdfDocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class PdfDocumentRepositoryImpl(
    private val sourceDataSource: PdfFileSourceDataSource,
    private val contentFetcher: PdfContentFetcher,
    private val sessionFactory: PdfDocumentSessionFactory,
    private val dispatcherProvider: DispatcherProvider,
    private val offlineDownloadsRepository: OfflineDownloadsRepository? = null
) : PdfDocumentRepository {

    override fun getBrokerConfigurationStatus(): PdfBrokerConfigurationStatus =
        sourceDataSource.getBrokerStatus()

    override suspend fun openDocument(
        fileId: String,
        activeVersionId: String?
    ): AppResult<PdfDocumentSession> {
        return withContext(dispatcherProvider.io) {
            var preparedFile: PreparedPdfFile? = null
            try {
                val verifiedOffline = offlineDownloadsRepository?.getVerifiedLocalVersion(
                    fileId = fileId,
                    versionId = activeVersionId
                )
                val descriptor = if (verifiedOffline != null) {
                    PdfSourceDescriptor.LocalFilePath(
                        absolutePath = verifiedOffline.localFilePath,
                        deleteOnSessionClose = false
                    )
                } else {
                    sourceDataSource.resolvePdfSource(
                        fileId = fileId,
                        activeVersionId = activeVersionId
                    )
                }
                preparedFile = contentFetcher.prepareReadableFile(
                    source = descriptor,
                    fileId = fileId
                )
                val session = sessionFactory.openSession(
                    fileId = fileId,
                    preparedFile = preparedFile
                )
                AppResult.Success(session)
            } catch (cancelled: CancellationException) {
                cleanupPreparedFileIfUnused(preparedFile)
                throw cancelled
            } catch (pdfError: PdfLoadException) {
                cleanupPreparedFileIfUnused(preparedFile)
                AppResult.Error(
                    message = pdfError.message ?: "Failed to open PDF document",
                    cause = pdfError
                )
            } catch (throwable: Throwable) {
                cleanupPreparedFileIfUnused(preparedFile)
                val wrapped = PdfLoadException(
                    reason = PdfFailureReason.UNKNOWN,
                    message = throwable.localizedMessage ?: "Unexpected error opening PDF document",
                    cause = throwable
                )
                AppResult.Error(
                    message = wrapped.message.orEmpty(),
                    cause = wrapped
                )
            }
        }
    }

    private fun cleanupPreparedFileIfUnused(preparedFile: PreparedPdfFile?) {
        if (preparedFile != null && preparedFile.deleteOnClose) {
            runCatching { preparedFile.file.delete() }
        }
    }
}
