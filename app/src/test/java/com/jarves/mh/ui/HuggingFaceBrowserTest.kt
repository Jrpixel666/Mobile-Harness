package com.jarves.mh.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jarves.mh.network.DeviceProfile
import com.jarves.mh.network.HfModelFile
import com.jarves.mh.network.HfRepo
import com.jarves.mh.network.HfSection
import com.jarves.mh.network.ModelRecommendation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuggingFaceBrowserTest {
    @get:Rule val compose = createComposeRule()

    private val repo = HfRepo("Qwen/Qwen2.5-Coder-3B-Instruct-GGUF", 91_791, 40, 3_397_103_616L, supportsTools = true, architecture = "qwen2")
    private val file = HfModelFile(repo.id, "qwen2.5-coder-3b-instruct-q4_0.gguf", 2_000_000_000L, "sha")
    private val device = DeviceProfile(12, 4, dotProd = true, int8Matmul = true, freeStorageBytes = 85_000_000_000L)

    /** The browser sits inside the agent screen's LazyColumn, exactly as on the phone. */
    @Test
    fun rendersInsideTheAgentScreenListAndNavigates() {
        var browser by mutableStateOf(HuggingFaceBrowserState(open = true, device = device, repos = listOf(repo)))
        var opened: HfRepo? = null
        var downloaded: HfModelFile? = null
        val actions = HuggingFaceActions(
            section = {}, query = {}, search = {}, onlyFits = {}, onlyTools = {},
            openRepo = { opened = it; browser = browser.copy(openRepo = it, files = listOf(file)) },
            back = { browser = browser.copy(openRepo = null, recommendations = null) },
            scan = { browser = browser.copy(recommendations = listOf(ModelRecommendation(HfSection.CODING, repo, file))) },
            download = { downloaded = it },
            saveToken = {},
        )
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    item {
                        HuggingFaceBrowser(browser, ramGb = 12, downloading = false, supportedArchitectures = setOf("qwen2"), actions = actions)
                    }
                }
            }
        }
        compose.onNodeWithText(repo.id).performClick()
        compose.waitForIdle()
        assertEquals(repo, opened)
        compose.onNodeWithText("Download").performClick()
        assertEquals(file, downloaded)
        compose.onNodeWithText("Back to results", useUnmergedTree = true).assertDoesNotExist()
        browser = browser.copy(openRepo = null)
        compose.waitForIdle()
        compose.onNodeWithText("Scan this phone and find models that work").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Recommended for this phone").assertExists()
    }
}
