package com.studentlearning.platform2026.presentation.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewportTransformTest {

    @Test
    fun `single finger at 1x zoom is never intercepted so LazyColumn scrolls naturally`() {
        assertFalse(
            PdfViewportTransform.shouldInterceptTouchForTransform(
                isCurrentlyZoomed = false,
                pressedPointerCount = 1
            )
        )
        assertTrue(
            PdfViewportTransform.shouldInterceptTouchForTransform(
                isCurrentlyZoomed = false,
                pressedPointerCount = 2
            )
        )
        assertTrue(
            PdfViewportTransform.shouldInterceptTouchForTransform(
                isCurrentlyZoomed = true,
                pressedPointerCount = 1
            )
        )
    }

    @Test
    fun `zoomed pan reaches full horizontal and vertical edges without clipping`() {
        val viewportW = 1000f
        val viewportH = 1600f
        val scale = 2.5f

        // Centered scale 2.5x increases half-span by (2.5 - 1) * dim / 2.
        assertEquals(750f, PdfViewportTransform.maxPanX(scale, viewportW), 0.01f)
        assertEquals(1200f, PdfViewportTransform.maxPanY(scale, viewportH), 0.01f)

        // Panning to extreme top-left brings top-left page corner cleanly inside viewport (0, 0).
        val topLeftStep = PdfViewportTransform.applyPanAndComputeScrollOverflow(
            currentOffset = PdfViewportOffset(0f, 0f),
            panDeltaX = 900f,
            panDeltaY = 1200f,
            zoomScale = scale,
            viewportWidthPx = viewportW,
            viewportHeightPx = viewportH
        )
        assertEquals(750f, topLeftStep.offset.x, 0.01f)
        assertEquals(1200f, topLeftStep.offset.y, 0.01f)
        assertEquals(0f, topLeftStep.listScrollDeltaPx, 0.01f)

        // Continuing to drag upward beyond the bottom edge forwards unscaled overflow to LazyColumn.
        val overflowDownStep = PdfViewportTransform.applyPanAndComputeScrollOverflow(
            currentOffset = PdfViewportOffset(0f, -1100f),
            panDeltaX = -2000f,
            panDeltaY = -350f,
            zoomScale = scale,
            viewportWidthPx = viewportW,
            viewportHeightPx = viewportH
        )
        assertEquals(-750f, overflowDownStep.offset.x, 0.01f)
        assertEquals(-1200f, overflowDownStep.offset.y, 0.01f)
        // Remaining -250f upward drag translates to +100f list scroll delta (-(-250) / 2.5).
        assertEquals(100f, overflowDownStep.listScrollDeltaPx, 0.01f)
    }

    @Test
    fun `zooming back out re-clamps offsets and resets to zero at 1x`() {
        val extremeAt3x = PdfViewportOffset(x = 1000f, y = -1500f)

        val clampedAt15x = PdfViewportTransform.clampOffset(
            offset = extremeAt3x,
            zoomScale = 1.5f,
            viewportWidthPx = 1000f,
            viewportHeightPx = 1500f
        )
        assertEquals(250f, clampedAt15x.x, 0.01f)
        assertEquals(-375f, clampedAt15x.y, 0.01f)

        val resetAt1x = PdfViewportTransform.clampOffset(
            offset = extremeAt3x,
            zoomScale = 1.0f,
            viewportWidthPx = 1000f,
            viewportHeightPx = 1500f
        )
        assertEquals(0f, resetAt1x.x, 0.01f)
        assertEquals(0f, resetAt1x.y, 0.01f)
    }

    @Test
    fun `two finger gesture at 1x never produces LazyColumn scroll delta`() {
        val step = PdfViewportTransform.applyPanAndComputeScrollOverflow(
            currentOffset = PdfViewportOffset(0f, 0f),
            panDeltaX = 45f,
            panDeltaY = -180f,
            zoomScale = 1.0f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 1920f,
            pressedPointerCount = 2
        )

        assertEquals(0f, step.offset.x, 0.01f)
        assertEquals(0f, step.offset.y, 0.01f)
        assertEquals(0f, step.listScrollDeltaPx, 0.01f)
    }

    @Test
    fun `programmatic page jump aligns top of target page cleanly when zoomed and resets at 1x`() {
        val zoomedJumpOffset = PdfViewportTransform.alignmentOffsetAfterPageJump(
            zoomScale = 2.0f,
            viewportWidthPx = 1000f,
            viewportHeightPx = 1600f
        )
        assertEquals(0f, zoomedJumpOffset.x, 0.01f)
        assertEquals(800f, zoomedJumpOffset.y, 0.01f)

        val unzoomedJumpOffset = PdfViewportTransform.alignmentOffsetAfterPageJump(
            zoomScale = 1.0f,
            viewportWidthPx = 1000f,
            viewportHeightPx = 1600f
        )
        assertEquals(0f, unzoomedJumpOffset.x, 0.01f)
        assertEquals(0f, unzoomedJumpOffset.y, 0.01f)
    }
}
