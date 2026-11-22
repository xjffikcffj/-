package com.studentlearning.platform2026.data.download

import com.studentlearning.platform2026.core.common.DispatcherProvider
import com.studentlearning.platform2026.data.local.OfflineDownloadsDataSource
import com.studentlearning.platform2026.data.pdf.PdfFileSourceDataSource
import com.studentlearning.platform2026.domain.model.DownloadException
import com.studentlearning.platform2026.domain.model.DownloadFailureReason
import com.studentlearning.platform2026.domain.model.OfflineDownloadRequest
import com.studentlearning.platform2026.domain.model.PdfSourceDescriptor
import com.studentlearning.platform2026.domain.repository.PdfBrokerConfigurationStatus
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Permission
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class DownloadStreamFetcherCancellationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cancellation before responseCode aborts cleanly without opening socket and deletes part file`() =
        runBlocking {
            val serverAcceptedCount = AtomicInteger(0)
            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                serverAcceptedCount.incrementAndGet()
                exchange.sendHeaders(200, 1024L)
                exchange.output.write(ByteArray(1024))
            }
            tlsServer.start()

            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = { jobProvider ->
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url -> tlsOpener(url) },
                        onBeforeResponseCode = {
                            jobProvider()?.cancel(CancellationException("Cancel before responseCode"))
                        }
                    )
                }
            )

            try {
                var capturedError: Throwable? = null
                val downloadJob = launch(Dispatchers.IO) {
                    try {
                        harness.engine.executeDownload(sampleRequest("file-pre-resp", "v1"))
                    } catch (t: Throwable) {
                        capturedError = t
                        throw t
                    }
                }
                harness.bindJob(downloadJob)
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue(downloadJob.isCancelled)
                assertTrue(capturedError is CancellationException)
                assertEquals(0, serverAcceptedCount.get())
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
                assertFalse(harness.storageManager.resolveCompletedFile("file-pre-resp", "v1").exists())
            } finally {
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `early cancellation before responseCode still closes connection if responseCode starts racing`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val disconnectThreads = CopyOnWriteArrayList<Thread>()
            val serverSawConnection = CountDownLatch(1)
            val allowServerFinish = CountDownLatch(1)
            val serverSawClosed = AtomicBoolean(false)
            val serverCompletedResponse = AtomicBoolean(false)

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                serverSawConnection.countDown()
                try {
                    allowServerFinish.await(5, TimeUnit.SECONDS)
                    exchange.sendHeaders(200, 1024L)
                    exchange.output.write(ByteArray(1024))
                    exchange.output.flush()
                    serverCompletedResponse.set(true)
                } catch (_: IOException) {
                    serverSawClosed.set(true)
                }
            }
            tlsServer.start()

            val baseOpener: (URL) -> HttpsURLConnection = { url ->
                val raw = tlsOpener(url)
                val trackedFactory = recordingSocketFactory(raw.sslSocketFactory, capturedSockets)
                raw.sslSocketFactory = trackedFactory
                object : DelegatingHttpsURLConnection(raw) {
                    override fun disconnect() {
                        disconnectThreads.add(Thread.currentThread())
                        super.disconnect()
                    }
                }
            }

            // Force a synthetic pre-responseCode disconnect() by wrapping responseCode to cancel
            // on first entry, simulating the nanosecond race between ensureActive() and connect().
            val cancellingOpener: (URL) -> HttpsURLConnection = { url ->
                val delegate = baseOpener(url)
                delegate
            }

            lateinit var downloadJobRef: kotlinx.coroutines.Job
            val hooks = HttpDownloadFetcherHooks(
                connectionOpener = { url ->
                    val real = cancellingOpener(url)
                    object : DelegatingHttpsURLConnection(real) {
                        override fun getResponseCode(): Int {
                            // Cancel concurrently inside responseCode entry before TCP/TLS connect completes,
                            // proving that early cancel doesn't poison finally cleanup.
                            downloadJobRef.cancel(CancellationException("Raced before connect"))
                            return super.getResponseCode()
                        }
                    }
                }
            )

            val fetcher = HttpDownloadStreamFetcher(allowInsecureHttpForTesting = false, hooks = hooks)
            val storageRoot = tempFolder.newFolder("early_race_storage")
            val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)
            val prefsFile = File(tempFolder.newFolder(), "early_race.preferences_pb")
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataSource = OfflineDownloadsDataSource(
                PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { prefsFile })
            )
            val engine = DownloadExecutionEngine(
                sourceDataSource = staticHttpsSource(tlsServer.port),
                streamFetcher = fetcher,
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = realIoDispatchers
            )

            try {
                var thrown: Throwable? = null
                downloadJobRef = launch(Dispatchers.IO) {
                    try {
                        engine.executeDownload(sampleRequest("file-early-race", "v1"))
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }
                withTimeout(5_000L) { downloadJobRef.join() }

                assertTrue(thrown is CancellationException)
                assertTrue(disconnectThreads.isNotEmpty())
                assertTrue("Expected SSLSocket creation attempt before abort", capturedSockets.isNotEmpty())
                capturedSockets.forEach { socket ->
                    assertTrue("Created socket must be closed after early race", socket.isClosed)
                }
                assertFalse("Server must not complete response after early abort", serverCompletedResponse.get())
                assertEquals(0, tlsServer.completedHandlers.get())
                assertTrue(storageManager.listAllTempFiles().isEmpty())
            } finally {
                allowServerFinish.countDown()
                dataStoreScope.cancel()
                tlsServer.close()
            }
        }

    @Test
    fun `cancellation during connection establishment and header wait closes real SSLSocket immediately`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val serverAcceptedHandshake = CountDownLatch(1)
            val allowServerCheckPeerClose = CountDownLatch(1)
            val serverObservedPeerClose = AtomicBoolean(false)
            val serverDone = CountDownLatch(1)

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                serverAcceptedHandshake.countDown()
                try {
                    // Hold before sending HTTP status/headers while client is blocked inside responseCode().
                    allowServerCheckPeerClose.await(5, TimeUnit.SECONDS)
                    // A single small write after peer close can succeed into the kernel TCP send buffer;
                    // reading the TLS input stream observes the client's close_notify EOF (-1) or TCP RST deterministically.
                    serverObservedPeerClose.set(exchange.awaitPeerClosed(timeoutMillis = 5_000))
                } finally {
                    serverDone.countDown()
                }
            }
            tlsServer.start()

            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = {
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url ->
                            tlsOpener(url).apply {
                                sslSocketFactory = recordingSocketFactory(sslSocketFactory, capturedSockets)
                            }
                        }
                    )
                }
            )

            try {
                var thrown: Throwable? = null
                val downloadJob = launch(Dispatchers.IO) {
                    try {
                        harness.engine.executeDownload(sampleRequest("file-connect-hang", "v1"))
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }

                assertTrue(serverAcceptedHandshake.await(5, TimeUnit.SECONDS))
                // Server enters handler only after TLS handshake and HTTP request headers were read,
                // so client is deterministically awaiting the HTTP response status line here.
                downloadJob.cancel(CancellationException("Cancel during responseCode"))
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue(thrown is CancellationException)
                assertTrue("Expected at least one SSLSocket to be created", capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })

                allowServerCheckPeerClose.countDown()
                assertTrue(serverDone.await(5, TimeUnit.SECONDS))
                assertTrue(
                    "Server TLS socket must observe client close_notify EOF or IOException",
                    serverObservedPeerClose.get()
                )
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            } finally {
                allowServerCheckPeerClose.countDown()
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `cancellation between SSLSocket creation and tracking registration closes socket and throws CancellationException`() =
        runBlocking {
            val socketsObservedAtHook = CopyOnWriteArrayList<Socket>()
            val allowServerClose = CountDownLatch(1)
            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                allowServerClose.await(5, TimeUnit.SECONDS)
                runCatching {
                    exchange.sendHeaders(200, 1024L)
                    exchange.output.write(ByteArray(1024))
                }
            }
            tlsServer.start()

            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = { jobProvider ->
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url -> tlsOpener(url) },
                        onAfterSocketCreatedBeforeTracking = { createdSocket ->
                            socketsObservedAtHook.add(createdSocket)
                            // Cancel synchronously from another thread right before `pendingSockets.add`.
                            val cancelThread = Thread {
                                jobProvider()?.cancel(CancellationException("Cancel between create and add"))
                            }
                            cancelThread.start()
                            cancelThread.join(3_000L)
                        }
                    )
                }
            )

            try {
                var thrown: Throwable? = null
                val downloadJob = launch(Dispatchers.IO) {
                    try {
                        harness.engine.executeDownload(sampleRequest("file-socket-race", "v1"))
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }
                harness.bindJob(downloadJob)
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue("Expected CancellationException, got $thrown", thrown is CancellationException)
                assertTrue("Socket must have been created before race point", socketsObservedAtHook.isNotEmpty())
                socketsObservedAtHook.forEach { socket ->
                    assertTrue("Socket created before tracking must still be closed", socket.isClosed)
                }
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            } finally {
                allowServerClose.countDown()
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `https cancellation while blocked in body read closes real SSLSocket releases resources and deletes part file`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val firstChunkSent = CountDownLatch(1)
            val allowSecondChunk = CountDownLatch(1)
            val serverSawClosed = AtomicBoolean(false)
            val serverDone = CountDownLatch(1)

            val initialChunk = ("%PDF-1.4\n" + "A".repeat(40 * 1024)).toByteArray(Charsets.US_ASCII)
            val secondChunk = ByteArray(64 * 1024) { 'B'.code.toByte() }
            val totalLength = initialChunk.size.toLong() + secondChunk.size.toLong()

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                try {
                    exchange.sendHeaders(200, totalLength)
                    exchange.output.write(initialChunk)
                    exchange.output.flush()
                    firstChunkSent.countDown()

                    allowSecondChunk.await(5, TimeUnit.SECONDS)
                    if (exchange.awaitPeerClosed(timeoutMillis = 2_000)) {
                        serverSawClosed.set(true)
                    } else {
                        repeat(64) {
                            exchange.output.write(secondChunk)
                            exchange.output.flush()
                        }
                    }
                } catch (_: IOException) {
                    serverSawClosed.set(true)
                } finally {
                    serverDone.countDown()
                }
            }
            tlsServer.start()

            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = {
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url ->
                            tlsOpener(url).apply {
                                sslSocketFactory = recordingSocketFactory(sslSocketFactory, capturedSockets)
                            }
                        }
                    )
                }
            )

            try {
                val clientGotFirstChunk = CompletableDeferred<Unit>()
                var thrown: Throwable? = null
                val downloadJob = launch(Dispatchers.IO) {
                    try {
                        harness.engine.executeDownload(
                            request = sampleRequest("file-https-read-cancel", "v1"),
                            onProgress = { bytes, _, _ ->
                                if (bytes >= 32L * 1024L) {
                                    clientGotFirstChunk.complete(Unit)
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }

                assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { clientGotFirstChunk.await() }
                assertTrue(harness.storageManager.listAllTempFiles().isNotEmpty())

                downloadJob.cancel(CancellationException("Cancel during HTTPS body read"))
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue(thrown is CancellationException)
                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })

                allowSecondChunk.countDown()
                assertTrue(serverDone.await(5, TimeUnit.SECONDS))
                assertTrue(serverSawClosed.get())
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            } finally {
                allowSecondChunk.countDown()
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `concurrent cancellation racing with finally closes socket cleanly without double-close failure`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val disconnectCount = AtomicInteger(0)
            val payload = ("%PDF-1.4\n" + "C".repeat(8 * 1024)).toByteArray(Charsets.US_ASCII)

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                exchange.sendHeaders(200, payload.size.toLong())
                exchange.output.write(payload)
                exchange.output.flush()
            }
            tlsServer.start()

            val raceBarrier = CyclicBarrier(2)
            val cancelRacedDone = CountDownLatch(1)
            val racingThreadRef = AtomicReference<Thread?>(null)
            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = { jobProvider ->
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url ->
                            val raw = tlsOpener(url).apply {
                                sslSocketFactory = recordingSocketFactory(sslSocketFactory, capturedSockets)
                            }
                            object : DelegatingHttpsURLConnection(raw) {
                                override fun disconnect() {
                                    disconnectCount.incrementAndGet()
                                    super.disconnect()
                                }
                            }
                        },
                        onBeforeFinallyCleanup = {
                            // Synchronize both threads at CyclicBarrier so cancel() and finally enter stateLock concurrently.
                            val racingThread = Thread({
                                try {
                                    raceBarrier.await(3, TimeUnit.SECONDS)
                                    jobProvider()?.cancel(CancellationException("Racing cancel with finally"))
                                } finally {
                                    cancelRacedDone.countDown()
                                }
                            }, "finally-race-canceller")
                            racingThreadRef.set(racingThread)
                            racingThread.start()
                            raceBarrier.await(3, TimeUnit.SECONDS)
                        }
                    )
                }
            )

            try {
                val downloadJob = launch(Dispatchers.IO) {
                    harness.engine.executeDownload(sampleRequest("file-finally-race", "v1"))
                }
                harness.bindJob(downloadJob)
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue(cancelRacedDone.await(3, TimeUnit.SECONDS))
                racingThreadRef.get()?.join(3_000L)
                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })
                assertEquals(1, disconnectCount.get())
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            } finally {
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `cancelling from main thread offloads socket close and disconnect off main thread`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val socketCloseThreads = CopyOnWriteArrayList<Thread>()
            val disconnectThreads = CopyOnWriteArrayList<Thread>()
            val firstChunkSent = CountDownLatch(1)
            val allowSecondChunk = CountDownLatch(1)
            val serverSawClosed = AtomicBoolean(false)
            val serverDone = CountDownLatch(1)

            val initialChunk = ("%PDF-1.4\n" + "M".repeat(40 * 1024)).toByteArray(Charsets.US_ASCII)
            val secondChunk = ByteArray(64 * 1024) { 'N'.code.toByte() }

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                try {
                    exchange.sendHeaders(200, (initialChunk.size + secondChunk.size).toLong())
                    exchange.output.write(initialChunk)
                    exchange.output.flush()
                    firstChunkSent.countDown()

                    allowSecondChunk.await(5, TimeUnit.SECONDS)
                    exchange.output.write(secondChunk)
                    exchange.output.flush()
                } catch (_: IOException) {
                    serverSawClosed.set(true)
                } finally {
                    serverDone.countDown()
                }
            }
            tlsServer.start()

            val simulatedMainThread = AtomicReference<Thread?>(null)
            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = {
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url ->
                            val raw = tlsOpener(url)
                            val base = raw.sslSocketFactory
                            raw.sslSocketFactory = object : SSLSocketFactory() {
                                override fun getDefaultCipherSuites() = base.defaultCipherSuites
                                override fun getSupportedCipherSuites() = base.supportedCipherSuites
                                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
                                    wrapTrackingClose(base.createSocket(s, host, port, autoClose) as SSLSocket)
                                override fun createSocket(host: String, port: Int): Socket =
                                    wrapTrackingClose(base.createSocket(host, port) as SSLSocket)
                                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                                    wrapTrackingClose(base.createSocket(host, port, localHost, localPort) as SSLSocket)
                                override fun createSocket(host: InetAddress, port: Int): Socket =
                                    wrapTrackingClose(base.createSocket(host, port) as SSLSocket)
                                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                                    wrapTrackingClose(base.createSocket(address, port, localAddress, localPort) as SSLSocket)

                                private fun wrapTrackingClose(real: SSLSocket): SSLSocket {
                                    capturedSockets.add(real)
                                    return object : DelegatingSSLSocket(real) {
                                        override fun close() {
                                            socketCloseThreads.add(Thread.currentThread())
                                            super.close()
                                        }
                                    }
                                }
                            }
                            object : DelegatingHttpsURLConnection(raw) {
                                override fun disconnect() {
                                    disconnectThreads.add(Thread.currentThread())
                                    super.disconnect()
                                }
                            }
                        },
                        isMainThreadChecker = {
                            val target = simulatedMainThread.get()
                            target != null && Thread.currentThread() === target
                        }
                    )
                }
            )

            try {
                val clientGotFirstChunk = CompletableDeferred<Unit>()
                val downloadJob = launch(Dispatchers.IO) {
                    harness.engine.executeDownload(
                        request = sampleRequest("file-main-offload", "v1"),
                        onProgress = { bytes, _, _ ->
                            if (bytes >= 32L * 1024L) {
                                clientGotFirstChunk.complete(Unit)
                            }
                        }
                    )
                }

                assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { clientGotFirstChunk.await() }

                val cancelFinished = CountDownLatch(1)
                val fakeMain = Thread({
                    try {
                        downloadJob.cancel(CancellationException("Cancelled from UI Main thread"))
                    } finally {
                        cancelFinished.countDown()
                    }
                }, "fake-android-main")
                simulatedMainThread.set(fakeMain)
                fakeMain.start()

                assertTrue(cancelFinished.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })
                assertTrue(socketCloseThreads.isNotEmpty())
                assertTrue(disconnectThreads.isNotEmpty())
                assertTrue(
                    "socket.close() must NEVER run on the Main thread",
                    socketCloseThreads.none { it === fakeMain }
                )
                assertTrue(
                    "disconnect() must NEVER run on the Main thread",
                    disconnectThreads.none { it === fakeMain }
                )

                allowSecondChunk.countDown()
                assertTrue(serverDone.await(5, TimeUnit.SECONDS))
                assertTrue(serverSawClosed.get())
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            } finally {
                allowSecondChunk.countDown()
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `cancelling one download does not close sockets of other concurrent downloads`() =
        runBlocking {
            val capturedSocketsByPath = ConcurrentHashMap<String, CopyOnWriteArrayList<Socket>>()
            val slowFirstChunkSent = CountDownLatch(1)
            val allowSlowSecondChunk = CountDownLatch(1)
            val fastFirstChunkSent = CountDownLatch(1)
            val allowFastSecondChunk = CountDownLatch(1)
            val slowSawClosed = AtomicBoolean(false)
            val slowDone = CountDownLatch(1)

            val slowFirstChunk = ("%PDF-1.4\n" + "S".repeat(40 * 1024)).toByteArray(Charsets.US_ASCII)
            val slowSecondChunk = ByteArray(64 * 1024) { 'X'.code.toByte() }
            val fastFirstChunk = ("%PDF-1.4\n" + "F".repeat(40 * 1024)).toByteArray(Charsets.US_ASCII)
            val fastSecondChunk = ("%EOF\n" + "Y".repeat(32 * 1024)).toByteArray(Charsets.US_ASCII)
            val fullFastPayload = fastFirstChunk + fastSecondChunk
            val fastSha = sha256Hex(fullFastPayload)

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                when (exchange.path) {
                    "/slow.pdf" -> {
                        try {
                            exchange.sendHeaders(200, (slowFirstChunk.size + slowSecondChunk.size).toLong())
                            exchange.output.write(slowFirstChunk)
                            exchange.output.flush()
                            slowFirstChunkSent.countDown()

                            allowSlowSecondChunk.await(5, TimeUnit.SECONDS)
                            exchange.output.write(slowSecondChunk)
                            exchange.output.flush()
                        } catch (_: IOException) {
                            slowSawClosed.set(true)
                        } finally {
                            slowDone.countDown()
                        }
                    }

                    "/fast.pdf" -> {
                        exchange.sendHeaders(
                            statusCode = 200,
                            contentLength = fullFastPayload.size.toLong(),
                            extraHeaders = mapOf("X-Content-Sha256" to fastSha)
                        )
                        exchange.output.write(fastFirstChunk)
                        exchange.output.flush()
                        fastFirstChunkSent.countDown()

                        allowFastSecondChunk.await(5, TimeUnit.SECONDS)
                        exchange.output.write(fastSecondChunk)
                        exchange.output.flush()
                    }
                }
            }
            tlsServer.start()

            val storageRoot = tempFolder.newFolder("concurrent_iso_storage")
            val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)
            val prefsFile = File(tempFolder.newFolder(), "concurrent_iso.preferences_pb")
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataSource = OfflineDownloadsDataSource(
                PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { prefsFile })
            )
            val fetcher = HttpDownloadStreamFetcher(
                allowInsecureHttpForTesting = false,
                hooks = HttpDownloadFetcherHooks(
                    connectionOpener = { url ->
                        tlsOpener(url).apply {
                            val list = capturedSocketsByPath.computeIfAbsent(url.path) {
                                CopyOnWriteArrayList()
                            }
                            sslSocketFactory = recordingSocketFactory(sslSocketFactory, list)
                        }
                    }
                )
            )
            val engine = DownloadExecutionEngine(
                sourceDataSource = object : PdfFileSourceDataSource {
                    override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
                    override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
                        PdfSourceDescriptor.AuthorizedRemoteUrl(
                            url = "https://127.0.0.1:${tlsServer.port}/$fileId.pdf",
                            cacheKey = "$fileId/${activeVersionId.orEmpty()}"
                        )
                },
                streamFetcher = fetcher,
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = realIoDispatchers
            )

            try {
                val slowGotChunk = CompletableDeferred<Unit>()
                val fastGotChunk = CompletableDeferred<Unit>()

                val slowJob = launch(Dispatchers.IO) {
                    engine.executeDownload(
                        request = sampleRequest("slow", "v1"),
                        onProgress = { bytes, _, _ ->
                            if (bytes >= 32L * 1024L) slowGotChunk.complete(Unit)
                        }
                    )
                }
                val fastJob = async(Dispatchers.IO) {
                    engine.executeDownload(
                        request = OfflineDownloadRequest(
                            fileId = "fast",
                            versionId = "v1",
                            versionNumber = 1,
                            versionLabel = "v1",
                            fileTitle = "Fast PDF",
                            expectedFileSizeBytes = fullFastPayload.size.toLong(),
                            expectedChecksumSha256 = fastSha
                        ),
                        onProgress = { bytes, _, _ ->
                            if (bytes >= 32L * 1024L) fastGotChunk.complete(Unit)
                        }
                    )
                }

                assertTrue(slowFirstChunkSent.await(5, TimeUnit.SECONDS))
                assertTrue(fastFirstChunkSent.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) {
                    slowGotChunk.await()
                    fastGotChunk.await()
                }

                // Cancel slow download while fast download is still paused mid-stream.
                slowJob.cancel(CancellationException("Cancel slow only"))
                withTimeout(5_000L) { slowJob.join() }

                val slowSockets = capturedSocketsByPath["/slow.pdf"].orEmpty()
                val fastSockets = capturedSocketsByPath["/fast.pdf"].orEmpty()
                assertTrue(slowSockets.isNotEmpty())
                assertTrue(fastSockets.isNotEmpty())
                assertTrue("Slow download sockets must be closed", slowSockets.all { it.isClosed })
                assertTrue("Concurrent fast download sockets must stay OPEN", fastSockets.none { it.isClosed })

                // Release remaining bytes for fast download and verify full verified completion.
                allowFastSecondChunk.countDown()
                val savedFast = withTimeout(5_000L) { fastJob.await() }
                assertEquals(fullFastPayload.size.toLong(), savedFast.fileSizeBytes)
                assertEquals(fastSha, savedFast.checksumSha256)
                assertTrue(File(savedFast.localFilePath).exists())
                assertFalse(storageManager.resolveCompletedFile("slow", "v1").exists())
                assertTrue(storageManager.listAllTempFiles().isEmpty())

                allowSlowSecondChunk.countDown()
                assertTrue(slowDone.await(5, TimeUnit.SECONDS))
                assertTrue(slowSawClosed.get())
            } finally {
                allowSlowSecondChunk.countDown()
                allowFastSecondChunk.countDown()
                dataStoreScope.cancel()
                tlsServer.close()
            }
        }

    @Test
    fun `normal https download succeeds with sha256 verification after repeated cancellations`() =
        runBlocking {
            val validPayload = ("%PDF-1.7\n" + "ValidContent-".repeat(4000) + "\n%%EOF").toByteArray(Charsets.UTF_8)
            val expectedSha = sha256Hex(validPayload)
            val firstAttemptChunkSent = CountDownLatch(1)
            val allowFirstCancel = CountDownLatch(1)
            val requestSeq = AtomicInteger(0)

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                val attempt = requestSeq.incrementAndGet()
                if (attempt == 1) {
                    exchange.sendHeaders(200, validPayload.size.toLong())
                    exchange.output.write(validPayload, 0, 36 * 1024)
                    exchange.output.flush()
                    firstAttemptChunkSent.countDown()
                    allowFirstCancel.await(5, TimeUnit.SECONDS)
                } else {
                    exchange.sendHeaders(
                        statusCode = 200,
                        contentLength = validPayload.size.toLong(),
                        extraHeaders = mapOf("X-Content-Sha256" to expectedSha)
                    )
                    exchange.output.write(validPayload)
                    exchange.output.flush()
                }
            }
            tlsServer.start()

            val sharedFetcher = HttpDownloadStreamFetcher(
                allowInsecureHttpForTesting = false,
                hooks = HttpDownloadFetcherHooks(connectionOpener = { url -> tlsOpener(url) })
            )
            val storageRoot = tempFolder.newFolder("normal_after_cancel_storage")
            val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)
            val prefsFile = File(tempFolder.newFolder(), "normal_after_cancel.preferences_pb")
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataSource = OfflineDownloadsDataSource(
                PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { prefsFile })
            )
            val engine = DownloadExecutionEngine(
                sourceDataSource = staticHttpsSource(tlsServer.port),
                streamFetcher = sharedFetcher,
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = realIoDispatchers
            )

            try {
                // 1) Cancel first attempt deterministically after first chunk is written to disk.
                val firstGotChunk = CompletableDeferred<Unit>()
                val firstJob = launch(Dispatchers.IO) {
                    engine.executeDownload(
                        request = sampleRequest("file-recovery", "v1"),
                        onProgress = { bytes, _, _ ->
                            if (bytes >= 32L * 1024L) {
                                firstGotChunk.complete(Unit)
                            }
                        }
                    )
                }
                assertTrue(firstAttemptChunkSent.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { firstGotChunk.await() }
                firstJob.cancel(CancellationException("Cancel attempt 1"))
                withTimeout(5_000L) { firstJob.join() }
                allowFirstCancel.countDown()
                assertTrue(storageManager.listAllTempFiles().isEmpty())

                // 2) Normal download with the exact same fetcher instance must succeed cleanly.
                val saved = withTimeout(5_000L) {
                    engine.executeDownload(
                        OfflineDownloadRequest(
                            fileId = "file-recovery",
                            versionId = "v1",
                            versionNumber = 1,
                            versionLabel = "v1",
                            fileTitle = "ملف طبيعي بعد الإلغاء",
                            expectedFileSizeBytes = validPayload.size.toLong(),
                            expectedChecksumSha256 = expectedSha
                        )
                    )
                }

                assertEquals(validPayload.size.toLong(), saved.fileSizeBytes)
                assertEquals(expectedSha, saved.checksumSha256)
                val completedFile = File(saved.localFilePath)
                assertTrue(completedFile.exists())
                assertEquals(validPayload.toList(), completedFile.readBytes().toList())
                assertTrue(storageManager.listAllTempFiles().isEmpty())
            } finally {
                allowFirstCancel.countDown()
                dataStoreScope.cancel()
                tlsServer.close()
            }
        }

    @Test
    fun `rejected teardown executor on main thread cancel leaves ownership for finally without leaking sockets`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val socketCloseThreads = CopyOnWriteArrayList<Thread>()
            val disconnectThreads = CopyOnWriteArrayList<Thread>()
            val firstChunkSent = CountDownLatch(1)
            val allowSecondChunk = CountDownLatch(1)
            val serverSawClosed = AtomicBoolean(false)
            val serverDone = CountDownLatch(1)

            val initialChunk = ("%PDF-1.4\n" + "R".repeat(40 * 1024)).toByteArray(Charsets.US_ASCII)
            val secondChunk = ByteArray(64 * 1024) { 'J'.code.toByte() }

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                try {
                    exchange.sendHeaders(200, (initialChunk.size + secondChunk.size).toLong())
                    exchange.output.write(initialChunk)
                    exchange.output.flush()
                    firstChunkSent.countDown()

                    allowSecondChunk.await(5, TimeUnit.SECONDS)
                    exchange.output.write(secondChunk)
                    exchange.output.flush()
                } catch (_: IOException) {
                    serverSawClosed.set(true)
                } finally {
                    serverDone.countDown()
                }
            }
            tlsServer.start()

            val simulatedMainThread = AtomicReference<Thread?>(null)
            val executorInvocations = AtomicInteger(0)
            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = {
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url ->
                            wrapClosingTrackers(
                                raw = tlsOpener(url),
                                capturedSockets = capturedSockets,
                                socketCloseThreads = socketCloseThreads,
                                disconnectThreads = disconnectThreads
                            )
                        },
                        isMainThreadChecker = {
                            val target = simulatedMainThread.get()
                            target != null && Thread.currentThread() === target
                        },
                        cancelTeardownExecutor = Executor {
                            executorInvocations.incrementAndGet()
                            throw RejectedExecutionException("Simulated teardown executor rejection")
                        }
                    )
                }
            )

            try {
                val clientGotFirstChunk = CompletableDeferred<Unit>()
                var thrown: Throwable? = null
                val downloadJob = launch(Dispatchers.IO) {
                    try {
                        harness.engine.executeDownload(
                            request = sampleRequest("file-reject-main", "v1"),
                            onProgress = { bytes, _, _ ->
                                if (bytes >= 32L * 1024L) {
                                    clientGotFirstChunk.complete(Unit)
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }

                assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { clientGotFirstChunk.await() }

                val fakeMainThrowable = AtomicReference<Throwable?>(null)
                val cancelFinished = CountDownLatch(1)
                val fakeMain = Thread({
                    try {
                        downloadJob.cancel(CancellationException("Main cancel hitting rejected executor"))
                    } catch (t: Throwable) {
                        fakeMainThrowable.set(t)
                    } finally {
                        cancelFinished.countDown()
                    }
                }, "fake-main-reject")
                simulatedMainThread.set(fakeMain)
                fakeMain.start()

                assertTrue(cancelFinished.await(5, TimeUnit.SECONDS))
                fakeMain.join(3_000L)
                assertEquals(null, fakeMainThrowable.get())
                assertEquals(1, executorInvocations.get())

                // Release server write so IO loop observes cancellation if read unblocked by stream finish/close,
                // then verify IO finally owns and closes all resources off the Main thread.
                allowSecondChunk.countDown()
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue(thrown is CancellationException)
                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })
                assertTrue(socketCloseThreads.isNotEmpty())
                assertTrue(disconnectThreads.isNotEmpty())
                assertTrue(socketCloseThreads.none { it === fakeMain })
                assertTrue(disconnectThreads.none { it === fakeMain })
                assertTrue(serverDone.await(5, TimeUnit.SECONDS))
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            } finally {
                allowSecondChunk.countDown()
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `executor rejection racing concurrently with finalizeConnection leaks zero sockets and closes in finally`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val socketCloseThreads = CopyOnWriteArrayList<Thread>()
            val disconnectThreads = CopyOnWriteArrayList<Thread>()
            val disconnectCount = AtomicInteger(0)
            val payload = ("%PDF-1.4\n" + "Z".repeat(8 * 1024)).toByteArray(Charsets.US_ASCII)

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                exchange.sendHeaders(200, payload.size.toLong())
                exchange.output.write(payload)
                exchange.output.flush()
            }
            tlsServer.start()

            val simulatedMainThread = AtomicReference<Thread?>(null)
            val readyToCancelAtFlush = CountDownLatch(1)
            val cancelEnteredExecutor = CountDownLatch(1)
            val raceBarrier = CyclicBarrier(2)
            val rejectionThrown = CountDownLatch(1)

            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = {
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url ->
                            val tracked = wrapClosingTrackers(
                                raw = tlsOpener(url),
                                capturedSockets = capturedSockets,
                                socketCloseThreads = socketCloseThreads,
                                disconnectThreads = disconnectThreads
                            )
                            object : DelegatingHttpsURLConnection(tracked) {
                                override fun disconnect() {
                                    disconnectCount.incrementAndGet()
                                    super.disconnect()
                                }
                            }
                        },
                        fileOutputStreamOpener = { file ->
                            object : FileOutputStream(file) {
                                override fun flush() {
                                    super.flush()
                                    // Trigger cancel while cancellationHook is still active before downloadToFile exits to finally.
                                    readyToCancelAtFlush.countDown()
                                    assertTrue(cancelEnteredExecutor.await(5, TimeUnit.SECONDS))
                                }
                            }
                        },
                        isMainThreadChecker = {
                            val target = simulatedMainThread.get()
                            target != null && Thread.currentThread() === target
                        },
                        cancelTeardownExecutor = Executor {
                            // Unblock IO thread to unwind into finally, then align executor rejection with finalizeConnection.
                            cancelEnteredExecutor.countDown()
                            raceBarrier.await(3, TimeUnit.SECONDS)
                            rejectionThrown.countDown()
                            throw RejectedExecutionException("Concurrent teardown rejection")
                        },
                        onBeforeFinallyCleanup = {
                            raceBarrier.await(3, TimeUnit.SECONDS)
                        }
                    )
                }
            )

            try {
                var thrown: Throwable? = null
                val downloadJob = launch(Dispatchers.IO) {
                    try {
                        harness.engine.executeDownload(sampleRequest("file-reject-race", "v1"))
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }

                assertTrue(readyToCancelAtFlush.await(5, TimeUnit.SECONDS))

                val fakeMainThrowable = AtomicReference<Throwable?>(null)
                val fakeMainDone = CountDownLatch(1)
                val fakeMain = Thread({
                    try {
                        downloadJob.cancel(CancellationException("Main cancel racing finally + rejection"))
                    } catch (t: Throwable) {
                        fakeMainThrowable.set(t)
                    } finally {
                        fakeMainDone.countDown()
                    }
                }, "fake-main-reject-race")
                simulatedMainThread.set(fakeMain)
                fakeMain.start()

                assertTrue(rejectionThrown.await(5, TimeUnit.SECONDS))
                assertTrue(fakeMainDone.await(5, TimeUnit.SECONDS))
                fakeMain.join(3_000L)
                withTimeout(5_000L) { downloadJob.join() }

                assertEquals(null, fakeMainThrowable.get())
                assertTrue("Expected CancellationException, got $thrown", thrown is CancellationException)
                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })
                assertEquals(1, disconnectCount.get())
                assertTrue(socketCloseThreads.isNotEmpty())
                assertTrue(disconnectThreads.isNotEmpty())
                assertTrue(disconnectThreads.none { it === fakeMain })
                assertTrue(socketCloseThreads.none { it === fakeMain })
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            } finally {
                readyToCancelAtFlush.countDown()
                cancelEnteredExecutor.countDown()
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `bounded teardown executor queue saturation triggers AbortPolicy without blocking main thread or leaking sockets`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val socketCloseThreads = CopyOnWriteArrayList<Thread>()
            val disconnectThreads = CopyOnWriteArrayList<Thread>()
            val firstChunkSent = CountDownLatch(1)
            val allowSecondChunk = CountDownLatch(1)
            val serverDone = CountDownLatch(1)

            val initialChunk = ("%PDF-1.4\n" + "Q".repeat(40 * 1024)).toByteArray(Charsets.US_ASCII)
            val secondChunk = ByteArray(64 * 1024) { 'P'.code.toByte() }

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                try {
                    exchange.sendHeaders(200, (initialChunk.size + secondChunk.size).toLong())
                    exchange.output.write(initialChunk)
                    exchange.output.flush()
                    firstChunkSent.countDown()

                    allowSecondChunk.await(5, TimeUnit.SECONDS)
                    exchange.output.write(secondChunk)
                    exchange.output.flush()
                } catch (_: IOException) {
                    // Expected
                } finally {
                    serverDone.countDown()
                }
            }
            tlsServer.start()

            val saturatedExecutor = ThreadPoolExecutor(
                1,
                1,
                15L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(1),
                ThreadPoolExecutor.AbortPolicy()
            )
            val workerBusy = CountDownLatch(1)
            val releaseSaturatingTasks = CountDownLatch(1)
            // Occupy the single worker thread + the single bounded queue slot (2 tasks total).
            saturatedExecutor.execute {
                workerBusy.countDown()
                releaseSaturatingTasks.await(5, TimeUnit.SECONDS)
            }
            assertTrue(workerBusy.await(5, TimeUnit.SECONDS))
            saturatedExecutor.execute {
                releaseSaturatingTasks.await(5, TimeUnit.SECONDS)
            }

            val simulatedMainThread = AtomicReference<Thread?>(null)
            val harness = createHttpsEngineHarness(
                port = tlsServer.port,
                hookBuilder = {
                    HttpDownloadFetcherHooks(
                        connectionOpener = { url ->
                            wrapClosingTrackers(
                                raw = tlsOpener(url),
                                capturedSockets = capturedSockets,
                                socketCloseThreads = socketCloseThreads,
                                disconnectThreads = disconnectThreads
                            )
                        },
                        isMainThreadChecker = {
                            val target = simulatedMainThread.get()
                            target != null && Thread.currentThread() === target
                        },
                        cancelTeardownExecutor = saturatedExecutor
                    )
                }
            )

            try {
                val clientGotFirstChunk = CompletableDeferred<Unit>()
                var thrown: Throwable? = null
                val downloadJob = launch(Dispatchers.IO) {
                    try {
                        harness.engine.executeDownload(
                            request = sampleRequest("file-queue-saturated", "v1"),
                            onProgress = { bytes, _, _ ->
                                if (bytes >= 32L * 1024L) {
                                    clientGotFirstChunk.complete(Unit)
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }

                assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { clientGotFirstChunk.await() }

                val fakeMainThrowable = AtomicReference<Throwable?>(null)
                val cancelReturned = CountDownLatch(1)
                val fakeMain = Thread({
                    try {
                        downloadJob.cancel(CancellationException("Cancel against saturated bounded executor"))
                    } catch (t: Throwable) {
                        fakeMainThrowable.set(t)
                    } finally {
                        cancelReturned.countDown()
                    }
                }, "fake-main-saturated")
                simulatedMainThread.set(fakeMain)
                fakeMain.start()

                // AbortPolicy rejects immediately while releaseSaturatingTasks is still held (no CallerRunsPolicy block).
                assertTrue(cancelReturned.await(2, TimeUnit.SECONDS))
                fakeMain.join(3_000L)
                assertEquals(null, fakeMainThrowable.get())

                allowSecondChunk.countDown()
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue(thrown is CancellationException)
                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })
                assertTrue(socketCloseThreads.isNotEmpty())
                assertTrue(disconnectThreads.isNotEmpty())
                assertTrue(socketCloseThreads.none { it === fakeMain })
                assertTrue(disconnectThreads.none { it === fakeMain })
                assertTrue(serverDone.await(5, TimeUnit.SECONDS))
                assertTrue(harness.storageManager.listAllTempFiles().isEmpty())
            } finally {
                releaseSaturatingTasks.countDown()
                allowSecondChunk.countDown()
                saturatedExecutor.shutdownNow()
                harness.close()
                tlsServer.close()
            }
        }

    @Test
    fun `interrupting IO thread while awaiting active teardown restores interrupt flag and closes sockets`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val disconnectStarted = CountDownLatch(1)
            val allowDisconnectFinish = CountDownLatch(1)
            val firstChunkSent = CountDownLatch(1)
            val allowSecondChunk = CountDownLatch(1)

            val initialChunk = ("%PDF-1.4\n" + "I".repeat(40 * 1024)).toByteArray(Charsets.US_ASCII)
            val secondChunk = ByteArray(64 * 1024) { 'T'.code.toByte() }

            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                try {
                    exchange.sendHeaders(200, (initialChunk.size + secondChunk.size).toLong())
                    exchange.output.write(initialChunk)
                    exchange.output.flush()
                    firstChunkSent.countDown()

                    allowSecondChunk.await(5, TimeUnit.SECONDS)
                    exchange.output.write(secondChunk)
                    exchange.output.flush()
                } catch (_: IOException) {
                    // Expected
                }
            }
            tlsServer.start()

            val simulatedMainThread = AtomicReference<Thread?>(null)
            val ioThreadRef = AtomicReference<Thread?>(null)
            val teardownExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-delayed-teardown").apply { isDaemon = true }
            }
            val singleIoPool = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-dedicated-io").apply { isDaemon = true }
            }
            val singleIoDispatcher = singleIoPool.asCoroutineDispatcher()

            val fetcher = HttpDownloadStreamFetcher(
                allowInsecureHttpForTesting = false,
                hooks = HttpDownloadFetcherHooks(
                    connectionOpener = { url ->
                        val raw = tlsOpener(url).apply {
                            sslSocketFactory = recordingSocketFactory(sslSocketFactory, capturedSockets)
                        }
                        object : DelegatingHttpsURLConnection(raw) {
                            override fun disconnect() {
                                disconnectStarted.countDown()
                                allowDisconnectFinish.await(5, TimeUnit.SECONDS)
                                super.disconnect()
                            }
                        }
                    },
                    isMainThreadChecker = {
                        val target = simulatedMainThread.get()
                        target != null && Thread.currentThread() === target
                    },
                    cancelTeardownExecutor = teardownExecutor,
                    onBeforeFinallyCleanup = {
                        // Wait until offloaded teardown is holding activeTeardownLatch inside disconnect(),
                        // then interrupt the IO thread right before activeTeardownLatch.await(...).
                        assertTrue(disconnectStarted.await(5, TimeUnit.SECONDS))
                        ioThreadRef.get()?.interrupt()
                    }
                )
            )
            val storageRoot = tempFolder.newFolder("interrupt_restore_storage")
            val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)

            try {
                val clientGotFirstChunk = CompletableDeferred<Unit>()
                val interruptedFlagPreserved = AtomicBoolean(false)
                var thrown: Throwable? = null

                val downloadJob = launch(singleIoDispatcher) {
                    ioThreadRef.set(Thread.currentThread())
                    var targetFile: File? = null
                    try {
                        targetFile = storageManager.createTempPartFile("file-interrupt", "v1")
                        fetcher.downloadToFile(
                            source = PdfSourceDescriptor.AuthorizedRemoteUrl(
                                url = "https://127.0.0.1:${tlsServer.port}/file-interrupt.pdf",
                                cacheKey = "file-interrupt/v1"
                            ),
                            destinationFile = targetFile,
                            onProgress = { bytes, _ ->
                                if (bytes >= 32L * 1024L) {
                                    clientGotFirstChunk.complete(Unit)
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        thrown = t
                        interruptedFlagPreserved.set(Thread.interrupted())
                    } finally {
                        storageManager.deleteTempFileQuietly(targetFile)
                    }
                }

                assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { clientGotFirstChunk.await() }

                val cancelDone = CountDownLatch(1)
                val fakeMain = Thread({
                    try {
                        downloadJob.cancel(CancellationException("Cancel before teardown interrupt"))
                    } finally {
                        cancelDone.countDown()
                    }
                }, "fake-main-interrupt-test")
                simulatedMainThread.set(fakeMain)
                fakeMain.start()

                assertTrue(cancelDone.await(5, TimeUnit.SECONDS))
                withTimeout(5_000L) { downloadJob.join() }

                assertTrue(thrown is CancellationException)
                assertTrue(
                    "Thread interrupt status must be restored rather than swallowed on InterruptedException",
                    interruptedFlagPreserved.get()
                )
                allowDisconnectFinish.countDown()
                teardownExecutor.shutdown()
                assertTrue(teardownExecutor.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })
            } finally {
                allowDisconnectFinish.countDown()
                allowSecondChunk.countDown()
                teardownExecutor.shutdownNow()
                singleIoPool.shutdownNow()
                tlsServer.close()
            }
        }

    @Test
    fun `disk write and flush IOException while coroutine is active maps to storage DownloadException and cleans up`() =
        runBlocking {
            val payload = ("%PDF-1.4\n" + "D".repeat(12 * 1024)).toByteArray(Charsets.UTF_8)
            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                exchange.sendHeaders(200, payload.size.toLong())
                exchange.output.write(payload)
                exchange.output.flush()
            }
            tlsServer.start()

            suspend fun assertStorageError(
                fileId: String,
                failOnFlush: Boolean,
                ioMessage: String,
                expectedReason: DownloadFailureReason
            ) {
                val capturedSockets = CopyOnWriteArrayList<Socket>()
                val storageManager = LocalPdfStorageManager(storageRoot = tempFolder.newFolder())
                val prefsFile = File(tempFolder.newFolder(), "$fileId.preferences_pb")
                val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val dataSource = OfflineDownloadsDataSource(
                    PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { prefsFile })
                )
                val fetcher = HttpDownloadStreamFetcher(
                    allowInsecureHttpForTesting = false,
                    hooks = HttpDownloadFetcherHooks(
                        connectionOpener = { url ->
                            tlsOpener(url).apply {
                                sslSocketFactory = recordingSocketFactory(sslSocketFactory, capturedSockets)
                            }
                        },
                        fileOutputStreamOpener = { file ->
                            object : FileOutputStream(file) {
                                override fun write(b: ByteArray, off: Int, len: Int) {
                                    if (!failOnFlush) throw IOException(ioMessage)
                                    super.write(b, off, len)
                                }

                                override fun flush() {
                                    if (failOnFlush) throw IOException(ioMessage)
                                    super.flush()
                                }
                            }
                        }
                    )
                )
                val engine = DownloadExecutionEngine(
                    sourceDataSource = staticHttpsSource(tlsServer.port),
                    streamFetcher = fetcher,
                    storageManager = storageManager,
                    downloadsDataSource = dataSource,
                    dispatcherProvider = realIoDispatchers
                )
                try {
                    val thrown = try {
                        engine.executeDownload(sampleRequest(fileId, "v1"))
                        null
                    } catch (t: Throwable) {
                        t
                    }
                    assertTrue("Expected DownloadException, got $thrown", thrown is DownloadException)
                    assertEquals(expectedReason, (thrown as DownloadException).reason)
                    assertTrue(capturedSockets.isNotEmpty())
                    assertTrue(capturedSockets.all { it.isClosed })
                    assertTrue(storageManager.listAllTempFiles().isEmpty())
                } finally {
                    dataStoreScope.cancel()
                }
            }

            try {
                assertStorageError(
                    fileId = "file-write-nospace",
                    failOnFlush = false,
                    ioMessage = "No space left on device",
                    expectedReason = DownloadFailureReason.INSUFFICIENT_STORAGE
                )
                assertStorageError(
                    fileId = "file-write-ioerr",
                    failOnFlush = false,
                    ioMessage = "Read-only file system",
                    expectedReason = DownloadFailureReason.STORAGE_WRITE_ERROR
                )
                assertStorageError(
                    fileId = "file-flush-nospace",
                    failOnFlush = true,
                    ioMessage = "ENOSPC: No space left on device",
                    expectedReason = DownloadFailureReason.INSUFFICIENT_STORAGE
                )
            } finally {
                tlsServer.close()
            }
        }

    @Test
    fun `disk write IOException preserves CancellationException when coroutine is concurrently cancelled`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val payload = ("%PDF-1.4\n" + "W".repeat(12 * 1024)).toByteArray(Charsets.UTF_8)
            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                exchange.sendHeaders(200, payload.size.toLong())
                exchange.output.write(payload)
                exchange.output.flush()
            }
            tlsServer.start()

            lateinit var jobRef: kotlinx.coroutines.Job
            val storageManager = LocalPdfStorageManager(storageRoot = tempFolder.newFolder("write_cancel_storage"))
            val prefsFile = File(tempFolder.newFolder(), "write_cancel.preferences_pb")
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataSource = OfflineDownloadsDataSource(
                PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { prefsFile })
            )
            val fetcher = HttpDownloadStreamFetcher(
                allowInsecureHttpForTesting = false,
                hooks = HttpDownloadFetcherHooks(
                    connectionOpener = { url ->
                        tlsOpener(url).apply {
                            sslSocketFactory = recordingSocketFactory(sslSocketFactory, capturedSockets)
                        }
                    },
                    fileOutputStreamOpener = { file ->
                        object : FileOutputStream(file) {
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                jobRef.cancel(CancellationException("Cancelled right as disk write fails"))
                                throw IOException("No space left on device")
                            }
                        }
                    }
                )
            )
            val engine = DownloadExecutionEngine(
                sourceDataSource = staticHttpsSource(tlsServer.port),
                streamFetcher = fetcher,
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = realIoDispatchers
            )

            try {
                var thrown: Throwable? = null
                jobRef = launch(Dispatchers.IO) {
                    try {
                        engine.executeDownload(sampleRequest("file-write-cancel", "v1"))
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }
                withTimeout(5_000L) { jobRef.join() }

                assertTrue("Expected CancellationException, got $thrown", thrown is CancellationException)
                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })
                assertTrue(storageManager.listAllTempFiles().isEmpty())
            } finally {
                dataStoreScope.cancel()
                tlsServer.close()
            }
        }

    @Test
    fun `disk flush IOException preserves CancellationException when coroutine is concurrently cancelled`() =
        runBlocking {
            val capturedSockets = CopyOnWriteArrayList<Socket>()
            val payload = ("%PDF-1.4\n" + "F".repeat(12 * 1024)).toByteArray(Charsets.UTF_8)
            val tlsServer = LoopbackTlsServer(sslContext) { exchange ->
                exchange.sendHeaders(200, payload.size.toLong())
                exchange.output.write(payload)
                exchange.output.flush()
            }
            tlsServer.start()

            lateinit var jobRef: kotlinx.coroutines.Job
            val storageManager = LocalPdfStorageManager(storageRoot = tempFolder.newFolder("flush_cancel_storage"))
            val prefsFile = File(tempFolder.newFolder(), "flush_cancel.preferences_pb")
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataSource = OfflineDownloadsDataSource(
                PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { prefsFile })
            )
            val fetcher = HttpDownloadStreamFetcher(
                allowInsecureHttpForTesting = false,
                hooks = HttpDownloadFetcherHooks(
                    connectionOpener = { url ->
                        tlsOpener(url).apply {
                            sslSocketFactory = recordingSocketFactory(sslSocketFactory, capturedSockets)
                        }
                    },
                    fileOutputStreamOpener = { file ->
                        object : FileOutputStream(file) {
                            override fun flush() {
                                jobRef.cancel(CancellationException("Cancelled right as disk flush fails"))
                                throw IOException("No space left on device")
                            }
                        }
                    }
                )
            )
            val engine = DownloadExecutionEngine(
                sourceDataSource = staticHttpsSource(tlsServer.port),
                streamFetcher = fetcher,
                storageManager = storageManager,
                downloadsDataSource = dataSource,
                dispatcherProvider = realIoDispatchers
            )

            try {
                var thrown: Throwable? = null
                jobRef = launch(Dispatchers.IO) {
                    try {
                        engine.executeDownload(sampleRequest("file-flush-cancel", "v1"))
                    } catch (t: Throwable) {
                        thrown = t
                        throw t
                    }
                }
                withTimeout(5_000L) { jobRef.join() }

                assertTrue("Expected CancellationException, got $thrown", thrown is CancellationException)
                assertTrue(capturedSockets.isNotEmpty())
                assertTrue(capturedSockets.all { it.isClosed })
                assertTrue(storageManager.listAllTempFiles().isEmpty())
            } finally {
                dataStoreScope.cancel()
                tlsServer.close()
            }
        }

    // region Helpers

    private val realIoDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.IO
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private fun sampleRequest(fileId: String, versionId: String) = OfflineDownloadRequest(
        fileId = fileId,
        versionId = versionId,
        versionNumber = 1,
        versionLabel = versionId,
        fileTitle = "اختبار التنزيل"
    )

    private fun staticHttpsSource(port: Int) = object : PdfFileSourceDataSource {
        override fun getBrokerStatus() = PdfBrokerConfigurationStatus.CONFIGURED
        override suspend fun resolvePdfSource(fileId: String, activeVersionId: String?) =
            PdfSourceDescriptor.AuthorizedRemoteUrl(
                url = "https://127.0.0.1:$port/$fileId.pdf",
                cacheKey = "$fileId/${activeVersionId.orEmpty()}"
            )
    }

    private class EngineHarness(
        val engine: DownloadExecutionEngine,
        val storageManager: LocalPdfStorageManager,
        private val dataStoreScope: CoroutineScope,
        private val jobHolder: Array<kotlinx.coroutines.Job?>
    ) {
        fun bindJob(job: kotlinx.coroutines.Job) {
            jobHolder[0] = job
        }

        fun close() {
            dataStoreScope.cancel()
        }
    }

    private fun createHttpsEngineHarness(
        port: Int,
        hookBuilder: (jobProvider: () -> kotlinx.coroutines.Job?) -> HttpDownloadFetcherHooks
    ): EngineHarness {
        val jobHolder = arrayOfNulls<kotlinx.coroutines.Job>(1)
        val fetcher = HttpDownloadStreamFetcher(
            allowInsecureHttpForTesting = false,
            hooks = hookBuilder { jobHolder[0] }
        )
        val storageRoot = tempFolder.newFolder()
        val storageManager = LocalPdfStorageManager(storageRoot = storageRoot)
        val prefsFile = File(tempFolder.newFolder(), "harness.preferences_pb")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataSource = OfflineDownloadsDataSource(
            PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { prefsFile })
        )
        val engine = DownloadExecutionEngine(
            sourceDataSource = staticHttpsSource(port),
            streamFetcher = fetcher,
            storageManager = storageManager,
            downloadsDataSource = dataSource,
            dispatcherProvider = realIoDispatchers
        )
        return EngineHarness(engine, storageManager, dataStoreScope, jobHolder)
    }

    private fun tlsOpener(url: URL): HttpsURLConnection {
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
    }

    private fun wrapClosingTrackers(
        raw: HttpsURLConnection,
        capturedSockets: MutableList<Socket>,
        socketCloseThreads: MutableList<Thread>,
        disconnectThreads: MutableList<Thread>
    ): HttpsURLConnection {
        val base = raw.sslSocketFactory
        raw.sslSocketFactory = object : SSLSocketFactory() {
            override fun getDefaultCipherSuites() = base.defaultCipherSuites
            override fun getSupportedCipherSuites() = base.supportedCipherSuites
            override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
                wrapTrackingClose(base.createSocket(s, host, port, autoClose) as SSLSocket)
            override fun createSocket(host: String, port: Int): Socket =
                wrapTrackingClose(base.createSocket(host, port) as SSLSocket)
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                wrapTrackingClose(base.createSocket(host, port, localHost, localPort) as SSLSocket)
            override fun createSocket(host: InetAddress, port: Int): Socket =
                wrapTrackingClose(base.createSocket(host, port) as SSLSocket)
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                wrapTrackingClose(base.createSocket(address, port, localAddress, localPort) as SSLSocket)

            private fun wrapTrackingClose(real: SSLSocket): SSLSocket {
                capturedSockets.add(real)
                return object : DelegatingSSLSocket(real) {
                    override fun close() {
                        socketCloseThreads.add(Thread.currentThread())
                        super.close()
                    }
                }
            }
        }
        return object : DelegatingHttpsURLConnection(raw) {
            override fun disconnect() {
                disconnectThreads.add(Thread.currentThread())
                super.disconnect()
            }
        }
    }

    private fun recordingSocketFactory(
        delegate: SSLSocketFactory,
        sink: MutableList<Socket>
    ): SSLSocketFactory = object : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            delegate.createSocket(s, host, port, autoClose).also { sink.add(it) }
        override fun createSocket(host: String, port: Int): Socket =
            delegate.createSocket(host, port).also { sink.add(it) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort).also { sink.add(it) }
        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate.createSocket(host, port).also { sink.add(it) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort).also { sink.add(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class LoopbackTlsExchange(
        private val socket: SSLSocket,
        val path: String,
        val output: OutputStream
    ) {
        fun awaitPeerClosed(timeoutMillis: Int): Boolean {
            val previousTimeout = runCatching { socket.soTimeout }.getOrDefault(0)
            return try {
                socket.soTimeout = timeoutMillis
                socket.inputStream.read() == -1
            } catch (_: java.net.SocketTimeoutException) {
                false
            } catch (_: IOException) {
                true
            } finally {
                runCatching { socket.soTimeout = previousTimeout }
            }
        }

        fun sendHeaders(
            statusCode: Int,
            contentLength: Long,
            extraHeaders: Map<String, String> = emptyMap()
        ) {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(statusCode).append(" OK\r\n")
            sb.append("Content-Type: application/pdf\r\n")
            sb.append("Content-Length: ").append(contentLength).append("\r\n")
            sb.append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("\r\n")
            output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }

    private class LoopbackTlsServer(
        sslContext: SSLContext,
        private val handler: (LoopbackTlsExchange) -> Unit
    ) : AutoCloseable {
        private val serverSocket = (sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        private val executor = Executors.newCachedThreadPool()
        private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
        private val running = AtomicBoolean(false)
        val completedHandlers = AtomicInteger(0)

        val port: Int
            get() = serverSocket.localPort

        fun start() {
            if (!running.compareAndSet(false, true)) return
            Thread({
                while (running.get() && !serverSocket.isClosed) {
                    val client = try {
                        serverSocket.accept() as SSLSocket
                    } catch (_: IOException) {
                        break
                    }
                    activeSockets.add(client)
                    executor.execute {
                        try {
                            client.startHandshake()
                            val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.ISO_8859_1))
                            val requestLine = reader.readLine() ?: return@execute
                            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            handler(LoopbackTlsExchange(client, path, client.outputStream))
                            completedHandlers.incrementAndGet()
                        } catch (_: Throwable) {
                            // Expected when client aborts/cancels socket mid-handshake or mid-stream.
                        } finally {
                            activeSockets.remove(client)
                            runCatching { client.close() }
                        }
                    }
                }
            }, "loopback-tls-accept").apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            running.set(false)
            runCatching { serverSocket.close() }
            activeSockets.forEach { runCatching { it.close() } }
            activeSockets.clear()
            executor.shutdownNow()
        }
    }

    private open class DelegatingHttpsURLConnection(
        private val delegate: HttpsURLConnection
    ) : HttpsURLConnection(delegate.url) {
        init {
            sslSocketFactory = delegate.sslSocketFactory
            hostnameVerifier = delegate.hostnameVerifier
        }

        override fun connect() = delegate.connect()
        override fun disconnect() = delegate.disconnect()
        override fun usingProxy(): Boolean = delegate.usingProxy()
        override fun getCipherSuite(): String = delegate.cipherSuite
        override fun getLocalCertificates() = delegate.localCertificates
        override fun getServerCertificates() = delegate.serverCertificates
        override fun getPeerPrincipal() = delegate.peerPrincipal
        override fun getLocalPrincipal() = delegate.localPrincipal
        override fun getResponseCode(): Int = delegate.responseCode
        override fun getResponseMessage(): String = delegate.responseMessage
        override fun getContentLengthLong(): Long = delegate.contentLengthLong
        override fun getHeaderField(name: String?): String? = delegate.getHeaderField(name)
        override fun getInputStream(): InputStream = delegate.inputStream
        override fun getErrorStream(): InputStream? = delegate.errorStream
        override fun getOutputStream(): OutputStream = delegate.outputStream
        override fun setRequestMethod(method: String?) { delegate.requestMethod = method }
        override fun getRequestMethod(): String = delegate.requestMethod
        override fun setConnectTimeout(timeout: Int) { delegate.connectTimeout = timeout }
        override fun getConnectTimeout(): Int = delegate.connectTimeout
        override fun setReadTimeout(timeout: Int) { delegate.readTimeout = timeout }
        override fun getReadTimeout(): Int = delegate.readTimeout
        override fun setInstanceFollowRedirects(followRedirects: Boolean) {
            delegate.instanceFollowRedirects = followRedirects
        }
        override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects
        override fun setRequestProperty(key: String?, value: String?) {
            delegate.setRequestProperty(key, value)
        }
        override fun getRequestProperty(key: String?): String? = delegate.getRequestProperty(key)
        override fun getPermission(): Permission = delegate.permission
        override fun setSSLSocketFactory(sf: SSLSocketFactory?) {
            super.setSSLSocketFactory(sf)
            delegate.sslSocketFactory = sf
        }
        override fun getSSLSocketFactory(): SSLSocketFactory = delegate.sslSocketFactory
        override fun setHostnameVerifier(v: HostnameVerifier?) {
            super.setHostnameVerifier(v)
            delegate.hostnameVerifier = v
        }
        override fun getHostnameVerifier(): HostnameVerifier = delegate.hostnameVerifier
    }

    private open class DelegatingSSLSocket(
        private val delegate: SSLSocket
    ) : SSLSocket() {
        override fun getSupportedCipherSuites() = delegate.supportedCipherSuites
        override fun getEnabledCipherSuites() = delegate.enabledCipherSuites
        override fun setEnabledCipherSuites(suites: Array<out String>?) { delegate.enabledCipherSuites = suites }
        override fun getSupportedProtocols() = delegate.supportedProtocols
        override fun getEnabledProtocols() = delegate.enabledProtocols
        override fun setEnabledProtocols(protocols: Array<out String>?) { delegate.enabledProtocols = protocols }
        override fun getSession() = delegate.session
        override fun addHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {
            delegate.addHandshakeCompletedListener(listener)
        }
        override fun removeHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {
            delegate.removeHandshakeCompletedListener(listener)
        }
        override fun startHandshake() = delegate.startHandshake()
        override fun setUseClientMode(mode: Boolean) { delegate.useClientMode = mode }
        override fun getUseClientMode(): Boolean = delegate.useClientMode
        override fun setNeedClientAuth(need: Boolean) { delegate.needClientAuth = need }
        override fun getNeedClientAuth(): Boolean = delegate.needClientAuth
        override fun setWantClientAuth(want: Boolean) { delegate.wantClientAuth = want }
        override fun getWantClientAuth(): Boolean = delegate.wantClientAuth
        override fun setEnableSessionCreation(flag: Boolean) { delegate.enableSessionCreation = flag }
        override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation
        override fun getInputStream(): InputStream = delegate.inputStream
        override fun getOutputStream(): OutputStream = delegate.outputStream
        override fun connect(endpoint: java.net.SocketAddress?) = delegate.connect(endpoint)
        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
        override fun bind(bindpoint: java.net.SocketAddress?) = delegate.bind(bindpoint)
        override fun getInetAddress(): InetAddress? = delegate.inetAddress
        override fun getLocalAddress(): InetAddress = delegate.localAddress
        override fun getPort(): Int = delegate.port
        override fun getLocalPort(): Int = delegate.localPort
        override fun isConnected(): Boolean = delegate.isConnected
        override fun isBound(): Boolean = delegate.isBound
        override fun isClosed(): Boolean = delegate.isClosed
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()
        override fun isInputShutdown(): Boolean = delegate.isInputShutdown
        override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
        override fun close() = delegate.close()
    }

    // endregion

    companion object {
        private lateinit var keystoreTempFile: File
        private lateinit var sslContext: SSLContext

        @JvmStatic
        @BeforeClass
        fun initLoopbackTls() {
            keystoreTempFile = Files.createTempFile("haqibati-fetcher-test-", ".p12").toFile()
            keystoreTempFile.delete()
            val password = "test-keystore-pass".toCharArray()
            val keytoolPath = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val process = ProcessBuilder(
                keytoolPath,
                "-genkeypair",
                "-alias", "loopback",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-keystore", keystoreTempFile.absolutePath,
                "-storetype", "PKCS12",
                "-storepass", String(password),
                "-keypass", String(password),
                "-dname", "CN=127.0.0.1",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                fail("keytool failed ($exitCode): $output")
            }

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                keystoreTempFile.inputStream().use { load(it, password) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            sslContext = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, tmf.trustManagers, null)
            }
        }

        @JvmStatic
        @AfterClass
        fun cleanupLoopbackTls() {
            if (::keystoreTempFile.isInitialized) {
                keystoreTempFile.delete()
            }
        }
    }
}
