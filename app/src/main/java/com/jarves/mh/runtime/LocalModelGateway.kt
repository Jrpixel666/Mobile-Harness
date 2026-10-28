package com.jarves.mh.runtime

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Loopback OpenAI-compatible front for llama-server. Chat requests are answered in one piece
 * by the model, repaired with [ToolCallRecovery], then returned as JSON or as a stream to match
 * what the agent asked for. Everything else is passed through unchanged.
 */
internal class LocalModelGateway(port: Int, private val upstream: String) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }

    fun start(): LocalModelGateway = apply {
        Thread({ acceptLoop() }, "mh-local-model-gateway").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                runCatching { socket.soTimeout = SOCKET_TIMEOUT_MS }
                Thread({ socket.use(::handle) }, "mh-local-model-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_REQUEST_BYTES) {
            write(output, 413, "application/json", """{"error":{"message":"Request body is too large"}}""")
            return
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        runCatching {
            if (method == "POST" && path.substringBefore('?').endsWith("/chat/completions")) {
                chat(output, JSONObject(body.decodeToString()), path)
            } else {
                val (code, type, text) = forward(method, path, body.takeIf { length > 0 }?.decodeToString())
                write(output, code, type, text)
            }
        }.onFailure { error ->
            Log.w("LocalModelGateway", "Request failed", error)
            write(output, 502, "application/json", JSONObject().put("error", JSONObject().put("message", error.message ?: "Local model error")).toString())
        }
    }

    private fun chat(output: BufferedOutputStream, request: JSONObject, path: String) {
        val wantsStream = request.optBoolean("stream", false)
        val includeUsage = request.optJSONObject("stream_options")?.optBoolean("include_usage") == true
        request.put("stream", false)
        request.remove("stream_options")
        val (code, type, text) = forward("POST", path, request.toString())
        if (code !in 200..299) {
            write(output, code, type, text)
            return
        }
        val completion = ToolCallRecovery.repairCompletion(request, JSONObject(text))
        if (!wantsStream) {
            write(output, 200, "application/json", completion.toString())
            return
        }
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        ToolCallRecovery.streamChunks(completion, includeUsage).forEach { output.write(it.toByteArray()) }
        output.flush()
    }

    private fun forward(method: String, path: String, body: String?): Triple<Int, String, String> {
        val connection = (URL(upstream + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = SOCKET_TIMEOUT_MS
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            body?.let { text -> connection.outputStream.use { it.write(text.toByteArray()) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            Triple(code, connection.contentType ?: "application/json", stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun write(output: BufferedOutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        output.write("HTTP/1.1 $code ${if (code in 200..299) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val next = input.read()
            if (next < 0) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) return line.toString().trimEnd('\r')
            line.append(next.toChar())
            if (line.length > 16_384) return null
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    private companion object {
        /** A long answer from a phone CPU can take several minutes. */
        const val SOCKET_TIMEOUT_MS = 15 * 60_000
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
    }
}
