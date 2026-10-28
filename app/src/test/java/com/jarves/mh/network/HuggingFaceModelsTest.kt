package com.jarves.mh.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceModelsTest {
    @Test
    fun readsSearchResultsWithGgufMetadata() {
        val repos = HuggingFaceModels.parseSearch(
            """[
              {"id":"Qwen/Qwen2.5-Coder-3B-Instruct-GGUF","downloads":91791,"likes":40,
               "gguf":{"total":3397103616,"architecture":"qwen2","chat_template":"{%- if tools %}..."}},
              {"id":"someone/plain-GGUF","downloads":5,"likes":0}
            ]""",
        )
        assertEquals(2, repos.size)
        assertEquals(3_397_103_616L, repos[0].parameters)
        assertTrue(repos[0].supportsTools)
        assertNull(repos[1].parameters)
        assertFalse(repos[1].supportsTools)
    }

    @Test
    fun listsSingleFileModelsSmallestFirst() {
        val files = HuggingFaceModels.parseFiles(
            "bartowski/Qwen2.5-Coder-3B-Instruct-GGUF",
            """[
              {"type":"file","path":".gitattributes","size":3359},
              {"type":"file","path":"Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf","size":135,"lfs":{"oid":"abc","size":1929903360}},
              {"type":"file","path":"Qwen2.5-Coder-3B-Instruct-IQ2_M.gguf","size":135,"lfs":{"oid":"def","size":1140516096}},
              {"type":"file","path":"big/model-Q8_0-00001-of-00002.gguf","size":135,"lfs":{"oid":"x","size":5}},
              {"type":"file","path":"mmproj-model-f16.gguf","size":135,"lfs":{"oid":"y","size":5}},
              {"type":"file","path":"Qwen2.5-Coder-3B-Instruct-Q4_0_4_8.gguf","size":135,"lfs":{"oid":"z","size":1822850304}},
              {"type":"directory","path":"big"}
            ]""",
        )
        assertEquals(listOf("IQ2_M", "Q4_K_M"), files.map { it.quant })
        assertEquals(1_929_903_360L, files[1].sizeBytes)
        assertEquals("abc", files[1].sha256)
        assertEquals(
            "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
            files[1].url,
        )
    }

    @Test
    fun ratesModelsAgainstPhoneMemory() {
        // Pixel 9: 12 GB. Qwen2.5 Coder 7B Q4_K_M is 4.68 GB.
        assertEquals(ModelFit.GOOD, HuggingFaceModels.fit(4_683_073_536L, 12))
        assertEquals(ModelFit.TOO_BIG, HuggingFaceModels.fit(15_000_000_000L, 12))
        // A 4 GB tablet: the 1.5B model (1.1 GB) is tight, the 3B too big.
        assertEquals(ModelFit.TIGHT, HuggingFaceModels.fit(1_117_320_768L, 4))
        assertEquals(ModelFit.TOO_BIG, HuggingFaceModels.fit(2_104_932_800L, 4))
        assertEquals(3_000_000_000L, HuggingFaceModels.estimatedQ4Bytes(5_000_000_000L))
    }

    @Test
    fun makesSafeLocalIds() {
        val file = HfModelFile("bartowski/Qwen2.5-Coder-3B-Instruct-GGUF", "sub dir/Model Q4_0.gguf", 1, "")
        assertEquals("hf-bartowski-qwen2.5-coder-3b-instruct-gguf-model-q4_0", HuggingFaceModels.localId(file))
        assertTrue(file.url.endsWith("/resolve/main/sub%20dir/Model%20Q4_0.gguf"))
        assertEquals("Q4_0", file.quant)
    }
}
