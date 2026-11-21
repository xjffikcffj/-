package com.studentlearning.platform2026.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LoopbackHeaders {
    private val map = linkedMapOf<String, MutableList<String>>()

    fun add(key: String, value: String) {
        val normalized = findKey(key) ?: key
        map.getOrPut(normalized) { mutableListOf() }.add(value)
    }

    fun set(key: String, value: String) {
        val existing = findKey(key)
        if (existing != null && existing != key) {
            map.remove(existing)
        }
        map[key] = mutableListOf(value)
    }

    fun getFirst(key: String): String? = findKey(key)?.let { map[it]?.firstOrNull() }

    fun entries(): Set<Map.Entry<String, List<String>>> = map.entries

    private fun findKey(key: String): String? =
        map.keys.firstOrNull { it.equals(key, ignoreCase = true) }
}

class LoopbackHttpExchange internal constructor(
    private val socket: Socket,
    val requestMethod: String,
    val requestURI: URI,
    val requestHeaders: LoopbackHeaders,
    val requestBody: InputStream
) {
    val responseHeaders = LoopbackHeaders()
    private val rawOut: OutputStream = socket.getOutputStream()
    private val headersSent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val responseBody: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            rawOut.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            rawOut.write(b, off, len)
        }

        override fun flush() {
            rawOut.flush()
        }

        override fun close() {
            runCatching { rawOut.flush() }
            this@LoopbackHttpExchange.close()
        }
    }

    fun sendResponseHeaders(statusCode: Int, responseLength: Long) {
        if (!headersSent.compareAndSet(false, true)) return
        val statusText = when (statusCode) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            302 -> "Found"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            410 -> "Gone"
            412 -> "Precondition Failed"
            422 -> "Unprocessable Entity"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Status"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
        var hasContentLength = false
        var hasConnection = false
        for ((name, values) in responseHeaders.entries()) {
            if (name.equals("Content-Length", ignoreCase = true)) hasContentLength = true
            if (name.equals("Connection", ignoreCase = true)) hasConnection = true
            for (value in values) {
                sb.append(name).append(": ").append(value).append("\r\n")
            }
        }
        if (!hasContentLength) {
            val contentLength = if (responseLength >= 0L) responseLength else 0L
            sb.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        if (!hasConnection) {
            sb.append("Connection: close\r\n")
        }
        sb.append("\r\n")
        rawOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        rawOut.flush()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { rawOut.flush() }
            runCatching { socket.close() }
        }
    }
}

class LoopbackHttpServer private constructor(
    bindAddress: InetSocketAddress
) {
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(bindAddress)
    }
    private val contexts = ConcurrentHashMap<String, (LoopbackHttpExchange) -> Unit>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val running = AtomicBoolean(false)
    private var defaultExecutor: ExecutorService? = null
    private var acceptThread: Thread? = null

    var executor: Executor? = null

    val address: InetSocketAddress
        get() = serverSocket.localSocketAddress as InetSocketAddress

    fun createContext(path: String, handler: (LoopbackHttpExchange) -> Unit) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        contexts[normalized] = handler
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val dispatchExecutor: Executor = executor ?: Executors.newCachedThreadPool().also {
            defaultExecutor = it
        }
        acceptThread = Thread({
            while (running.get() && !serverSocket.isClosed) {
                val clientSocket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                activeSockets.add(clientSocket)
                dispatchExecutor.execute {
                    try {
                        serveClient(clientSocket)
                    } finally {
                        activeSockets.remove(clientSocket)
                        runCatching { clientSocket.close() }
                    }
                }
            }
        }, "loopback-http-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop(delaySeconds: Int) {
        running.set(false)
        runCatching { serverSocket.close() }
        for (socket in activeSockets) {
            runCatching { socket.close() }
        }
        activeSockets.clear()
        defaultExecutor?.shutdownNow()
        defaultExecutor = null
        acceptThread?.interrupt()
    }

    private fun serveClient(socket: Socket) {
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(Locale.US)
        val uri = URI.create(parts[1])

        val requestHeaders = LoopbackHeaders()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val name = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                requestHeaders.add(name, value)
            }
        }

        if (requestHeaders.getFirst("Expect")?.equals("100-continue", ignoreCase = true) == true) {
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        val bodyBytes = readRequestBodyBytes(input, requestHeaders)
        val exchange = LoopbackHttpExchange(
            socket = socket,
            requestMethod = method,
            requestURI = uri,
            requestHeaders = requestHeaders,
            requestBody = ByteArrayInputStream(bodyBytes)
        )

        val handler = resolveHandler(uri.path ?: "/")
        try {
            if (handler != null) {
                handler(exchange)
            } else {
                exchange.sendResponseHeaders(404, 0L)
            }
        } finally {
            exchange.close()
        }
    }

    private fun resolveHandler(requestPath: String): ((LoopbackHttpExchange) -> Unit)? {
        val exact = contexts[requestPath]
        if (exact != null) return exact
        return contexts.entries
            .filter { (ctxPath, _) ->
                ctxPath == "/" || requestPath == ctxPath || requestPath.startsWith(
                    if (ctxPath.endsWith("/")) ctxPath else "$ctxPath/"
                )
            }
            .maxByOrNull { it.key.length }
            ?.value
    }

    private fun readRequestBodyBytes(input: InputStream, headers: LoopbackHeaders): ByteArray {
        val transferEncoding = headers.getFirst("Transfer-Encoding")
        if (transferEncoding?.contains("chunked", ignoreCase = true) == true) {
            return readChunkedBody(input)
        }
        val contentLength = headers.getFirst("Content-Length")?.toLongOrNull() ?: 0L
        if (contentLength <= 0L) return ByteArray(0)
        val remaining = contentLength.toInt()
        val result = ByteArray(remaining)
        var offset = 0
        while (offset < remaining) {
            val read = input.read(result, offset, remaining - offset)
            if (read == -1) throw EOFException("Unexpected EOF reading request body")
            offset += read
        }
        return result
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input) ?: break
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (chunkSize == 0) {
                // Drain trailing CRLF
                readAsciiLine(input)
                break
            }
            val chunk = ByteArray(chunkSize)
            var offset = 0
            while (offset < chunkSize) {
                val read = input.read(chunk, offset, chunkSize - offset)
                if (read == -1) throw EOFException("Unexpected EOF in chunked body")
                offset += read
            }
            out.write(chunk)
            readAsciiLine(input)
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var prevWasCr = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString(Charsets.ISO_8859_1.name())
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val length = if (prevWasCr && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.ISO_8859_1)
            }
            prevWasCr = b == '\r'.code
            baos.write(b)
        }
    }

    companion object {
        fun create(address: InetSocketAddress, backlog: Int): LoopbackHttpServer {
            return LoopbackHttpServer(address)
        }
    }
}
