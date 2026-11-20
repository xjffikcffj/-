package com.studentlearning.platform2026.domain.pdf

import com.studentlearning.platform2026.domain.model.PdfSearchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicTextNormalizerTest {

    @Test
    fun `normalizes hamza alef maqsura teh marbuta and removes diacritics`() {
        val normalized = ArabicTextNormalizer.normalizeForSearch(
            "  الأَعْدَادُ   المُرَكَّبَةُ وَمَسَائِلُ الكِيميَاءِ الكُبرَى  "
        )
        assertEquals("الاعداد المركبه ومسائل الكيمياء الكبري", normalized)
    }

    @Test
    fun `searches across pages in document order with Arabic normalization`() {
        val pages = mapOf(
            1 to "مقدمة في الفيزياء الحديثة",
            2 to "القوانين الأساسية للحركة والطاقة الحركية",
            3 to "تطبيقات الطاقة الكهربائية والطاقة الحرارية"
        )

        val result = ArabicTextNormalizer.searchAcrossPages(
            query = "الطّاقة",
            pageCount = 3,
            extractPageText = { page -> pages[page].orEmpty() }
        )

        assertEquals(PdfSearchStatus.FOUND, result.status)
        assertEquals(3, result.matches.size)
        assertEquals(listOf(2, 3, 3), result.matches.map { it.pageNumber })
        assertEquals(listOf(0, 1, 2), result.matches.map { it.matchIndexInDocument })
    }

    @Test
    fun `reports NO_EXTRACTABLE_TEXT for scanned image-only PDFs without fake OCR`() {
        val result = ArabicTextNormalizer.searchAcrossPages(
            query = "المعادلة",
            pageCount = 4,
            extractPageText = { "" }
        )

        assertEquals(PdfSearchStatus.NO_EXTRACTABLE_TEXT, result.status)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `reports NO_RESULTS when text exists but query does not match`() {
        val result = ArabicTextNormalizer.searchAcrossPages(
            query = "الكيمياء العضوية",
            pageCount = 2,
            extractPageText = { "الفصل الأول: الدوال والتفاضل" }
        )

        assertEquals(PdfSearchStatus.NO_RESULTS, result.status)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `blank query returns IDLE without scanning`() {
        var scanned = false
        val result = ArabicTextNormalizer.searchAcrossPages(
            query = "   ",
            pageCount = 5,
            extractPageText = {
                scanned = true
                "نص"
            }
        )

        assertEquals(PdfSearchStatus.IDLE, result.status)
        assertEquals(false, scanned)
    }
}
