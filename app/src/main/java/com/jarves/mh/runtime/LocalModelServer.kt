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
    /** Found through Hugging Face search rather than the built-in list; removed again on delete. */
    val custom: Boolean = false,
)

data class LocalModelStatus(val model: LocalModel, val downloaded: Boolean)

/** How far the model has read the current request (its prompt) before it starts replying. */
data class LocalModelProgress(val processed: Int, val total: Int)

/**
 * Runs llama.cpp's llama-server, packaged as libllamaserver.so, directly on Android (not inside
 * PRoot) on 127.0.0.1, behind [LocalModelGateway]. Agents in the Linux runtime share the phone's
 * network, so they reach it at [BASE_URL] like any OpenAI-compatible server.
 */
class LocalModelServer(
    context: Context,
    /** Optional Hugging Face token, sent only to huggingface.co for gated models. */
    private val huggingFaceToken: () -> String? = { null },
) {
    private val app = context.applicationContext
    private val modelsDir = File(app.filesDir, "models").apply { mkdirs() }
    private val logFile = File(app.cacheDir, "llama-server.log")
    private val warmupFile = File(modelsDir, "agent-warmup.json")
    /** Held only while the model is working, so a dark screen does not pause it. */
    private val wakeLock = app.getSystemService(android.os.PowerManager::class.java)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "mh:on-device-model")
        .apply { setReferenceCounted(true) }

    init {
        active = this
    }
    private val _progress = kotlinx.coroutines.flow.MutableStateFlow<LocalModelProgress?>(null)
    val progress: kotlinx.coroutines.flow.StateFlow<LocalModelProgress?> = _progress

    fun resetProgress() {
        _progress.value = null
    }

    fun file(model: LocalModel) = File(modelsDir, "${model.id}.gguf")

    fun isDownloaded(model: LocalModel) = file(model).let { it.isFile && it.length() == model.sizeBytes }

    private val customFile = File(modelsDir, "custom-models.json")

    /** Built-in models, then ones added from Hugging Face search. */
    fun models(): List<LocalModel> = CATALOG + customModels()

    fun find(id: String): LocalModel? = models().firstOrNull { it.id == id }

    private fun customModels(): List<LocalModel> = runCatching {
        val list = org.json.JSONArray(customFile.readText())
        (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            LocalModel(
                id = item.getString("id"),
                title = item.getString("title"),
                url = item.getString("url"),
                sha256 = item.optString("sha256"),
                sizeBytes = item.getLong("size"),
                minRamGb = item.optInt("minRamGb", 4),
                custom = true,
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun addCustom(model: LocalModel) {
        val others = customModels().filterNot { it.id == model.id }
        val list = org.json.JSONArray()
        (others + model).forEach {
            list.put(
                org.json.JSONObject()
                    .put("id", it.id).put("title", it.title).put("url", it.url)
                    .put("sha256", it.sha256).put("size", it.sizeBytes).put("minRamGb", it.minRamGb),
            )
        }
        customFile.writeText(list.toString())
    }

    @Synchronized
    private fun removeCustom(id: String) {
        val list = org.json.JSONArray()
        customModels().filterNot { it.id == id }.forEach {
            list.put(
                org.json.JSONObject()
                    .put("id", it.id).put("title", it.title).put("url", it.url)
                    .put("sha256", it.sha256).put("size", it.sizeBytes).put("minRamGb", it.minRamGb),
            )
        }
        customFile.writeText(list.toString())
    }

    fun statuses(): List<LocalModelStatus> = models().map { LocalModelStatus(it, isDownloaded(it)) }

    /** RAM, fast cores, ARM features and free space, for "Scan this phone". */
    fun deviceProfile(): com.jarves.mh.network.DeviceProfile {
        val features = runCatching {
            com.jarves.mh.network.ModelRecommender.cpuFeatures(File("/proc/cpuinfo").readText())
        }.getOrDefault(emptySet())
        return com.jarves.mh.network.DeviceProfile(
            ramGb = deviceRamGb(),
            fastCores = performanceCores(coreMaxFrequencies()),
            dotProd = "asimddp" in features,
            int8Matmul = "i8mm" in features,
            freeStorageBytes = modelsDir.usableSpace,
        )
    }

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
        if (model.sha256.isNotBlank() && sha256(partial) != model.sha256) {
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
            if (URL(model.url).host == "huggingface.co") {
                huggingFaceToken()?.takeIf(String::isNotBlank)?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
        }
        try {
            val code = connection.responseCode
            check(code != 401 && code != 403) {
                "${model.title} is gated: accept its licence on huggingface.co, then add your Hugging Face token in the search screen."
            }
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
        if (model.custom) removeCustom(model.id)
    }

    private val settings = app.getSharedPreferences("local_model", Context.MODE_PRIVATE)

    /** Cores to run on: 0 picks the fast cores automatically; a change applies on the next load. */
    var threadsSetting: Int
        get() = settings.getInt("threads", 0)
        set(value) { settings.edit().putInt("threads", value.coerceAtLeast(0)).apply() }

    fun totalCores(): Int = Runtime.getRuntime().availableProcessors()

    fun autoThreads(): Int = performanceCores(coreMaxFrequencies())

    @Volatile private var process: Process? = null
    /** Threads the running server uses; shown to the user when it is ready. */
    @Volatile var threads: Int = 0
        private set
    @Volatile private var gateway: LocalModelGateway? = null
    @Volatile private var runningModelId: String? = null

    /** Starts the server for [modelId] unless it already serves it; waits until it answers. */
    suspend fun ensureRunning(modelId: String): Unit = withContext(Dispatchers.IO) {
        val model = find(modelId)
            ?: error("Pick an on-device model in Agent → AI provider.")
        check(isDownloaded(model)) { "${model.title} is not downloaded yet. Download it in Agent → AI provider." }
        val wanted = threadsSetting.takeIf { it > 0 }?.coerceAtMost(totalCores()) ?: performanceCores(coreMaxFrequencies())
        if (runningModelId == model.id && threads == wanted && process?.isAlive == true && isHealthy()) return@withContext
        stop()
        killOrphanedServers()
        val libraryDir = app.applicationInfo.nativeLibraryDir
        val threads = wanted
        this@LocalModelServer.threads = threads
        val started = ProcessBuilder(
            serverCommand(
                File(libraryDir, "libllamaserver.so").absolutePath,
                file(model).absolutePath,
                model.id,
                contextTokens(deviceRamGb()),
                threads,
            ),
        )
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .apply { environment()["LD_LIBRARY_PATH"] = libraryDir }
            .start()
        process = started
        runningModelId = model.id
        val startedGateway = LocalModelGateway(
            PORT,
            "http://$HOST:$SERVER_PORT",
            warmupFile,
            onProgress = { processed, total -> _progress.value = LocalModelProgress(processed, total) },
            onBusy = { busy ->
                // A lock that already timed out throws on release; that is harmless here.
                if (busy) wakeLock.acquire(WAKE_LOCK_MAX_MS) else runCatching { if (wakeLock.isHeld) wakeLock.release() }
            },
        ).start()
        gateway = startedGateway
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) {
                runningModelId = null
                error("The on-device model stopped while loading: ${logTail()}")
            }
            if (isHealthy()) {
                LocalModelService.start(app, model.title)
                // Read the agent's saved instructions now, so the first request finds them cached.
                Thread({ startedGateway.warmUp() }, "mh-local-model-warmup").apply { isDaemon = true; start() }
                return@withContext
            }
            delay(500)
        }
        stop()
        error("The on-device model took too long to load. Try a smaller model.")
    }

    fun stop() {
        LocalModelService.stop(app)
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

    /** A server left behind when Android killed the app still holds the port; end it first. */
    private fun killOrphanedServers() {
        val own = android.os.Process.myPid()
        File("/proc").listFiles { file -> file.name.all(Char::isDigit) }.orEmpty().forEach { dir ->
            val pid = dir.name.toInt()
            if (pid == own) return@forEach
            val command = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if ("libllamaserver.so" in command) runCatching { android.os.Process.killProcess(pid) }
        }
    }

    private fun coreMaxFrequencies(): List<Long> =
        (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }

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
        /**
         * Coding agents send about 8k tokens of instructions before any files, so less overflows
         * quickly. Phones with room for it get double, which lasts longer before the agent compacts.
         */
        internal fun contextTokens(ramGb: Int): Int = if (ramGb >= 10) 32_768 else 16_384
        private const val START_TIMEOUT_MS = 180_000L
        private const val DOWNLOAD_ATTEMPTS = 5

        /** --jinja applies the model's own chat template, which is what makes tool calls work. */
        /**
         * Phone CPUs mix fast and slow cores. llama.cpp starts one thread per core and every step
         * waits for the slowest, so only the cores within 70% of the fastest clock are used.
         */
        internal fun performanceCores(maxFrequencies: List<Long>): Int {
            val fastest = maxFrequencies.maxOrNull() ?: return FALLBACK_THREADS
            return maxFrequencies.count { it * 10 >= fastest * 7 }.coerceIn(1, MAX_THREADS)
        }

        private const val FALLBACK_THREADS = 4
        private const val WAKE_LOCK_MAX_MS = 30 * 60_000L

        /** The server the notification's Stop button ends. */
        @Volatile var active: LocalModelServer? = null
            private set
        private const val MAX_THREADS = 8

        internal fun serverCommand(
            executable: String,
            modelPath: String,
            alias: String,
            contextTokens: Int,
            threads: Int,
        ): List<String> = listOf(
            executable,
            "-m", modelPath,
            "--alias", alias,
            "--host", HOST,
            "--port", SERVER_PORT.toString(),
            "--jinja",
            "-c", contextTokens.toString(),
            "-t", threads.toString(),
            "-tb", threads.toString(),
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
