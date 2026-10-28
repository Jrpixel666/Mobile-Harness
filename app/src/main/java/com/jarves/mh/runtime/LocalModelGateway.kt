package com.jarves.mh.runtime

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loopback OpenAI-compatible front for llama-server. Chat requests are streamed from the model
 * with prompt progress (reported through [onProgress]), assembled, repaired with
 * [ToolCallRecovery], then returned as JSON or as a stream to match what the agent asked for.
 * Everything else is passed through unchanged.
 *
 * The agent's instructions and tool list are saved to [warmupFile]; [warmUp] replays them when
 * the model loads so llama-server has them cached before the first real request.
 */
internal class LocalModelGateway(
    port: Int,
    private val upstream: String,
    private val warmupFile: File,
    private val onProgress: (processed: Int, total: Int) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
    private val warmed = CountDownLatch(1)

    fun start(): LocalModelGateway = apply {
        Thread({ acceptLoop() }, "mh-local-model-gateway").apply { isDaemon = true; start() }
    }

    /** Reads the saved instructions into the model's cache; requests wait until this finishes. */
    fun warmUp() {
        try {
            val saved = runCatching { JSONObject(warmupFile.readText()) }.getOrNull() ?: return
            val request = JSONObject(saved.toString())
                .put("messages", saved.getJSONArray("messages").put(JSONObject().put("role", "user").put("content", "Ready?")))
                .put("max_tokens", 1)
            streamCompletion(request)
        } catch (error: Exception) {
            Log.w("LocalModelGateway", "Warm-up failed", error)
        } finally {
            warmed.countDown()
        }
    }

    fun skipWarmUp() = warmed.countDown()

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
                chat(output, JSONObject(body.decodeToString()))
            } else {
                val (code, type, text) = forward(method, path, body.takeIf { length > 0 }?.decodeToString())
                write(output, code, type, text)
            }
        }.onFailure { error ->
            Log.w("LocalModelGateway", "Request failed", error)
            write(output, 502, "application/json", JSONObject().put("error", JSONObject().put("message", error.message ?: "Local model error")).toString())
        }
    }

    private fun chat(output: BufferedOutputStream, request: JSONObject) {
        val wantsStream = request.optBoolean("stream", false)
        val includeUsage = request.optJSONObject("stream_options")?.optBoolean("include_usage") == true
        saveWarmup(request)
        warmed.await(WARMUP_WAIT_MINUTES, TimeUnit.MINUTES)
        val (code, result) = streamCompletion(request)
        if (result !is JSONObject) {
            write(output, code, "application/json", result.toString())
            return
        }
        val completion = ToolCallRecovery.repairCompletion(request, result)
        if (!wantsStream) {
            write(output, 200, "application/json", completion.toString())
            return
        }
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        ToolCallRecovery.streamChunks(completion, includeUsage).forEach { output.write(it.toByteArray()) }
        output.flush()
    }

    /** Only the agent's main requests carry tools; their leading system messages and tools are stable. */
    private fun saveWarmup(request: JSONObject) {
        val tools = request.optJSONArray("tools")?.takeIf { it.length() > 0 } ?: return
        val messages = request.optJSONArray("messages") ?: return
        val system = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: break
            if (message.optString("role") != "system") break
            system.put(message)
        }
        if (system.length() == 0) return
        val snapshot = JSONObject().put("model", request.optString("model")).put("messages", system).put("tools", tools).toString()
        runCatching {
            if (!warmupFile.isFile || warmupFile.readText() != snapshot) warmupFile.writeText(snapshot)
        }
    }

    /** Streams [request] from llama-server; returns the assembled completion or the error body. */
    private fun streamCompletion(request: JSONObject): Pair<Int, Any> {
        val upstreamRequest = JSONObject(request.toString())
            .put("stream", true)
            .put("return_progress", true)
            .put("stream_options", JSONObject().put("include_usage", true))
        val connection = (URL("$upstream/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = SOCKET_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(upstreamRequest.toString().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                return code to connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            val assembler = ChatStreamAssembler(onProgress)
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!assembler.line(line)) break
                }
            }
            200 to assembler.completion()
        } finally {
            connection.disconnect()
        }
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
        warmed.countDown()
        runCatching { server.close() }
    }

    private companion object {
        /** A long answer from a phone CPU can take several minutes. */
        const val SOCKET_TIMEOUT_MS = 15 * 60_000
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        const val WARMUP_WAIT_MINUTES = 10L
    }
}
