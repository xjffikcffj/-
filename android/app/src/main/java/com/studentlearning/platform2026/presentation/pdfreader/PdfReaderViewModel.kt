package com.studentlearning.platform2026.presentation.pdfreader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studentlearning.platform2026.core.common.AppResult
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfLoadException
import com.studentlearning.platform2026.domain.model.PdfSearchResult
import com.studentlearning.platform2026.domain.model.PdfSearchStatus
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.studentlearning.platform2026.domain.repository.PdfDocumentRepository
import com.studentlearning.platform2026.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfReaderViewModel(
    private val pdfDocumentRepository: PdfDocumentRepository,
    private val readingProgressRepository: ReadingProgressRepository,
    initialFileId: String = "",
    initialFileTitle: String = "",
    initialActiveVersionId: String? = null,
    autoOpen: Boolean = true
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PdfReaderUiState(
            fileId = initialFileId,
            fileTitle = initialFileTitle,
            activeVersionId = initialActiveVersionId,
            isLoading = initialFileId.isNotBlank() && autoOpen
        )
    )
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    private var activeSession: PdfDocumentSession? = null
    private var openJob: Job? = null
    private var searchJob: Job? = null
    private var progressSaveJob: Job? = null
    private var loadGeneration: Long = 0L

    init {
        if (autoOpen && initialFileId.isNotBlank()) {
            openFile(
                fileId = initialFileId,
                fileTitle = initialFileTitle,
                activeVersionId = initialActiveVersionId
            )
        }
    }

    fun openFile(
        fileId: String,
        fileTitle: String,
        activeVersionId: String? = null
    ) {
        val current = _uiState.value
        val sameTargetOpen = activeSession != null &&
            current.fileId == fileId &&
            current.activeVersionId == activeVersionId &&
            current.errorReason == null
        if (sameTargetOpen) {
            if (current.fileTitle != fileTitle) {
                _uiState.update { it.copy(fileTitle = fileTitle) }
            }
            return
        }

        val generation = ++loadGeneration
        openJob?.cancel()
        searchJob?.cancel()
        progressSaveJob?.cancel()
        closeActiveSession()

        _uiState.update { state ->
            state.copy(
                fileId = fileId,
                fileTitle = fileTitle,
                activeVersionId = activeVersionId,
                isLoading = true,
                errorReason = null,
                documentSession = null,
                totalPages = 0,
                currentPage = 1,
                scrollTargetPage = 1,
                resumedFromSavedPage = null,
                zoomScale = 1.0f,
                isSearchBarVisible = false,
                searchQuery = "",
                searchResult = PdfSearchResult(query = "", status = PdfSearchStatus.IDLE),
                activeMatchCursor = -1,
                isPageJumpDialogVisible = false
            )
        }

        if (fileId.isBlank()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorReason = PdfFailureReason.FILE_NOT_FOUND
                )
            }
            return
        }

        openJob = viewModelScope.launch {
            val openResult = pdfDocumentRepository.openDocument(
                fileId = fileId,
                activeVersionId = activeVersionId
            )

            if (!isActive || generation != loadGeneration) {
                if (openResult is AppResult.Success) {
                    runCatching { openResult.data.close() }
                }
                return@launch
            }

            when (openResult) {
                is AppResult.Success -> {
                    val session = openResult.data
                    val count = session.pageCount
                    if (count <= 0) {
                        runCatching { session.close() }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                documentSession = null,
                                errorReason = PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE
                            )
                        }
                        return@launch
                    }

                    val savedProgress = runCatching {
                        readingProgressRepository.getProgress(fileId)
                    }.getOrNull()

                    if (!isActive || generation != loadGeneration) {
                        runCatching { session.close() }
                        return@launch
                    }

                    val initialPage = (savedProgress?.lastPageNumber ?: 1).coerceIn(1, count)
                    activeSession = session

                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            errorReason = null,
                            documentSession = session,
                            totalPages = count,
                            currentPage = initialPage,
                            scrollTargetPage = initialPage,
                            scrollRequestToken = state.scrollRequestToken + 1,
                            resumedFromSavedPage = if (initialPage > 1) initialPage else null
                        )
                    }

                    persistPageImmediately(fileId = fileId, pageNumber = initialPage, totalPages = count)
                }

                is AppResult.Error -> {
                    val reason = (openResult.cause as? PdfLoadException)?.reason
                        ?: PdfFailureReason.UNKNOWN
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            documentSession = null,
                            errorReason = reason
                        )
                    }
                }

                AppResult.Loading -> Unit
            }
        }
    }

    fun retry() {
        val current = _uiState.value
        val previousGeneration = loadGeneration
        loadGeneration = previousGeneration + 1L
        openJob?.cancel()
        searchJob?.cancel()
        progressSaveJob?.cancel()
        closeActiveSession()
        openFileForce(
            fileId = current.fileId,
            fileTitle = current.fileTitle,
            activeVersionId = current.activeVersionId
        )
    }

    private fun openFileForce(
        fileId: String,
        fileTitle: String,
        activeVersionId: String?
    ) {
        _uiState.update { it.copy(documentSession = null, errorReason = null) }
        openFile(fileId = fileId, fileTitle = fileTitle, activeVersionId = activeVersionId)
    }

    fun onVisiblePageChanged(pageNumberOneBased: Int) {
        val current = _uiState.value
        val total = current.totalPages
        if (current.documentSession == null || total <= 0) return

        val clampedPage = pageNumberOneBased.coerceIn(1, total)
        if (clampedPage == current.currentPage) return

        _uiState.update { it.copy(currentPage = clampedPage) }
        scheduleProgressSave(fileId = current.fileId, pageNumber = clampedPage, totalPages = total)
    }

    fun goToPage(pageNumberOneBased: Int) {
        val current = _uiState.value
        val total = current.totalPages
        if (current.documentSession == null || total <= 0) return

        val clampedPage = pageNumberOneBased.coerceIn(1, total)
        _uiState.update { state ->
            state.copy(
                currentPage = clampedPage,
                scrollTargetPage = clampedPage,
                scrollRequestToken = state.scrollRequestToken + 1,
                isPageJumpDialogVisible = false
            )
        }
        persistPageImmediately(fileId = current.fileId, pageNumber = clampedPage, totalPages = total)
    }

    fun nextPage() {
        val current = _uiState.value
        if (current.canNavigateNextPage) {
            goToPage(current.currentPage + 1)
        }
    }

    fun previousPage() {
        val current = _uiState.value
        if (current.canNavigatePreviousPage) {
            goToPage(current.currentPage - 1)
        }
    }

    fun zoomIn() {
        val current = _uiState.value
        setZoomScale(current.zoomScale + PdfReaderUiState.ZOOM_STEP)
    }

    fun zoomOut() {
        val current = _uiState.value
        setZoomScale(current.zoomScale - PdfReaderUiState.ZOOM_STEP)
    }

    fun resetZoom() {
        setZoomScale(PdfReaderUiState.MIN_ZOOM_SCALE)
    }

    fun setZoomScale(scale: Float) {
        val clamped = scale.coerceIn(
            PdfReaderUiState.MIN_ZOOM_SCALE,
            PdfReaderUiState.MAX_ZOOM_SCALE
        )
        _uiState.update { it.copy(zoomScale = clamped) }
    }

    fun toggleNightMode() {
        _uiState.update { it.copy(isNightModeEnabled = !it.isNightModeEnabled) }
    }

    fun toggleControlsVisibility() {
        _uiState.update { it.copy(areControlsVisible = !it.areControlsVisible) }
    }

    fun openPageJumpDialog() {
        if (_uiState.value.documentSession == null) return
        _uiState.update { it.copy(isPageJumpDialogVisible = true) }
    }

    fun dismissPageJumpDialog() {
        _uiState.update { it.copy(isPageJumpDialogVisible = false) }
    }

    fun toggleSearchBar() {
        _uiState.update { state ->
            val nextVisible = !state.isSearchBarVisible
            state.copy(
                isSearchBarVisible = nextVisible,
                areControlsVisible = if (nextVisible) true else state.areControlsVisible
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = "",
                searchResult = PdfSearchResult(query = "", status = PdfSearchStatus.IDLE),
                activeMatchCursor = -1
            )
        }
    }

    fun executeSearch() {
        val session = activeSession ?: return
        val current = _uiState.value
        val trimmedQuery = current.searchQuery.trim()
        if (trimmedQuery.isEmpty()) {
            _uiState.update {
                it.copy(
                    searchResult = PdfSearchResult(query = "", status = PdfSearchStatus.IDLE),
                    activeMatchCursor = -1
                )
            }
            return
        }

        val generation = loadGeneration
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                searchResult = PdfSearchResult(
                    query = trimmedQuery,
                    status = PdfSearchStatus.SEARCHING
                ),
                activeMatchCursor = -1
            )
        }

        searchJob = viewModelScope.launch {
            try {
                val result = session.searchText(trimmedQuery)
                if (!isActive || generation != loadGeneration || activeSession !== session) return@launch

                val initialCursor = if (result.matches.isNotEmpty()) 0 else -1
                _uiState.update {
                    it.copy(
                        searchResult = result,
                        activeMatchCursor = initialCursor
                    )
                }
                if (initialCursor >= 0) {
                    goToPage(result.matches[initialCursor].pageNumber)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!isActive || generation != loadGeneration) return@launch
                _uiState.update {
                    it.copy(
                        searchResult = PdfSearchResult(
                            query = trimmedQuery,
                            status = PdfSearchStatus.ERROR
                        ),
                        activeMatchCursor = -1
                    )
                }
            }
        }
    }

    fun nextSearchMatch() {
        val matches = _uiState.value.searchResult.matches
        if (matches.isEmpty()) return
        val currentCursor = _uiState.value.activeMatchCursor
        val nextCursor = if (currentCursor < 0) 0 else (currentCursor + 1) % matches.size
        _uiState.update { it.copy(activeMatchCursor = nextCursor) }
        goToPage(matches[nextCursor].pageNumber)
    }

    fun previousSearchMatch() {
        val matches = _uiState.value.searchResult.matches
        if (matches.isEmpty()) return
        val currentCursor = _uiState.value.activeMatchCursor
        val prevCursor = if (currentCursor <= 0) matches.lastIndex else currentCursor - 1
        _uiState.update { it.copy(activeMatchCursor = prevCursor) }
        goToPage(matches[prevCursor].pageNumber)
    }

    private fun scheduleProgressSave(fileId: String, pageNumber: Int, totalPages: Int) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            readingProgressRepository.saveLastPage(
                fileId = fileId,
                pageNumber = pageNumber,
                totalPages = totalPages
            )
        }
    }

    private fun persistPageImmediately(fileId: String, pageNumber: Int, totalPages: Int) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            withContext(NonCancellable) {
                readingProgressRepository.saveLastPage(
                    fileId = fileId,
                    pageNumber = pageNumber,
                    totalPages = totalPages
                )
            }
        }
    }

    private fun closeActiveSession() {
        val session = activeSession ?: return
        activeSession = null
        runCatching { session.close() }
    }

    override fun onCleared() {
        openJob?.cancel()
        searchJob?.cancel()
        progressSaveJob?.cancel()
        _uiState.update { it.copy(documentSession = null) }
        closeActiveSession()
        super.onCleared()
    }

    companion object {
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 300L

        fun provideFactory(
            pdfDocumentRepository: PdfDocumentRepository,
            readingProgressRepository: ReadingProgressRepository,
            fileId: String,
            fileTitle: String,
            activeVersionId: String?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PdfReaderViewModel(
                    pdfDocumentRepository = pdfDocumentRepository,
                    readingProgressRepository = readingProgressRepository,
                    initialFileId = fileId,
                    initialFileTitle = fileTitle,
                    initialActiveVersionId = activeVersionId,
                    autoOpen = true
                ) as T
            }
        }
    }
}
