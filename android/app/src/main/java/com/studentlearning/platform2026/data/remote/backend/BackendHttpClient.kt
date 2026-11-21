package com.studentlearning.platform2026.data.remote.backend

import com.studentlearning.platform2026.domain.repository.PdfUploadBinarySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal class BackendHttpException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String,
    val isR2StorageError: Boolean = false,
    val isR2UrlExpired: Boolean = false
) : Exception(message)

internal class BackendNotConfiguredException(
    override val message: String
) : Exception(message)

internal class BackendHttpClient(
    rawBaseUrl: String?,
    private val allowInsecureHttpForTesting: Boolean = false,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) {

    val apiRootUrl: String? = BackendJson.normalizeApiRootUrl(
        rawBaseUrl = rawBaseUrl,
        allowInsecureHttpForTesting = allowInsecureHttpForTesting
    )

    val isConfigured: Boolean
        get() = apiRootUrl != null

    suspend fun getJsonObject(
        relativeApiPath: String,
        bearerToken: String? = null
    ): Map<String, Any?> {
        return requestJsonObject(
            method = "GET",
            relativeApiPath = relativeApiPath,
            bodyMap = null,
            bearerToken = bearerToken
        )
    }

    suspend fun postJsonObject(
        relativeApiPath: String,
        body: Map<String, Any?> = emptyMap(),
        bearerToken: String
    ): Map<String, Any?> {
        return requestJsonObject(
            method = "POST",
            relativeApiPath = relativeApiPath,
            bodyMap = body,
            bearerToken = bearerToken
        )
    }

    suspend fun patchJsonObject(
        relativeApiPath: String,
        body: Map<String, Any?>,
        bearerToken: String
    ): Map<String, Any?> {
        return requestJsonObject(
            method = "PATCH",
            relativeApiPath = relativeApiPath,
            bodyMap = body,
            bearerToken = bearerToken
        )
    }

    suspend fun uploadPdfStreamPut(
        uploadUrl: String,
        requiredHeaders: Map<String, String>,
        binarySource: PdfUploadBinarySource
    ) {
        validateTargetTransportUrl(uploadUrl)
        if (binarySource.sizeBytes <= 0L) {
            throw IllegalArgumentException("PDF binary size must be positive.")
        }

        var connection: HttpURLConnection? = null
        var cancellationHook: DisposableHandle? = null

        try {
            currentCoroutineContext().ensureActive()
            val url = URL(uploadUrl)
            val activeConnection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = UPLOAD_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setFixedLengthStreamingMode(binarySource.sizeBytes)

                var hasContentType = false
                for ((key, value) in requiredHeaders) {
                    if (key.equals("Content-Length", ignoreCase = true)) continue
                    if (key.equals("Content-Type", ignoreCase = true)) hasContentType = true
                    setRequestProperty(key, value)
                }
                if (!hasContentType) {
                    setRequestProperty("Content-Type", "application/pdf")
                }
            }
            connection = activeConnection
            cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    runCatching { activeConnection.disconnect() }
                }
            }

            binarySource.openInputStream().use { input ->
                activeConnection.outputStream.use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var totalSent = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalSent += read
                    }
                    output.flush()
                    if (totalSent != binarySource.sizeBytes) {
                        throw IOException(
                            "Streamed byte count ($totalSent) did not match expected size (${binarySource.sizeBytes})."
                        )
                    }
                }
            }

            val statusCode = activeConnection.responseCode
            if (statusCode !in 200..299) {
                val rawError = readResponseBody(activeConnection, isError = true)
                throw parseR2UploadError(statusCode, rawError)
            }
        } finally {
            cancellationHook?.dispose()
            connection?.disconnect()
        }
    }

    private suspend fun requestJsonObject(
        method: String,
        relativeApiPath: String,
        bodyMap: Map<String, Any?>?,
        bearerToken: String?
    ): Map<String, Any?> {
        val root = apiRootUrl
            ?: throw BackendNotConfiguredException("Backend API base URL is not configured or not HTTPS.")
        val cleanPath = relativeApiPath.trimStart('/')
        val fullUrl = "$root/$cleanPath"
        validateTargetTransportUrl(fullUrl)

        var connection: HttpURLConnection? = null
        var cancellationHook: DisposableHandle? = null

        try {
            currentCoroutineContext().ensureActive()
            val url = URL(fullUrl)
            val activeConnection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
                }
                if (bodyMap != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            connection = activeConnection
            cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    runCatching { activeConnection.disconnect() }
                }
            }

            if (bodyMap != null) {
                val payloadBytes = BackendJson.writeObject(bodyMap).toByteArray(Charsets.UTF_8)
                activeConnection.setFixedLengthStreamingMode(payloadBytes.size)
                activeConnection.outputStream.use { output ->
                    output.write(payloadBytes)
                    output.flush()
                }
            }

            val statusCode = activeConnection.responseCode
            val isSuccess = statusCode in 200..299
            val rawBody = readResponseBody(activeConnection, isError = !isSuccess)
            if (!isSuccess) {
                throw parseStructuredError(statusCode, rawBody)
            }
            if (rawBody.isBlank()) return emptyMap()
            return BackendJson.parseObject(rawBody)
        } finally {
            cancellationHook?.dispose()
            connection?.disconnect()
        }
    }

    private fun validateTargetTransportUrl(rawUrl: String) {
        val uri = try {
            URI(rawUrl)
        } catch (_: Throwable) {
            throw BackendNotConfiguredException("Invalid target URL format.")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val isHttps = scheme == "https"
        val isLoopbackHttp = allowInsecureHttpForTesting &&
            scheme == "http" &&
            (host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2")
        if (!isHttps && !isLoopbackHttp) {
            throw BackendNotConfiguredException("Only HTTPS backend endpoints are permitted.")
        }
    }

    private fun readResponseBody(connection: HttpURLConnection, isError: Boolean): String {
        val stream = if (isError) {
            connection.errorStream ?: connection.inputStream
        } else {
            connection.inputStream
        } ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parseStructuredError(statusCode: Int, rawBody: String): BackendHttpException {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = BackendJson.parseObject(trimmed)
                val errObj = json.objectOrNull("error")
                val code = errObj?.stringOrNull("code")
                val msg = errObj?.stringOrNull("message") ?: "HTTP $statusCode"
                return BackendHttpException(statusCode = statusCode, errorCode = code, message = msg)
            } catch (_: Throwable) {
                // Fall back to status-based error if JSON body is malformed.
            }
        }
        return BackendHttpException(
            statusCode = statusCode,
            errorCode = null,
            message = "Backend request failed with HTTP $statusCode."
        )
    }

    private fun parseR2UploadError(statusCode: Int, rawBody: String): BackendHttpException {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("<")) {
            val safeCode = R2_XML_CODE_REGEX.find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val xmlMessage = R2_XML_MESSAGE_REGEX.find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
            val isExpired = statusCode == 410 ||
                safeCode.equals("ExpiredToken", ignoreCase = true) ||
                safeCode.equals("TokenRefreshRequired", ignoreCase = true) ||
                xmlMessage.contains("expired", ignoreCase = true)
            val codeSuffix = safeCode?.let { " ($it)" }.orEmpty()
            return BackendHttpException(
                statusCode = statusCode,
                errorCode = safeCode,
                message = "Cloudflare R2 upload rejected with HTTP $statusCode$codeSuffix.",
                isR2StorageError = true,
                isR2UrlExpired = isExpired
            )
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = BackendJson.parseObject(trimmed)
                val errObj = json.objectOrNull("error")
                val code = errObj?.stringOrNull("code")
                val isExpired = statusCode == 410 ||
                    code.equals("SESSION_EXPIRED", ignoreCase = true) ||
                    code.equals("ExpiredToken", ignoreCase = true)
                val codeSuffix = code?.let { " ($it)" }.orEmpty()
                return BackendHttpException(
                    statusCode = statusCode,
                    errorCode = code,
                    message = "Cloudflare R2 upload rejected with HTTP $statusCode$codeSuffix.",
                    isR2StorageError = true,
                    isR2UrlExpired = isExpired
                )
            } catch (_: Throwable) {
                // Fall back to status-based R2 error without leaking raw payload.
            }
        }
        return BackendHttpException(
            statusCode = statusCode,
            errorCode = null,
            message = "Cloudflare R2 upload failed with HTTP $statusCode.",
            isR2StorageError = true,
            isR2UrlExpired = statusCode == 410
        )
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val UPLOAD_READ_TIMEOUT_MS = 120_000
        const val STREAM_BUFFER_BYTES = 16 * 1024
        val R2_XML_CODE_REGEX = Regex("<Code>\\s*([A-Za-z0-9_.-]{1,64})\\s*</Code>", RegexOption.IGNORE_CASE)
        val R2_XML_MESSAGE_REGEX = Regex("<Message>\\s*([^<]{1,200})\\s*</Message>", RegexOption.IGNORE_CASE)
    }
}
