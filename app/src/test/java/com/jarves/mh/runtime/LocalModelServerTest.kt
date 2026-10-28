package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelServerTest {
    @Test
    fun serverListensOnlyOnThePhoneWithToolCalls() {
        val command = LocalModelServer.serverCommand("/lib/libllamaserver.so", "/models/a.gguf", "a")
        assertEquals("/lib/libllamaserver.so", command.first())
        assertEquals("127.0.0.1", command[command.indexOf("--host") + 1])
        assertEquals(LocalModelServer.SERVER_PORT.toString(), command[command.indexOf("--port") + 1])
        assertTrue("--jinja" in command)
        assertEquals("/models/a.gguf", command[command.indexOf("-m") + 1])
    }

    @Test
    fun catalogEntriesAreVerifiable() {
        val ids = LocalModelServer.CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        LocalModelServer.CATALOG.forEach { model ->
            assertTrue(model.url.startsWith("https://huggingface.co/"))
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.sizeBytes > 100_000_000L)
        }
        assertTrue(ProviderKind.LOCAL.defaultModel in ids)
    }

    @Test
    fun deepSeekHarnessReachesTheLocalServerAsOpenAiChat() {
        val profile = ProviderProfile(ProviderKind.LOCAL)
        assertEquals(LocalModelServer.BASE_URL, profile.resolvedBaseUrl)
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        val route = DshRouteMapper.forProfile(profile)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals(LocalModelServer.BASE_URL, route.custom?.baseUrl)
        assertEquals("no-key-required", localServerPlaceholderKey(profile))
        assertTrue(ProviderKind.LOCAL in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
    }
}
