package com.studentlearning.platform2026.data.download

import android.os.Looper
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

data class DownloadStreamResult(
    val bytesWritten: Long,
    val contentLengthHeaderBytes: Long?,
    val serverSha256Header: String?
)

interface DownloadStreamFetcher {
    suspend fun downloadToFile(
        source: PdfSourceDescriptor.AuthorizedRemoteUrl,
        destinationFile: File,
        onContentLengthKnown: suspend (contentLengthBytes: Long) -> Unit = {},
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): DownloadStreamResult
}

internal data class HttpDownloadFetcherHooks(
    val connectionOpener: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
    val fileOutputStreamOpener: (File) -> FileOutputStream = { file -> FileOutputStream(file) },
    val isMainThreadChecker: () -> Boolean = DEFAULT_MAIN_THREAD_CHECKER,
    val cancelTeardownExecutor: Executor = DEFAULT_CANCEL_TEARDOWN_EXECUTOR,
    val onBeforeResponseCode: (() -> Unit)? = null,
    val onAfterSocketCreatedBeforeTracking: ((Socket) -> Unit)? = null,
    val onBeforeFinallyCleanup: (() -> Unit)? = null
) {
    private companion object {
        const val CANCEL_CORE_POOL_SIZE = 2
        const val CANCEL_MAX_POOL_SIZE = 6
        const val CANCEL_KEEP_ALIVE_SECONDS = 15L
        const val CANCEL_QUEUE_CAPACITY = 16
        val CANCEL_TEARDOWN_THREAD_COUNTER = AtomicInteger(1)

        val DEFAULT_MAIN_THREAD_CHECKER: () -> Boolean = {
            runCatching { Looper.getMainLooper()?.thread === Thread.currentThread() }.getOrDefault(false)
        }

        val DEFAULT_CANCEL_TEARDOWN_EXECUTOR: Executor = ThreadPoolExecutor(
            CANCEL_CORE_POOL_SIZE,
            CANCEL_MAX_POOL_SIZE,
            CANCEL_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(CANCEL_QUEUE_CAPACITY),
            { runnable ->
                Thread(
                    runnable,
                    "haqibati-download-cancel-${CANCEL_TEARDOWN_THREAD_COUNTER.getAndIncrement()}"
                ).apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy()
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }
}

/**
 * Streams an authorized HTTPS PDF source into [destinationFile] with active cancellation,
 * progress reporting, HTTP status classification, and write/disk-space error separation.
 *
 * Security & Redirect Policy:
 * - `instanceFollowRedirects = false` is intentionally enforced so downloads never silently follow
 *   3xx redirects to unvalidated hosts or downgrade HTTPS to HTTP.
 * - The future Cloudflare R2 broker endpoint must stream PDF bytes directly over HTTPS with HTTP 200
 *   (or supply a direct HTTPS URL to the fetcher via broker API JSON rather than HTTP 301/302/307).
 */
class HttpDownloadStreamFetcher private constructor(
    private val allowInsecureHttpForTesting: Boolean,
    private val hooks: HttpDownloadFetcherHooks,
    @Suppress("UNUSED_PARAMETER") privateTag: Unit
) : DownloadStreamFetcher {

    constructor(allowInsecureHttpForTesting: Boolean = false) : this(
        allowInsecureHttpForTesting = allowInsecureHttpForTesting,
        hooks = HttpDownloadFetcherHooks(),
        privateTag = Unit
    )

    internal constructor(
        allowInsecureHttpForTesting: Boolean = false,
        hooks: HttpDownloadFetcherHooks
    ) : this(
        allowInsecureHttpForTesting = allowInsecureHttpForTesting,
        hooks = hooks,
        privateTag = Unit
    )

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun downloadToFile(
        source: PdfSourceDescriptor.AuthorizedRemoteUrl,
        destinationFile: File,
        onContentLengthKnown: suspend (contentLengthBytes: Long) -> Unit,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit
    ): DownloadStreamResult {
        val isHttps = source.url.startsWith("https://", ignoreCase = true)
        val isAllowedHttp = allowInsecureHttpForTesting &&
            source.url.startsWith("http://", ignoreCase = true)
        if (!isHttps && !isAllowedHttp) {
            throw DownloadException(
                reason = DownloadFailureReason.SECURE_BROKER_NOT_CONFIGURED,
                message = "Only HTTPS sources are permitted for offline PDF downloads."
            )
        }

        var finalizeConnection: (() -> Unit)? = null
        var cancellationHook: DisposableHandle? = null

        try {
            currentCoroutineContext().ensureActive()
            val url = URL(source.url)
            val activeConnection = hooks.connectionOpener(url).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                source.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }

            // OpenJDK's HttpURLConnection wraps streams in MeteredStream whose close() acquires the same
            // readLock held by blocked input.read(buffer) calls, so calling disconnect() concurrently on
            // plain http:// connections deadlocks until server timeout unless read() slices briefly.
            val usesJdkSunHttp = activeConnection !is HttpsURLConnection &&
                activeConnection.javaClass.name.startsWith("sun.net.www.protocol.")
            if (usesJdkSunHttp) {
                activeConnection.readTimeout = JDK_HTTP_READ_SLICE_MS
            }

            val stateLock = Any()
            var isCancelled = false
            var isFinished = false
            var isConnected = false
            var disconnectedAfterConnected = false
            var activeTeardownLatch: CountDownLatch? = null
            val pendingSockets = LinkedHashSet<Socket>()
            val isReadingBody = AtomicBoolean(false)

            val registerSocketsOrAbort: (Array<out Socket>) -> Unit = { sockets ->
                sockets.firstOrNull()?.let { primary ->
                    hooks.onAfterSocketCreatedBeforeTracking?.invoke(primary)
                }
                val shouldAbort = synchronized(stateLock) {
                    if (isCancelled || isFinished) {
                        true
                    } else {
                        for (socket in sockets) {
                            pendingSockets.add(socket)
                        }
                        false
                    }
                }
                if (shouldAbort) {
                    for (socket in sockets) {
                        runCatching { socket.close() }
                    }
                    throw CancellationException("Download cancelled during socket creation.")
                }
            }

            if (activeConnection is HttpsURLConnection) {
                val baseFactory = activeConnection.sslSocketFactory
                    ?: HttpsURLConnection.getDefaultSSLSocketFactory()
                activeConnection.sslSocketFactory = TrackingSSLSocketFactory(
                    delegate = baseFactory,
                    onSocketsCreated = registerSocketsOrAbort
                )
            }

            finalizeConnection = {
                hooks.onBeforeFinallyCleanup?.invoke()
                val remainingSockets: List<Socket>
                val shouldDisconnect: Boolean
                val teardownLatchToWait: CountDownLatch?
                synchronized(stateLock) {
                    isFinished = true
                    remainingSockets = pendingSockets.toList()
                    pendingSockets.clear()
                    shouldDisconnect = !disconnectedAfterConnected
                    disconnectedAfterConnected = true
                    teardownLatchToWait = activeTeardownLatch
                }
                for (socket in remainingSockets) {
                    runCatching { socket.close() }
                }
                if (shouldDisconnect) {
                    runCatching { activeConnection.disconnect() }
                }
                if (teardownLatchToWait != null && !hooks.isMainThreadChecker()) {
                    try {
                        teardownLatchToWait.await(TEARDOWN_AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }

            cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true
            ) { cause ->
                if (cause !is CancellationException) return@invokeOnCompletion

                val hasPendingTeardown = synchronized(stateLock) {
                    isCancelled = true
                    if (isFinished) {
                        false
                    } else {
                        val canDisconnect = !(usesJdkSunHttp && isReadingBody.get())
                        pendingSockets.isNotEmpty() || (canDisconnect && !disconnectedAfterConnected)
                    }
                }
                if (!hasPendingTeardown) return@invokeOnCompletion

                val teardownLatch = CountDownLatch(1)
                val claimAndTeardown = {
                    try {
                        val socketsToClose: List<Socket>
                        val shouldDisconnect: Boolean
                        synchronized(stateLock) {
                            if (isFinished) {
                                socketsToClose = emptyList()
                                shouldDisconnect = false
                            } else {
                                socketsToClose = pendingSockets.toList()
                                pendingSockets.clear()
                                val canDisconnect = !(usesJdkSunHttp && isReadingBody.get())
                                shouldDisconnect = canDisconnect && !disconnectedAfterConnected
                                if (shouldDisconnect && isConnected) {
                                    disconnectedAfterConnected = true
                                }
                                if (socketsToClose.isNotEmpty() || shouldDisconnect) {
                                    activeTeardownLatch = teardownLatch
                                }
                            }
                        }
                        for (socket in socketsToClose) {
                            runCatching { socket.close() }
                        }
                        if (shouldDisconnect) {
                            runCatching { activeConnection.disconnect() }
                        }
                    } finally {
                        teardownLatch.countDown()
                    }
                }

                if (hooks.isMainThreadChecker()) {
                    try {
                        hooks.cancelTeardownExecutor.execute(claimAndTeardown)
                    } catch (_: Throwable) {
                        // Ownership stays in pendingSockets / disconnectedAfterConnected for finalizeConnection on IO.
                    }
                } else {
                    claimAndTeardown()
                }
            }

            hooks.onBeforeResponseCode?.invoke()
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (isCancelled) {
                    throw CancellationException("Download cancelled before HTTP connection.")
                }
            }

            val statusCode = activeConnection.responseCode
            synchronized(stateLock) {
                isConnected = true
            }
            currentCoroutineContext().ensureActive()

            when (statusCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    throw DownloadException(
                        reason = DownloadFailureReason.UNAUTHORIZED_ACCESS,
                        message = "Access denied by remote PDF source (HTTP $statusCode)."
                    )
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    throw DownloadException(
                        reason = DownloadFailureReason.SERVER_ERROR,
                        message = "Remote PDF file was not found (HTTP 404)."
                    )
                }
                else -> {
                    throw DownloadException(
                        reason = DownloadFailureReason.SERVER_ERROR,
                        message = "Remote PDF download failed with HTTP $statusCode."
                    )
                }
            }

            val rawContentLength = activeConnection.contentLengthLong
            val knownTotalBytes = rawContentLength.takeIf { it > 0L }
            if (knownTotalBytes != null) {
                onContentLengthKnown(knownTotalBytes)
            }

            val rawHeaderChecksum = activeConnection.getHeaderField("X-Content-Sha256")
            val headerChecksum = if (rawHeaderChecksum != null) {
                val trimmed = rawHeaderChecksum.trim().lowercase()
                if (trimmed.isEmpty()) {
                    null
                } else if (!trimmed.matches(SHA256_HEX_REGEX)) {
                    throw DownloadException(
                        reason = DownloadFailureReason.INTEGRITY_CHECK_FAILED,
                        message = "Malformed X-Content-Sha256 response header."
                    )
                } else {
                    trimmed
                }
            } else {
                null
            }

            var bytesWritten = 0L
            var lastProgressBytes = 0L
            onProgress(0L, knownTotalBytes)

            try {
                isReadingBody.set(true)
                activeConnection.inputStream.use { input ->
                    try {
                        hooks.fileOutputStreamOpener(destinationFile).use { output ->
                            val buffer = ByteArray(STREAM_BUFFER_BYTES)
                            var stalledReadMillis = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = try {
                                    input.read(buffer)
                                } catch (timeout: SocketTimeoutException) {
                                    currentCoroutineContext().ensureActive()
                                    if (usesJdkSunHttp) {
                                        stalledReadMillis += JDK_HTTP_READ_SLICE_MS
                                        if (stalledReadMillis < READ_TIMEOUT_MS) {
                                            continue
                                        }
                                    }
                                    throw DownloadException(
                                        reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                                        message = timeout.localizedMessage ?: "Network interrupted during download.",
                                        cause = timeout
                                    )
                                } catch (io: IOException) {
                                    currentCoroutineContext().ensureActive()
                                    throw DownloadException(
                                        reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                                        message = io.localizedMessage ?: "Network interrupted during download.",
                                        cause = io
                                    )
                                }
                                if (read == -1) break
                                stalledReadMillis = 0L
                                try {
                                    output.write(buffer, 0, read)
                                } catch (io: IOException) {
                                    currentCoroutineContext().ensureActive()
                                    val reason = if (LocalPdfStorageManager.isOutOfSpaceError(io)) {
                                        DownloadFailureReason.INSUFFICIENT_STORAGE
                                    } else {
                                        DownloadFailureReason.STORAGE_WRITE_ERROR
                                    }
                                    throw DownloadException(
                                        reason = reason,
                                        message = io.localizedMessage ?: "Failed writing downloaded bytes.",
                                        cause = io
                                    )
                                }
                                bytesWritten += read
                                if (bytesWritten - lastProgressBytes >= PROGRESS_NOTIFY_STEP_BYTES ||
                                    (knownTotalBytes != null && bytesWritten >= knownTotalBytes)
                                ) {
                                    lastProgressBytes = bytesWritten
                                    onProgress(bytesWritten, knownTotalBytes)
                                }
                            }
                            try {
                                output.flush()
                                output.fd.sync()
                            } catch (io: IOException) {
                                currentCoroutineContext().ensureActive()
                                val reason = if (LocalPdfStorageManager.isOutOfSpaceError(io)) {
                                    DownloadFailureReason.INSUFFICIENT_STORAGE
                                } else {
                                    DownloadFailureReason.STORAGE_WRITE_ERROR
                                }
                                throw DownloadException(
                                    reason = reason,
                                    message = io.localizedMessage ?: "Failed flushing downloaded file.",
                                    cause = io
                                )
                            }
                        }
                    } catch (downloadError: DownloadException) {
                        throw downloadError
                    } catch (io: IOException) {
                        currentCoroutineContext().ensureActive()
                        val reason = if (LocalPdfStorageManager.isOutOfSpaceError(io)) {
                            DownloadFailureReason.INSUFFICIENT_STORAGE
                        } else {
                            DownloadFailureReason.STORAGE_WRITE_ERROR
                        }
                        throw DownloadException(
                            reason = reason,
                            message = io.localizedMessage ?: "Unable to open destination file for writing.",
                            cause = io
                        )
                    }
                }
            } catch (downloadError: DownloadException) {
                throw downloadError
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                currentCoroutineContext().ensureActive()
                throw DownloadException(
                    reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                    message = io.localizedMessage ?: "Network connection lost during download.",
                    cause = io
                )
            } finally {
                isReadingBody.set(false)
            }

            currentCoroutineContext().ensureActive()

            if (bytesWritten != lastProgressBytes) {
                onProgress(bytesWritten, knownTotalBytes)
            }

            return DownloadStreamResult(
                bytesWritten = bytesWritten,
                contentLengthHeaderBytes = knownTotalBytes,
                serverSha256Header = headerChecksum
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (downloadError: DownloadException) {
            throw downloadError
        } catch (io: IOException) {
            currentCoroutineContext().ensureActive()
            throw DownloadException(
                reason = DownloadFailureReason.NETWORK_UNAVAILABLE,
                message = io.localizedMessage ?: "Network error connecting to PDF download source.",
                cause = io
            )
        } catch (unexpected: Throwable) {
            currentCoroutineContext().ensureActive()
            val nestedCancellation = generateSequence(unexpected) { it.cause }
                .take(16)
                .filterIsInstance<CancellationException>()
                .firstOrNull()
            if (nestedCancellation != null) {
                throw nestedCancellation
            }
            throw DownloadException(
                reason = DownloadFailureReason.UNKNOWN,
                message = unexpected.localizedMessage ?: "Unexpected error downloading PDF.",
                cause = unexpected
            )
        } finally {
            cancellationHook?.dispose()
            finalizeConnection?.invoke()
        }
    }

    private class TrackingSSLSocketFactory(
        private val delegate: SSLSocketFactory,
        private val onSocketsCreated: (Array<out Socket>) -> Unit
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            delegate.createSocket(s, host, port, autoClose).also {
                onSocketsCreated(arrayOf(it, s))
            }

        override fun createSocket(host: String, port: Int): Socket =
            delegate.createSocket(host, port).also {
                onSocketsCreated(arrayOf(it))
            }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = delegate.createSocket(host, port, localHost, localPort).also {
            onSocketsCreated(arrayOf(it))
        }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate.createSocket(host, port).also {
                onSocketsCreated(arrayOf(it))
            }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = delegate.createSocket(address, port, localAddress, localPort).also {
            onSocketsCreated(arrayOf(it))
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val JDK_HTTP_READ_SLICE_MS = 200
        const val TEARDOWN_AWAIT_TIMEOUT_MS = 2_000L
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val PROGRESS_NOTIFY_STEP_BYTES = 32 * 1024L
        val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    }
}
