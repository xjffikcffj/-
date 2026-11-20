package com.studentlearning.platform2026.presentation.pdfreader

import kotlin.math.abs

data class PdfViewportOffset(
    val x: Float = 0f,
    val y: Float = 0f
)

data class PdfPanStepResult(
    val offset: PdfViewportOffset,
    val listScrollDeltaPx: Float
)

/**
 * Pure geometry helpers for PDF zoom/pan bounds so zoomed pages can be panned both horizontally
 * and vertically without clipping top/bottom/side edges, while letting 1x single-finger scrolling
 * pass straight through to [androidx.compose.foundation.lazy.LazyColumn].
 */
object PdfViewportTransform {

    private const val ZOOM_EPSILON = 0.01f

    fun isZoomed(zoomScale: Float, minZoomScale: Float = PdfReaderUiState.MIN_ZOOM_SCALE): Boolean {
        return zoomScale > minZoomScale + ZOOM_EPSILON
    }

    fun shouldInterceptTouchForTransform(
        isCurrentlyZoomed: Boolean,
        pressedPointerCount: Int
    ): Boolean {
        return pressedPointerCount >= 2 || isCurrentlyZoomed
    }

    fun maxPanX(zoomScale: Float, viewportWidthPx: Float): Float {
        if (!isZoomed(zoomScale) || viewportWidthPx <= 0f) return 0f
        return ((zoomScale - 1f) * viewportWidthPx) / 2f
    }

    fun maxPanY(zoomScale: Float, viewportHeightPx: Float): Float {
        if (!isZoomed(zoomScale) || viewportHeightPx <= 0f) return 0f
        return ((zoomScale - 1f) * viewportHeightPx) / 2f
    }

    fun clampOffset(
        offset: PdfViewportOffset,
        zoomScale: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float
    ): PdfViewportOffset {
        if (!isZoomed(zoomScale)) return PdfViewportOffset(0f, 0f)
        val limitX = maxPanX(zoomScale, viewportWidthPx)
        val limitY = maxPanY(zoomScale, viewportHeightPx)
        return PdfViewportOffset(
            x = offset.x.coerceIn(-limitX, limitX),
            y = offset.y.coerceIn(-limitY, limitY)
        )
    }

    fun alignmentOffsetAfterPageJump(
        zoomScale: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float
    ): PdfViewportOffset {
        if (!isZoomed(zoomScale) || viewportWidthPx <= 0f || viewportHeightPx <= 0f) {
            return PdfViewportOffset(0f, 0f)
        }
        return PdfViewportOffset(
            x = 0f,
            y = maxPanY(zoomScale, viewportHeightPx)
        )
    }

    /**
     * Applies [panDeltaX]/[panDeltaY] on top of [currentOffset] inside scaled bounds and converts
     * excess vertical drag beyond the top/bottom viewport edge into unscaled [LazyColumn]
     * scroll delta only for single-finger zoomed panning (never for 2-finger pinch at 1x).
     */
    fun applyPanAndComputeScrollOverflow(
        currentOffset: PdfViewportOffset,
        panDeltaX: Float,
        panDeltaY: Float,
        zoomScale: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        pressedPointerCount: Int = 1
    ): PdfPanStepResult {
        if (!isZoomed(zoomScale)) {
            return PdfPanStepResult(
                offset = PdfViewportOffset(0f, 0f),
                listScrollDeltaPx = 0f
            )
        }

        val limitX = maxPanX(zoomScale, viewportWidthPx)
        val limitY = maxPanY(zoomScale, viewportHeightPx)

        val rawNextX = currentOffset.x + panDeltaX
        val clampedX = rawNextX.coerceIn(-limitX, limitX)

        val rawNextY = currentOffset.y + panDeltaY
        val clampedY = rawNextY.coerceIn(-limitY, limitY)
        val overflowY = rawNextY - clampedY

        // Dragging finger upward (panDeltaY < 0 -> overflowY < 0) scrolls the list forward (+).
        // Never emit list scroll delta during multi-touch gestures (pinch/zoom).
        val listScrollDelta = if (pressedPointerCount == 1 && abs(overflowY) > 0.01f) {
            -overflowY / zoomScale.coerceAtLeast(1f)
        } else {
            0f
        }

        return PdfPanStepResult(
            offset = PdfViewportOffset(x = clampedX, y = clampedY),
            listScrollDeltaPx = listScrollDelta
        )
    }
}
