package com.studentlearning.platform2026.presentation.pdfreader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studentlearning.platform2026.R
import com.studentlearning.platform2026.data.pdf.PdfPageBitmapRenderer
import com.studentlearning.platform2026.data.pdf.PdfRenderSizing
import com.studentlearning.platform2026.domain.model.PdfFailureReason
import com.studentlearning.platform2026.domain.model.PdfSearchStatus
import com.studentlearning.platform2026.domain.pdf.PdfDocumentSession
import com.studentlearning.platform2026.presentation.components.HaqibatiSurfaceCard
import com.studentlearning.platform2026.presentation.theme.HaqibatiThemeTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun PdfReaderScreen(
    viewModel: PdfReaderViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PdfReaderScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::retry,
        onVisiblePageChanged = viewModel::onVisiblePageChanged,
        onPreviousPage = viewModel::previousPage,
        onNextPage = viewModel::nextPage,
        onOpenPageJump = viewModel::openPageJumpDialog,
        onDismissPageJump = viewModel::dismissPageJumpDialog,
        onJumpToPage = viewModel::goToPage,
        onZoomIn = viewModel::zoomIn,
        onZoomOut = viewModel::zoomOut,
        onResetZoom = viewModel::resetZoom,
        onSetZoomScale = viewModel::setZoomScale,
        onToggleNightMode = viewModel::toggleNightMode,
        onToggleControls = viewModel::toggleControlsVisibility,
        onToggleSearch = viewModel::toggleSearchBar,
        onUpdateSearchQuery = viewModel::updateSearchQuery,
        onExecuteSearch = viewModel::executeSearch,
        onClearSearch = viewModel::clearSearch,
        onNextMatch = viewModel::nextSearchMatch,
        onPreviousMatch = viewModel::previousSearchMatch,
        modifier = modifier
    )
}

@Composable
fun PdfReaderScreenContent(
    uiState: PdfReaderUiState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onVisiblePageChanged: (Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onOpenPageJump: () -> Unit,
    onDismissPageJump: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onSetZoomScale: (Float) -> Unit,
    onToggleNightMode: () -> Unit,
    onToggleControls: () -> Unit,
    onToggleSearch: () -> Unit,
    onUpdateSearchQuery: (String) -> Unit,
    onExecuteSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onNextMatch: () -> Unit,
    onPreviousMatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val readerBackground = if (uiState.isNightModeEnabled) {
        Color(0xFF111317)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    val chromeContainerColor = if (uiState.isNightModeEnabled) {
        Color(0xFF1C2026)
    } else {
        MaterialTheme.colorScheme.surface
    }

    val chromeContentColor = if (uiState.isNightModeEnabled) {
        Color(0xFFE7EAEF)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = readerBackground,
        topBar = {
            AnimatedVisibility(
                visible = uiState.areControlsVisible || uiState.documentSession == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PdfReaderTopBar(
                        title = uiState.fileTitle.ifBlank {
                            stringResource(R.string.pdf_reader_default_title)
                        },
                        hasDocumentLoaded = uiState.documentSession != null,
                        isNightModeEnabled = uiState.isNightModeEnabled,
                        containerColor = chromeContainerColor,
                        contentColor = chromeContentColor,
                        onNavigateBack = onNavigateBack,
                        onToggleSearch = onToggleSearch,
                        onToggleNightMode = onToggleNightMode
                    )

                    if (uiState.isSearchBarVisible && uiState.documentSession != null) {
                        PdfSearchPanel(
                            uiState = uiState,
                            containerColor = chromeContainerColor,
                            contentColor = chromeContentColor,
                            onQueryChange = onUpdateSearchQuery,
                            onExecuteSearch = onExecuteSearch,
                            onClearSearch = onClearSearch,
                            onPreviousMatch = onPreviousMatch,
                            onNextMatch = onNextMatch
                        )
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = uiState.areControlsVisible && uiState.documentSession != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PdfReaderBottomToolbar(
                    uiState = uiState,
                    containerColor = chromeContainerColor,
                    contentColor = chromeContentColor,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    onOpenPageJump = onOpenPageJump,
                    onZoomOut = onZoomOut,
                    onResetZoom = onResetZoom,
                    onZoomIn = onZoomIn
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isLoading -> {
                    PdfLoadingView(isNightMode = uiState.isNightModeEnabled)
                }

                uiState.errorReason != null -> {
                    PdfErrorView(
                        reason = uiState.errorReason,
                        isNightMode = uiState.isNightModeEnabled,
                        onRetry = onRetry,
                        onNavigateBack = onNavigateBack
                    )
                }

                uiState.documentSession != null -> {
                    key(uiState.fileId, uiState.activeVersionId) {
                        PdfPagesViewport(
                            uiState = uiState,
                            session = uiState.documentSession,
                            onVisiblePageChanged = onVisiblePageChanged,
                            onSetZoomScale = onSetZoomScale,
                            onToggleControls = onToggleControls
                        )
                    }
                }
            }
        }
    }

    if (uiState.isPageJumpDialogVisible && uiState.totalPages > 0) {
        PdfPageJumpDialog(
            currentPage = uiState.currentPage,
            totalPages = uiState.totalPages,
            onDismiss = onDismissPageJump,
            onConfirmJump = onJumpToPage
        )
    }
}

@Composable
private fun PdfReaderTopBar(
    title: String,
    hasDocumentLoaded: Boolean,
    isNightModeEnabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    onNavigateBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleNightMode: () -> Unit
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (hasDocumentLoaded) {
                    IconButton(onClick = onToggleSearch) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.pdf_reader_toggle_search)
                        )
                    }
                    IconButton(onClick = onToggleNightMode) {
                        Icon(
                            imageVector = if (isNightModeEnabled) {
                                Icons.Rounded.LightMode
                            } else {
                                Icons.Rounded.DarkMode
                            },
                            contentDescription = stringResource(
                                if (isNightModeEnabled) {
                                    R.string.pdf_reader_day_mode
                                } else {
                                    R.string.pdf_reader_night_mode
                                }
                            )
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun PdfSearchPanel(
    uiState: PdfReaderUiState,
    containerColor: Color,
    contentColor: Color,
    onQueryChange: (String) -> Unit,
    onExecuteSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    Surface(
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.pdf_reader_search_hint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onExecuteSearch() }),
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = onClearSearch) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.pdf_reader_search_clear)
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = onExecuteSearch,
                    enabled = uiState.searchQuery.isNotBlank() &&
                        uiState.searchResult.status != PdfSearchStatus.SEARCHING
                ) {
                    Text(text = stringResource(R.string.pdf_reader_search_execute))
                }
            }

            when (uiState.searchResult.status) {
                PdfSearchStatus.IDLE -> Unit
                PdfSearchStatus.SEARCHING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.pdf_reader_search_searching),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.78f)
                    )
                }

                PdfSearchStatus.FOUND -> {
                    val matches = uiState.searchResult.matches
                    val currentDisplay = (uiState.activeMatchCursor + 1).coerceAtLeast(1)
                    val activePage = uiState.activeMatch?.pageNumber ?: uiState.currentPage
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.pdf_reader_search_match_counter,
                                currentDisplay,
                                matches.size,
                                activePage
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onPreviousMatch) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.pdf_reader_search_prev_match)
                            )
                        }
                        IconButton(onClick = onNextMatch) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.pdf_reader_search_next_match)
                            )
                        }
                    }
                }

                PdfSearchStatus.NO_RESULTS -> {
                    Text(
                        text = stringResource(R.string.pdf_reader_search_no_results),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                PdfSearchStatus.NO_EXTRACTABLE_TEXT -> {
                    Text(
                        text = stringResource(R.string.pdf_reader_search_no_extractable_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.85f)
                    )
                }

                PdfSearchStatus.ERROR -> {
                    Text(
                        text = stringResource(R.string.pdf_reader_search_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun PdfReaderBottomToolbar(
    uiState: PdfReaderUiState,
    containerColor: Color,
    contentColor: Color,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onOpenPageJump: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onZoomIn: () -> Unit
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 6.dp
    ) {
        Column {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPreviousPage,
                        enabled = uiState.canNavigatePreviousPage
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.pdf_reader_previous_page)
                        )
                    }

                    TextButton(onClick = onOpenPageJump) {
                        Text(
                            text = stringResource(
                                R.string.pdf_reader_page_indicator,
                                uiState.currentPage,
                                uiState.totalPages
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }

                    IconButton(
                        onClick = onNextPage,
                        enabled = uiState.canNavigateNextPage
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.pdf_reader_next_page)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onZoomOut,
                        enabled = uiState.canZoomOut
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ZoomOut,
                            contentDescription = stringResource(R.string.pdf_reader_zoom_out)
                        )
                    }

                    TextButton(onClick = onResetZoom) {
                        Text(
                            text = stringResource(
                                R.string.pdf_reader_zoom_percent,
                                (uiState.zoomScale * 100f).roundToInt()
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }

                    IconButton(
                        onClick = onZoomIn,
                        enabled = uiState.canZoomIn
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ZoomIn,
                            contentDescription = stringResource(R.string.pdf_reader_zoom_in)
                        )
                    }
                }
            }
        }
    }
}

const val PDF_VIEWPORT_TEST_TAG = "pdf_pages_viewport"

private val PdfViewportOffsetSaver = listSaver<PdfViewportOffset, Float>(
    save = { listOf(it.x, it.y) },
    restore = { values ->
        PdfViewportOffset(
            x = values.getOrElse(0) { 0f },
            y = values.getOrElse(1) { 0f }
        )
    }
)

@Composable
private fun PdfPagesViewport(
    uiState: PdfReaderUiState,
    session: PdfDocumentSession,
    onVisiblePageChanged: (Int) -> Unit,
    onSetZoomScale: (Float) -> Unit,
    onToggleControls: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (uiState.currentPage - 1)
            .coerceIn(0, (uiState.totalPages - 1).coerceAtLeast(0))
    )
    var panOffset by rememberSaveable(
        uiState.fileId,
        uiState.activeVersionId,
        stateSaver = PdfViewportOffsetSaver
    ) {
        mutableStateOf(PdfViewportOffset())
    }
    var lastHandledScrollSessionId by rememberSaveable(
        uiState.fileId,
        uiState.activeVersionId
    ) {
        mutableStateOf(uiState.scrollRequestSessionId)
    }
    var lastHandledScrollRequestToken by rememberSaveable(
        uiState.fileId,
        uiState.activeVersionId
    ) {
        mutableIntStateOf(0)
    }

    LaunchedEffect(listState, session) {
        snapshotFlow { listState.firstVisibleItemIndex + 1 }
            .distinctUntilChanged()
            .map { page -> page.coerceIn(1, session.pageCount.coerceAtLeast(1)) }
            .collect { visiblePage ->
                onVisiblePageChanged(visiblePage)
            }
    }

    val pageNightColorFilter = remember(uiState.isNightModeEnabled) {
        if (!uiState.isNightModeEnabled) {
            null
        } else {
            // Soft night-study inversion: bright white becomes comfortable dark charcoal (#151515),
            // dark ink becomes soft off-white (#E4E4E4) without clipping anti-aliased glyphs.
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        -0.82f, 0f, 0f, 0f, 228f,
                        0f, -0.82f, 0f, 0f, 228f,
                        0f, 0f, -0.82f, 0f, 228f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }

    val bitmapRenderer = session as? PdfPageBitmapRenderer
    val density = LocalDensity.current
    val currentZoomScale by rememberUpdatedState(uiState.zoomScale)
    val isZoomed = PdfViewportTransform.isZoomed(uiState.zoomScale)

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.resumedFromSavedPage != null && uiState.areControlsVisible) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        R.string.pdf_reader_resumed_page_note,
                        uiState.resumedFromSavedPage
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = 6.dp)
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val viewportWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val currentViewportWidthPx by rememberUpdatedState(viewportWidthPx)
            val currentViewportHeightPx by rememberUpdatedState(viewportHeightPx)

            LaunchedEffect(uiState.zoomScale, viewportWidthPx, viewportHeightPx) {
                panOffset = PdfViewportTransform.clampOffset(
                    offset = panOffset,
                    zoomScale = uiState.zoomScale,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx
                )
            }

            LaunchedEffect(uiState.scrollRequestSessionId, uiState.scrollRequestToken) {
                val effectiveLastHandledToken = if (
                    lastHandledScrollSessionId == uiState.scrollRequestSessionId
                ) {
                    lastHandledScrollRequestToken
                } else {
                    0
                }
                if (uiState.totalPages > 0 && uiState.scrollRequestToken > effectiveLastHandledToken) {
                    val targetIndex = (uiState.scrollTargetPage - 1).coerceIn(0, uiState.totalPages - 1)
                    if (listState.firstVisibleItemIndex != targetIndex ||
                        listState.firstVisibleItemScrollOffset != 0
                    ) {
                        listState.scrollToItem(targetIndex)
                    }
                    panOffset = PdfViewportTransform.alignmentOffsetAfterPageJump(
                        zoomScale = currentZoomScale,
                        viewportWidthPx = currentViewportWidthPx,
                        viewportHeightPx = currentViewportHeightPx
                    )
                    lastHandledScrollSessionId = uiState.scrollRequestSessionId
                    lastHandledScrollRequestToken = uiState.scrollRequestToken
                }
            }

            val horizontalPaddingPx = with(density) { (spacing.md * 2).toPx() }
            val baseWidthPx = (constraints.maxWidth - horizontalPaddingPx).coerceAtLeast(360f)
            val targetBitmapWidthPx = remember(baseWidthPx, uiState.zoomScale) {
                val rawWidth = (baseWidthPx * uiState.zoomScale.coerceAtMost(2.0f)).roundToInt()
                PdfRenderSizing.quantizeWidthPx(rawWidth)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PDF_VIEWPORT_TEST_TAG)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onToggleControls() })
                    }
                    .pointerInput(session.fileId) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var gestureZoomScale = currentZoomScale
                            var panAccumulatedX = 0f
                            var panAccumulatedY = 0f
                            var zoomAccumulated = 1f
                            var pastTouchSlop = false
                            val touchSlop = viewConfiguration.touchSlop

                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressedCount = event.changes.count { it.pressed }
                                val currentlyZoomed = PdfViewportTransform.isZoomed(gestureZoomScale)

                                if (!PdfViewportTransform.shouldInterceptTouchForTransform(
                                        isCurrentlyZoomed = currentlyZoomed,
                                        pressedPointerCount = pressedCount
                                    )
                                ) {
                                    // At 1x zoom with a single finger, leave events untouched so
                                    // LazyColumn receives native scroll and fling physics, while
                                    // staying synchronized with toolbar zoom changes.
                                    gestureZoomScale = currentZoomScale
                                    pastTouchSlop = false
                                    zoomAccumulated = 1f
                                    panAccumulatedX = 0f
                                    panAccumulatedY = 0f
                                    continue
                                }

                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()

                                if (!pastTouchSlop) {
                                    zoomAccumulated *= zoomChange
                                    panAccumulatedX += panChange.x
                                    panAccumulatedY += panChange.y
                                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                    val zoomMotion = abs(1f - zoomAccumulated) * centroidSize
                                    val panMotion = hypot(panAccumulatedX, panAccumulatedY)
                                    if (pressedCount >= 2 || zoomMotion > touchSlop || panMotion > touchSlop) {
                                        pastTouchSlop = true
                                    }
                                }

                                if (pastTouchSlop) {
                                    val nextZoom = (gestureZoomScale * zoomChange).coerceIn(
                                        PdfReaderUiState.MIN_ZOOM_SCALE,
                                        PdfReaderUiState.MAX_ZOOM_SCALE
                                    )
                                    if (abs(nextZoom - gestureZoomScale) > 0.001f) {
                                        gestureZoomScale = nextZoom
                                        onSetZoomScale(nextZoom)
                                    }

                                    val step = PdfViewportTransform.applyPanAndComputeScrollOverflow(
                                        currentOffset = panOffset,
                                        panDeltaX = panChange.x,
                                        panDeltaY = panChange.y,
                                        zoomScale = nextZoom,
                                        viewportWidthPx = currentViewportWidthPx,
                                        viewportHeightPx = currentViewportHeightPx,
                                        pressedPointerCount = pressedCount
                                    )
                                    panOffset = step.offset
                                    if (pressedCount == 1 && abs(step.listScrollDeltaPx) > 0.01f) {
                                        listState.dispatchRawDelta(step.listScrollDeltaPx)
                                    }

                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) {
                                            change.consume()
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = !isZoomed,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = uiState.zoomScale
                            scaleY = uiState.zoomScale
                            translationX = panOffset.x
                            translationY = panOffset.y
                        },
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md)
                ) {
                    items(
                        count = session.pageCount,
                        key = { index -> "${session.fileId}_$index" }
                    ) { pageIndex ->
                        PdfPageItem(
                            pageIndexZeroBased = pageIndex,
                            session = session,
                            bitmapRenderer = bitmapRenderer,
                            targetWidthPx = targetBitmapWidthPx,
                            colorFilter = pageNightColorFilter,
                            isNightModeEnabled = uiState.isNightModeEnabled
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    pageIndexZeroBased: Int,
    session: PdfDocumentSession,
    bitmapRenderer: PdfPageBitmapRenderer?,
    targetWidthPx: Int,
    colorFilter: ColorFilter?,
    isNightModeEnabled: Boolean
) {
    val pageNumberOneBased = pageIndexZeroBased + 1

    val aspectRatio by produceState(
        initialValue = 0.707f,
        key1 = session.fileId,
        key2 = pageIndexZeroBased
    ) {
        value = session.getPageAspectRatio(pageIndexZeroBased)
    }

    var lastValidBitmap by remember(session.fileId, pageIndexZeroBased) {
        mutableStateOf<Bitmap?>(null)
    }

    val renderedBitmap by produceState<Bitmap?>(
        initialValue = lastValidBitmap,
        key1 = session.fileId,
        key2 = pageIndexZeroBased,
        key3 = targetWidthPx
    ) {
        val rendered = bitmapRenderer?.renderPage(
            pageIndexZeroBased = pageIndexZeroBased,
            targetWidthPx = targetWidthPx
        )
        if (rendered != null && !rendered.isRecycled) {
            lastValidBitmap = rendered
            value = rendered
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isNightModeEnabled) Color(0xFF1A1D22) else Color.White,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
    ) {
        val currentBitmap = renderedBitmap?.takeIf { !it.isRecycled }
            ?: lastValidBitmap?.takeIf { !it.isRecycled }
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = stringResource(
                    R.string.pdf_reader_page_indicator,
                    pageNumberOneBased,
                    session.pageCount
                ),
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    Text(
                        text = stringResource(R.string.pdf_reader_page_loading, pageNumberOneBased),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isNightModeEnabled) {
                            Color(0xFFC4C8D0)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfLoadingView(isNightMode: Boolean) {
    val spacing = HaqibatiThemeTokens.spacing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        HaqibatiSurfaceCard(contentPadding = spacing.xl) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = stringResource(R.string.pdf_reader_loading),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isNightMode) Color(0xFFE5E7EB) else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.pdf_reader_local_progress_badge),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PdfErrorView(
    reason: PdfFailureReason,
    isNightMode: Boolean,
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val spacing = HaqibatiThemeTokens.spacing
    val icon = when (reason) {
        PdfFailureReason.NETWORK_UNAVAILABLE -> Icons.Rounded.CloudOff
        PdfFailureReason.UNAUTHORIZED_ACCESS,
        PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED -> Icons.Rounded.Lock
        PdfFailureReason.NO_PUBLISHED_VERSION -> Icons.Rounded.PictureAsPdf
        else -> Icons.Rounded.ErrorOutline
    }
    val message = when (reason) {
        PdfFailureReason.NO_PUBLISHED_VERSION -> stringResource(R.string.pdf_reader_error_no_version)
        PdfFailureReason.SECURE_SOURCE_NOT_CONFIGURED -> stringResource(R.string.pdf_reader_error_broker_not_configured)
        PdfFailureReason.UNAUTHORIZED_ACCESS -> stringResource(R.string.pdf_reader_error_unauthorized)
        PdfFailureReason.NETWORK_UNAVAILABLE -> stringResource(R.string.pdf_reader_error_network)
        PdfFailureReason.FILE_NOT_FOUND -> stringResource(R.string.pdf_reader_error_not_found)
        PdfFailureReason.CORRUPTED_OR_UNSUPPORTED_FILE -> stringResource(R.string.pdf_reader_error_corrupted)
        PdfFailureReason.UNKNOWN -> stringResource(R.string.pdf_reader_error_unknown)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        HaqibatiSurfaceCard(contentPadding = spacing.xl) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.pdf_reader_error_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isNightMode) Color(0xFFF3F4F6) else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.action_back))
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageJumpDialog(
    currentPage: Int,
    totalPages: Int,
    onDismiss: () -> Unit,
    onConfirmJump: (Int) -> Unit
) {
    var input by remember(currentPage) { mutableStateOf(currentPage.toString()) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.pdf_reader_page_jump_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { next ->
                        input = next.filter { it.isDigit() }
                        showError = false
                    },
                    label = {
                        Text(
                            text = stringResource(
                                R.string.pdf_reader_page_jump_hint,
                                totalPages
                            )
                        )
                    },
                    singleLine = true,
                    isError = showError,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val parsed = input.toIntOrNull()
                            if (parsed != null && parsed in 1..totalPages) {
                                onConfirmJump(parsed)
                            } else {
                                showError = true
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (showError) {
                    Text(
                        text = stringResource(R.string.pdf_reader_page_jump_invalid, totalPages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = input.toIntOrNull()
                    if (parsed != null && parsed in 1..totalPages) {
                        onConfirmJump(parsed)
                    } else {
                        showError = true
                    }
                }
            ) {
                Text(text = stringResource(R.string.pdf_reader_page_jump_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.admin_action_cancel))
            }
        }
    )
}
