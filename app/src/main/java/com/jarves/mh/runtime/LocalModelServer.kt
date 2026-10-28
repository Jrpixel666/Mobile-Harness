package com.jarves.mh.runtime

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A GGUF model the app knows how to download and run on the phone. */
data class LocalModel(
    val id: String,
    val title: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Phones with less memory than this are likely to be killed while it runs. */
    val minRamGb: Int,
)

data class LocalModelStatus(val model: LocalModel, val downloaded: Boolean)

/**
 * Runs llama.cpp's llama-server, packaged as libllamaserver.so, directly on Android (not inside
 * PRoot) on 127.0.0.1, behind [LocalModelGateway]. Agents in the Linux runtime share the phone's
 * network, so they reach it at [BASE_URL] like any OpenAI-compatible server.
 */
class LocalModelServer(context: Context) {
    private val app = context.applicationContext
    private val modelsDir = File(app.filesDir, "models").apply { mkdirs() }
    private val logFile = File(app.cacheDir, "llama-server.log")

    fun file(model: LocalModel) = File(modelsDir, "${model.id}.gguf")

    fun isDownloaded(model: LocalModel) = file(model).let { it.isFile && it.length() == model.sizeBytes }

    fun statuses(): List<LocalModelStatus> = CATALOG.map { LocalModelStatus(it, isDownloaded(it)) }

    fun deviceRamGb(): Int {
        val info = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return ((info.totalMem + (1L shl 29)) shr 30).toInt()
    }

    /** Downloads with resume support and checks the SHA-256 that Hugging Face publishes. */
    suspend fun download(model: LocalModel, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (isDownloaded(model)) return@withContext
        val partial = File(modelsDir, "${model.id}.gguf.part")
        var attempt = 0
        while (partial.length() < model.sizeBytes) {
            try {
                fetchRange(model, partial, onProgress)
            } catch (error: java.io.IOException) {
                if (++attempt >= DOWNLOAD_ATTEMPTS) throw error
                delay(2_000L * attempt)
            }
        }
        check(partial.length() == model.sizeBytes) { "The download of ${model.title} is incomplete." }
        if (sha256(partial) != model.sha256) {
            partial.delete()
            error("${model.title} failed its checksum. Download it again.")
        }
        check(partial.renameTo(file(model))) { "Could not save ${model.title}." }
    }

    private fun fetchRange(model: LocalModel, partial: File, onProgress: (Long, Long) -> Unit) {
        val start = partial.length()
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }
        try {
            val code = connection.responseCode
            check(code == 200 || code == 206) { "Download failed (HTTP $code)." }
            // A server that ignores Range sends the whole file again.
            val append = code == 206
            RandomAccessFile(partial, "rw").use { out ->
                if (append) out.seek(start) else out.setLength(0)
                var written = if (append) start else 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= (4L shl 20)) {
                            lastReport = written
                            onProgress(written, model.sizeBytes)
                        }
                    }
                }
                onProgress(written, model.sizeBytes)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun delete(model: LocalModel) {
        if (runningModelId == model.id) stop()
        file(model).delete()
        File(modelsDir, "${model.id}.gguf.part").delete()
    }

    @Volatile private var process: Process? = null
    @Volatile private var gateway: LocalModelGateway? = null
    @Volatile private var runningModelId: String? = null

    /** Starts the server for [modelId] unless it already serves it; waits until it answers. */
    suspend fun ensureRunning(modelId: String): Unit = withContext(Dispatchers.IO) {
        val model = CATALOG.firstOrNull { it.id == modelId }
            ?: error("Pick an on-device model in Agent → AI provider.")
        check(isDownloaded(model)) { "${model.title} is not downloaded yet. Download it in Agent → AI provider." }
        if (runningModelId == model.id && process?.isAlive == true && isHealthy()) return@withContext
        stop()
        val libraryDir = app.applicationInfo.nativeLibraryDir
        val started = ProcessBuilder(
            serverCommand(File(libraryDir, "libllamaserver.so").absolutePath, file(model).absolutePath, model.id),
        )
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .apply { environment()["LD_LIBRARY_PATH"] = libraryDir }
            .start()
        process = started
        runningModelId = model.id
        gateway = LocalModelGateway(PORT, "http://$HOST:$SERVER_PORT").start()
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) {
                runningModelId = null
                error("The on-device model stopped while loading: ${logTail()}")
            }
            if (isHealthy()) return@withContext
            delay(500)
        }
        stop()
        error("The on-device model took too long to load. Try a smaller model.")
    }

    fun stop() {
        gateway?.close()
        gateway = null
        process?.let { running ->
            running.destroy()
            if (!running.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) running.destroyForcibly()
        }
        process = null
        runningModelId = null
    }

    private fun isHealthy(): Boolean = runCatching {
        val connection = (URL("http://$HOST:$SERVER_PORT/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1_000
            readTimeout = 2_000
        }
        try { connection.responseCode == 200 } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun logTail(): String = runCatching {
        logFile.readLines().filter(String::isNotBlank).takeLast(3).joinToString(" ").take(400)
    }.getOrDefault("")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val HOST = "127.0.0.1"
        /** The agent's address: [LocalModelGateway], which repairs tool calls. */
        const val PORT = 8181
        /** llama-server itself, reached only through the gateway. */
        const val SERVER_PORT = 8182
        const val BASE_URL = "http://$HOST:$PORT/v1"
        /** Coding agents send long system prompts and file contents; smaller contexts overflow. */
        const val CONTEXT_TOKENS = 16_384
        private const val START_TIMEOUT_MS = 180_000L
        private const val DOWNLOAD_ATTEMPTS = 5

        /** --jinja applies the model's own chat template, which is what makes tool calls work. */
        internal fun serverCommand(executable: String, modelPath: String, alias: String): List<String> = listOf(
            executable,
            "-m", modelPath,
            "--alias", alias,
            "--host", HOST,
            "--port", SERVER_PORT.toString(),
            "--jinja",
            "-c", CONTEXT_TOKENS.toString(),
            "--no-webui",
        )

        private const val HF = "https://huggingface.co/Qwen"
        val CATALOG = listOf(
            LocalModel(
                id = "qwen2.5-coder-1.5b",
                title = "Qwen2.5 Coder 1.5B",
                url = "$HF/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
                sha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
                sizeBytes = 1_117_320_768L,
                minRamGb = 4,
            ),
            LocalModel(
                id = "qwen2.5-coder-3b",
                title = "Qwen2.5 Coder 3B",
                url = "$HF/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
                sha256 = "724fb256bec1ff062b2f65e4569e871ad2e95ab2a3989723d1769c54294730b7",
                sizeBytes = 2_104_932_800L,
                minRamGb = 6,
            ),
            LocalModel(
                id = "qwen2.5-coder-7b",
                title = "Qwen2.5 Coder 7B",
                url = "$HF/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
                sha256 = "509287f78cb4d4cf6b3843734733b914b2c158e43e22a7f4bf5e963800894d3c",
                sizeBytes = 4_683_073_536L,
                minRamGb = 10,
            ),
        )
    }
}
