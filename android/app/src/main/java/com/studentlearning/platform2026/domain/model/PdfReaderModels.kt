package com.studentlearning.platform2026.domain.model

enum class PdfFailureReason {
    NO_PUBLISHED_VERSION,
    SECURE_SOURCE_NOT_CONFIGURED,
    UNAUTHORIZED_ACCESS,
    NETWORK_UNAVAILABLE,
    FILE_NOT_FOUND,
    CORRUPTED_OR_UNSUPPORTED_FILE,
    UNKNOWN
}

class PdfLoadException(
    val reason: PdfFailureReason,
    override val message: String? = null,
    override val cause: Throwable? = null
) : Exception(message, cause)

enum class PdfSearchStatus {
    IDLE,
    SEARCHING,
    FOUND,
    NO_RESULTS,
    NO_EXTRACTABLE_TEXT,
    ERROR
}

data class PdfSearchMatch(
    val pageNumber: Int, // 1-based page number
    val matchIndexInDocument: Int,
    val snippet: String
)

data class PdfSearchResult(
    val query: String,
    val status: PdfSearchStatus,
    val matches: List<PdfSearchMatch> = emptyList()
)

sealed interface PdfSourceDescriptor {
    /**
     * Authoritative short-lived HTTPS stream/URL issued by a future secure Cloudflare R2 broker.
     */
    data class AuthorizedRemoteUrl(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val cacheKey: String
    ) : PdfSourceDescriptor

    /**
     * Explicit local file path (e.g., future verified offline study files or local stream cache).
     */
    data class LocalFilePath(
        val absolutePath: String,
        val deleteOnSessionClose: Boolean = false
    ) : PdfSourceDescriptor
}
