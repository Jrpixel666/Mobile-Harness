package com.jarves.mh.network

/** What the phone offers for running a model, as found by "Scan this phone". */
data class DeviceProfile(
    val ramGb: Int,
    val fastCores: Int,
    /** CPU has the ARM dot-product instructions llama.cpp's fast Q4_0 path uses. */
    val dotProd: Boolean,
    /** CPU has ARM int8 matrix multiply, which speeds up Q4_0 further. */
    val int8Matmul: Boolean,
    val freeStorageBytes: Long,
)

/** One model the scan suggests, with the file to download. */
data class ModelRecommendation(val section: HfSection, val repo: HfRepo, val file: HfModelFile)

object ModelRecommender {
    /** Sections the scan looks through, in the order they are shown. */
    val SCAN_SECTIONS = listOf(HfSection.CODING, HfSection.SMALL, HfSection.UNCENSORED, HfSection.GENERAL)
    const val REPOS_PER_SECTION = 4
    /** Keep room on the phone after a download. */
    private const val STORAGE_RESERVE_BYTES = 2_000_000_000L

    /** CPU feature flags from /proc/cpuinfo's "Features" lines. */
    fun cpuFeatures(cpuinfo: String): Set<String> =
        cpuinfo.lineSequence()
            .filter { it.trimStart().startsWith("Features", ignoreCase = true) }
            .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }
            .filter(String::isNotBlank)
            .toSet()

    /**
     * Architectures llama.cpp loads that are not chat models on their own: vision encoders,
     * embedding models, and draft models that only speed up a bigger one.
     */
    private val HELPER_ARCHITECTURES = setOf(
        "clip", "bert", "modern-bert", "nomic-bert", "nomic-bert-moe", "neo-bert", "jina-bert-v2", "jina-bert-v3",
        "eurobert", "gemma-embedding", "gemma-embedding2", "t5encoder", "wavtokenizer-dec", "eagle3", "dflash", "llama-embed",
    )

    private val NAMED_SIZE = Regex("(?i)(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)B(?![A-Za-z])")

    /** Whether this app's llama.cpp can run the repository as a chat model, when its architecture is known. */
    fun runnable(repo: HfRepo, supportedArchitectures: Set<String>): Boolean {
        val architecture = repo.architecture ?: return true
        return architecture !in HELPER_ARCHITECTURES && (supportedArchitectures.isEmpty() || architecture in supportedArchitectures)
    }

    /**
     * Hugging Face reads the metadata of one file per repository; for some repositories that
     * is a small helper file, so a "27B" model reports 1.9B. Trust the name when they disagree.
     */
    fun sizeLooksRight(repo: HfRepo): Boolean {
        val parameters = repo.parameters ?: return false
        val named = NAMED_SIZE.find(repo.id.substringAfter('/'))?.groupValues?.get(1)?.toDoubleOrNull() ?: return true
        return named * 1e9 <= parameters * 2.5
    }

    /** Repositories worth opening: they can use tools, will load, and are sized for this phone. */
    fun candidates(repos: List<HfRepo>, device: DeviceProfile, supportedArchitectures: Set<String> = emptySet()): List<HfRepo> =
        repos.filter { repo ->
            repo.supportsTools && !repo.gated && repo.parameters != null &&
                runnable(repo, supportedArchitectures) && sizeLooksRight(repo) &&
                HuggingFaceModels.fit(HuggingFaceModels.estimatedQ4Bytes(repo.parameters), device.ramGb) == ModelFit.GOOD
        }.take(REPOS_PER_SECTION)

    /**
     * Best file for this phone: it must fit well and leave storage free. Q4_0 is preferred when
     * the CPU has dot-product support (llama.cpp repacks it for ARM), then Q4_K_M, then the
     * largest other 4-bit file, then anything that fits.
     */
    fun bestFile(files: List<HfModelFile>, device: DeviceProfile): HfModelFile? {
        val usable = files.filter {
            HuggingFaceModels.fit(it.sizeBytes, device.ramGb) == ModelFit.GOOD &&
                it.sizeBytes + STORAGE_RESERVE_BYTES <= device.freeStorageBytes
        }
        val preferred = if (device.dotProd) listOf("Q4_0", "Q4_K_M") else listOf("Q4_K_M", "Q4_0")
        preferred.forEach { quant -> usable.firstOrNull { it.quant == quant }?.let { return it } }
        return usable.filter { it.quant.contains("4") }.maxByOrNull { it.sizeBytes }
            ?: usable.maxByOrNull { it.sizeBytes }
    }
}
