package com.studentlearning.platform2026.presentation.pdfreader

import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfSearchMatch
import com.studentlearning.platform2026.domain.model.PdfSearchResult
import com.studentlearning.platform2026.domain.model.PdfSearchStatus
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession

data class PdfReaderUiState(
    val fileId: String = "",
    val fileTitle: String = "",
    val activeVersionId: String? = null,
    val isLoading: Boolean = true,
    val errorReason: PdfFailureReason? = null,
    val documentSession: PdfDocumentSession? = null,
    val totalPages: Int = 0,
    val currentPage: Int = 1, // 1-based
    val scrollTargetPage: Int = 1, // 1-based programmatic jump target
    val scrollRequestToken: Int = 0,
    val resumedFromSavedPage: Int? = null,
    val zoomScale: Float = 1.0f,
    val isNightModeEnabled: Boolean = false,
    val areControlsVisible: Boolean = true,
    val isSearchBarVisible: Boolean = false,
    val searchQuery: String = "",
    val searchResult: PdfSearchResult = PdfSearchResult(
        query = "",
        status = PdfSearchStatus.IDLE
    ),
    val activeMatchCursor: Int = -1,
    val isPageJumpDialogVisible: Boolean = false
) {
    val activeMatch: PdfSearchMatch?
        get() = searchResult.matches.getOrNull(activeMatchCursor)

    val canNavigatePreviousPage: Boolean
        get() = documentSession != null && currentPage > 1

    val canNavigateNextPage: Boolean
        get() = documentSession != null && currentPage < totalPages

    val canZoomIn: Boolean
        get() = documentSession != null && zoomScale < MAX_ZOOM_SCALE - 0.01f

    val canZoomOut: Boolean
        get() = documentSession != null && zoomScale > MIN_ZOOM_SCALE + 0.01f

    companion object {
        const val MIN_ZOOM_SCALE = 1.0f
        const val MAX_ZOOM_SCALE = 3.0f
        const val ZOOM_STEP = 0.25f
    }
}
