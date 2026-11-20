package com.studentlearning.platform2026.domain.pdf

import com.studentlearning.platform2026.domain.model.PdfSearchMatch
import com.studentlearning.platform2026.domain.model.PdfSearchResult
import com.studentlearning.platform2026.domain.model.PdfSearchStatus

/**
 * Pure Kotlin Arabic-aware normalization & text match helper for extractable PDF text.
 *
 * Does NOT perform OCR on scanned/image-only pages: if extracted text across all pages is empty,
 * search explicitly reports [PdfSearchStatus.NO_EXTRACTABLE_TEXT].
 */
object ArabicTextNormalizer {

    private val DIACRITICS_REGEX = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun normalizeForSearch(input: String): String {
        if (input.isBlank()) return ""
        val withoutDiacritics = DIACRITICS_REGEX.replace(input, "")
        val normalizedChars = StringBuilder(withoutDiacritics.length)
        for (ch in withoutDiacritics) {
            normalizedChars.append(
                when (ch) {
                    '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627' // أ إ آ ٱ -> ا
                    '\u0649' -> '\u064A' // ى -> ي
                    '\u0629' -> '\u0647' // ة -> ه
                    else -> ch.lowercaseChar()
                }
            )
        }
        return WHITESPACE_REGEX.replace(normalizedChars.toString().trim(), " ")
    }

    fun searchAcrossPages(
        query: String,
        pageCount: Int,
        extractPageText: (pageNumberOneBased: Int) -> String,
        onCheckCancelled: () -> Unit = {}
    ): PdfSearchResult {
        val trimmedQuery = query.trim()
        val normalizedQuery = normalizeForSearch(trimmedQuery)
        if (normalizedQuery.isEmpty() || pageCount <= 0) {
            return PdfSearchResult(
                query = trimmedQuery,
                status = PdfSearchStatus.IDLE,
                matches = emptyList()
            )
        }

        val matches = mutableListOf<PdfSearchMatch>()
        var hasAnyExtractableText = false
        var globalIndex = 0

        for (page in 1..pageCount) {
            onCheckCancelled()
            val rawPageText = extractPageText(page)
            val normalizedPageText = normalizeForSearch(rawPageText)
            if (normalizedPageText.isNotEmpty()) {
                hasAnyExtractableText = true
            } else {
                continue
            }

            var searchFrom = 0
            while (searchFrom < normalizedPageText.length) {
                val foundAt = normalizedPageText.indexOf(normalizedQuery, startIndex = searchFrom)
                if (foundAt < 0) break

                val snippetStart = (foundAt - 24).coerceAtLeast(0)
                val snippetEnd = (foundAt + normalizedQuery.length + 36)
                    .coerceAtMost(normalizedPageText.length)
                val prefix = if (snippetStart > 0) "…" else ""
                val suffix = if (snippetEnd < normalizedPageText.length) "…" else ""
                val snippet = prefix + normalizedPageText.substring(snippetStart, snippetEnd).trim() + suffix

                matches.add(
                    PdfSearchMatch(
                        pageNumber = page,
                        matchIndexInDocument = globalIndex++,
                        snippet = snippet
                    )
                )
                searchFrom = foundAt + normalizedQuery.length
            }
        }

        val status = when {
            matches.isNotEmpty() -> PdfSearchStatus.FOUND
            !hasAnyExtractableText -> PdfSearchStatus.NO_EXTRACTABLE_TEXT
            else -> PdfSearchStatus.NO_RESULTS
        }

        return PdfSearchResult(
            query = trimmedQuery,
            status = status,
            matches = matches
        )
    }
}
