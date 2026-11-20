package com.studentlearning.platform2026.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderSizingTest {

    @Test
    fun `quantizeWidthPx clamps bounds and snaps to 256px buckets`() {
        assertEquals(512, PdfRenderSizing.quantizeWidthPx(120))
        assertEquals(512, PdfRenderSizing.quantizeWidthPx(480))
        assertEquals(1024, PdfRenderSizing.quantizeWidthPx(1000))
        assertEquals(1280, PdfRenderSizing.quantizeWidthPx(1080))
        assertEquals(2048, PdfRenderSizing.quantizeWidthPx(4096))
    }

    @Test
    fun `computeSafeDimensions caps height and total bitmap pixel budget for low-memory safety`() {
        val portrait = PdfRenderSizing.computeSafeDimensions(
            targetWidthPx = 2048,
            pageWidth = 595,
            pageHeight = 842
        )
        assertTrue(portrait.widthPx <= PdfRenderSizing.MAX_RENDER_WIDTH_PX)
        assertTrue(portrait.heightPx <= PdfRenderSizing.MAX_RENDER_HEIGHT_PX)
        val portraitPixels = portrait.widthPx.toLong() * portrait.heightPx.toLong()
        assertTrue(portraitPixels <= PdfRenderSizing.MAX_BITMAP_PIXELS + 4096L)
        assertTrue(portrait.estimatedBytes <= 17L * 1024L * 1024L)

        // Very tall skinny page still respects both MAX_RENDER_HEIGHT_PX and pixel budget.
        val ultraTall = PdfRenderSizing.computeSafeDimensions(
            targetWidthPx = 2048,
            pageWidth = 200,
            pageHeight = 1600
        )
        assertTrue(ultraTall.heightPx <= PdfRenderSizing.MAX_RENDER_HEIGHT_PX)
        assertTrue(ultraTall.widthPx.toLong() * ultraTall.heightPx.toLong() <= PdfRenderSizing.MAX_BITMAP_PIXELS)

        // Half-scale fallback (used after OutOfMemoryError) reduces memory roughly 4x.
        val halfScale = PdfRenderSizing.computeSafeDimensions(
            targetWidthPx = 2048,
            pageWidth = 595,
            pageHeight = 842,
            scaleFactorOverride = 0.5f
        )
        assertTrue(halfScale.estimatedBytes < portrait.estimatedBytes / 2)
    }
}
