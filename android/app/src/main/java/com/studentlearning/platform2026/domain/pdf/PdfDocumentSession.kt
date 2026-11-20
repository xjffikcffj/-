package com.studentlearning.platform2026.domain.pdf

import com.studentlearning.platform2026.domain.model.PdfSearchResult

/**
 * Pure domain handle representing an opened PDF document session.
 *
 * Implementations back page rendering and text extraction on-demand so large documents
 * are never loaded completely into memory, and must release all native descriptors/streams
 * in [close].
 */
interface PdfDocumentSession : AutoCloseable {
    val fileId: String
    val pageCount: Int

    suspend fun getPageAspectRatio(pageIndexZeroBased: Int): Float

    suspend fun searchText(query: String): PdfSearchResult

    override fun close()
}
