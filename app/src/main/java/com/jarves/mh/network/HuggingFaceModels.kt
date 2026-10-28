package com.jarves.mh.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A GGUF repository found on Hugging Face. */
data class HfRepo(
    val id: String,
    val downloads: Long,
    val likes: Long,
    /** Parameter count from the GGUF metadata, when Hugging Face has read it. */
    val parameters: Long?,
    /** The chat template mentions tools, so the agent's tool calls can be formatted for it. */
    val supportsTools: Boolean,
    /** Downloads need the licence accepted on huggingface.co and a token. */
    val gated: Boolean = false,
)

/** Ready-made searches; each runs its queries and merges the results. */
enum class HfSection(val queries: List<String>, val maxParameters: Long? = null) {
    CODING(listOf("coder instruct", "coder")),
    UNCENSORED(listOf("abliterated", "uncensored", "heretic")),
    SMALL(listOf("instruct"), maxParameters = 4_000_000_000L),
    REASONING(listOf("reasoning", "thinking", "r1 distill")),
    GENERAL(listOf("instruct", "chat")),
}

/** One downloadable .gguf file in a repository. */
data class HfModelFile(
    val repo: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val quant: String get() = HuggingFaceModels.quantOf(path)
    val url: String get() = HuggingFaceModels.fileUrl(repo, path)
}

/** How a model is likely to run on this phone, from its size and the phone's RAM. */
enum class ModelFit { GOOD, TIGHT, TOO_BIG }

object HuggingFaceModels {
    private const val API = "https://huggingface.co/api/models"
    private const val GB = 1_000_000_000.0
    /** Context cache, the server and the agent need room beyond the model file itself. */
    private const val OVERHEAD_BYTES = 1_500_000_000L
    /** Q4_K_M stores about 4.8 bits per parameter. */
    private const val Q4_BYTES_PER_PARAMETER = 0.6

    private val QUANT = Regex("(?i)(IQ\\d_[A-Z0-9]+|Q\\d_K_[SML]|Q\\d_K|Q\\d_\\d|BF16|F16|F32)")
    private val SPLIT = Regex("(?i)-\\d{5}-of-\\d{5}\\.gguf$")
    /** Old pre-repacked ARM formats; current llama.cpp repacks plain Q4_0 itself and rejects these. */
    private val LEGACY_ARM = Regex("(?i)Q4_0_\\d_\\d")

    fun searchUrl(query: String, maxParameters: Long? = null, limit: Int = 40): String =
        "$API?search=${URLEncoder.encode(query.trim(), "UTF-8")}&filter=gguf&sort=downloads&direction=-1&limit=$limit" +
            // The API takes whole billions here; "M" values are not filtered correctly.
            (maxParameters?.let { "&num_parameters=min:0,max:${(it / 1_000_000_000L).coerceAtLeast(1)}B" } ?: "") +
            "&expand%5B%5D=gguf&expand%5B%5D=downloads&expand%5B%5D=likes&expand%5B%5D=gated"

    fun filesUrl(repo: String): String = "$API/$repo/tree/main?recursive=true"

    fun fileUrl(repo: String, path: String): String =
        "https://huggingface.co/$repo/resolve/main/" + path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    fun parseSearch(json: String): List<HfRepo> {
        val list = JSONArray(json)
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val gguf = item.optJSONObject("gguf")
            HfRepo(
                id = id,
                downloads = item.optLong("downloads"),
                likes = item.optLong("likes"),
                parameters = gguf?.optLong("total")?.takeIf { it > 0 },
                supportsTools = gguf?.optString("chat_template").orEmpty().contains("tools"),
                // "gated" is false, or the approval mode ("auto" / "manual").
                gated = when (val gated = item.opt("gated")) {
                    is Boolean -> gated
                    is String -> gated.isNotBlank() && gated != "false"
                    else -> false
                },
            )
        }
    }

    /** Single-file GGUF models only: split parts and vision projectors cannot run on their own here. */
    fun parseFiles(repo: String, json: String): List<HfModelFile> {
        val list = JSONArray(json)
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val path = item.optString("path")
            val name = path.substringAfterLast('/')
            if (item.optString("type") != "file" || !name.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            if (SPLIT.containsMatchIn(name) || LEGACY_ARM.containsMatchIn(name) || name.contains("mmproj", ignoreCase = true)) {
                return@mapNotNull null
            }
            val lfs = item.optJSONObject("lfs")
            HfModelFile(
                repo = repo,
                path = path,
                sizeBytes = lfs?.optLong("size")?.takeIf { it > 0 } ?: item.optLong("size"),
                sha256 = lfs?.optString("oid").orEmpty(),
            )
        }.sortedBy { it.sizeBytes }
    }

    fun quantOf(fileName: String): String =
        QUANT.findAll(fileName.substringAfterLast('/')).lastOrNull()?.value?.uppercase() ?: "GGUF"

    fun estimatedQ4Bytes(parameters: Long): Long = (parameters * Q4_BYTES_PER_PARAMETER).toLong()

    /** Android needs a good share of RAM for itself and the agent; leave it at least 40%. */
    fun fit(sizeBytes: Long, ramGb: Int): ModelFit {
        if (ramGb <= 0) return ModelFit.TIGHT
        val needed = (sizeBytes + OVERHEAD_BYTES) / GB
        return when {
            needed <= ramGb * 0.6 -> ModelFit.GOOD
            needed <= ramGb * 0.8 -> ModelFit.TIGHT
            else -> ModelFit.TOO_BIG
        }
    }

    /** Largest model, in parameters, that still rates Tight or better at Q4 on this phone. */
    fun maxParametersFor(ramGb: Int): Long? {
        if (ramGb <= 0) return null
        val bytes = ramGb * 0.8 * GB - OVERHEAD_BYTES
        return (bytes / Q4_BYTES_PER_PARAMETER).toLong().coerceAtLeast(500_000_000L)
    }

    /** The RAM a file needs to be in the GOOD band, for the model list's "needs N GB" line. */
    fun minRamGb(sizeBytes: Long): Int = kotlin.math.ceil((sizeBytes + OVERHEAD_BYTES) / GB / 0.6).toInt()

    fun search(query: String, maxParameters: Long? = null, token: String? = null): List<HfRepo> =
        parseSearch(get(searchUrl(query, maxParameters), token))

    /** Runs a section's queries and merges them, most downloaded first. */
    fun search(section: HfSection, phoneMaxParameters: Long?, token: String? = null): List<HfRepo> {
        val cap = listOfNotNull(section.maxParameters, phoneMaxParameters).minOrNull()
        return section.queries
            .flatMap { query -> runCatching { search(query, cap, token) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }
            .sortedByDescending { it.downloads }
    }

    fun files(repo: String, token: String? = null): List<HfModelFile> = parseFiles(repo, get(filesUrl(repo), token))

    private fun get(url: String, token: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            token?.takeIf(String::isNotBlank)?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            val code = connection.responseCode
            check(code in 200..299) { "Hugging Face returned HTTP $code." }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Stable local id for a downloaded file, used as its file name and model alias. */
    fun localId(file: HfModelFile): String =
        ("hf-" + file.repo + "-" + file.path.substringAfterLast('/').removeSuffix(".gguf"))
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .take(120)

    fun toJson(file: HfModelFile, title: String): JSONObject = JSONObject()
        .put("repo", file.repo).put("path", file.path).put("size", file.sizeBytes).put("sha256", file.sha256).put("title", title)
}
