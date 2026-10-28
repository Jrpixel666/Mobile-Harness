package com.jarves.mh.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {
    private val pixel9 = DeviceProfile(ramGb = 12, fastCores = 4, dotProd = true, int8Matmul = true, freeStorageBytes = 100_000_000_000L)
    private val tablet = DeviceProfile(ramGb = 4, fastCores = 2, dotProd = true, int8Matmul = false, freeStorageBytes = 20_000_000_000L)

    private fun file(quant: String, size: Long) = HfModelFile("r/m", "m-$quant.gguf", size, "sha")

    @Test
    fun readsArmFeatures() {
        val features = ModelRecommender.cpuFeatures("processor : 0\nFeatures\t: fp asimd asimddp i8mm sve2\n\nprocessor : 1\nFeatures : fp asimd")
        assertTrue("asimddp" in features && "i8mm" in features && "sve2" in features)
    }

    @Test
    fun prefersQ4_0OnPhonesWithDotProduct() {
        val files = listOf(file("Q2_K", 1_200_000_000L), file("Q4_0", 1_800_000_000L), file("Q4_K_M", 1_900_000_000L), file("Q8_0", 3_300_000_000L))
        assertEquals("Q4_0", ModelRecommender.bestFile(files, pixel9)?.quant)
        assertEquals("Q4_K_M", ModelRecommender.bestFile(files, pixel9.copy(dotProd = false))?.quant)
    }

    @Test
    fun skipsFilesThatDoNotFitWellOrHaveNoRoom() {
        val files = listOf(file("Q4_0", 1_800_000_000L), file("Q4_K_M", 1_900_000_000L))
        // 4 GB: a 1.8 GB file is only "tight", so nothing is recommended.
        assertNull(ModelRecommender.bestFile(files, tablet))
        assertNull(ModelRecommender.bestFile(files, pixel9.copy(freeStorageBytes = 3_000_000_000L)))
        assertEquals("Q4_0", ModelRecommender.bestFile(listOf(file("Q4_0", 600_000_000L)), tablet)?.quant)
    }

    @Test
    fun opensOnlyToolCapableUngatedReposThatFit() {
        val repos = listOf(
            HfRepo("a/7b", 10, 0, 7_600_000_000L, supportsTools = true),
            HfRepo("b/no-tools", 9, 0, 3_000_000_000L, supportsTools = false),
            HfRepo("c/gated", 8, 0, 3_000_000_000L, supportsTools = true, gated = true),
            HfRepo("d/30b", 7, 0, 30_000_000_000L, supportsTools = true),
            HfRepo("e/unknown", 6, 0, null, supportsTools = true),
        )
        assertEquals(listOf("a/7b"), ModelRecommender.candidates(repos, pixel9).map { it.id })
    }

    @Test
    fun skipsHelperModelsUnsupportedArchitecturesAndMislabelledSizes() {
        val supported = setOf("qwen2", "qwen35", "gemma4", "dflash")
        // Real cases from the scan: a 27B repo whose metadata came from its 1.9B helper file,
        // a DFlash draft model, and the Gemma 4 "E4B" naming that is not a size.
        assertTrue(!ModelRecommender.sizeLooksRight(HfRepo("HauhauCS/Qwen3.8-27B-Uncensored-MTP-GGUF", 1, 0, 1_863_907_840L, true, architecture = "qwen35")))
        assertTrue(ModelRecommender.sizeLooksRight(HfRepo("Qwen/Qwen2.5-Coder-7B-Instruct-GGUF", 1, 0, 7_615_616_512L, true)))
        assertTrue(ModelRecommender.sizeLooksRight(HfRepo("HauhauCS/Gemma-4-E4B-Uncensored", 1, 0, 7_518_069_290L, true)))
        assertTrue(!ModelRecommender.runnable(HfRepo("x/Next-DFlash", 1, 0, 1_342_549_325L, true, architecture = "dflash"), supported))
        assertTrue(!ModelRecommender.runnable(HfRepo("x/future", 1, 0, 1_000_000_000L, true, architecture = "qwen9"), supported))
        assertTrue(ModelRecommender.runnable(HfRepo("x/known", 1, 0, 1_000_000_000L, true, architecture = "gemma4"), supported))
    }
}
