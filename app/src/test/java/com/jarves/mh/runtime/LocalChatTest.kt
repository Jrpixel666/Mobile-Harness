package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class LocalChatTest {
    @Test
    fun requestHasNoToolsAndKeepsTheConversation() {
        val request = LocalChat.request(
            listOf(
                LocalChatMessage(true, "What is Kotlin?"),
                LocalChatMessage(false, "A language."),
                LocalChatMessage(false, ""),
                LocalChatMessage(true, "Who made it?"),
            ),
        )
        assertFalse(request.has("tools"))
        val messages = request.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Who made it?", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun parsesContentAndReasoningDeltas() {
        assertEquals("Hi" to "", LocalChat.parseDelta("""data: {"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertEquals("" to "hmm", LocalChat.parseDelta("""data: {"choices":[{"delta":{"content":null,"reasoning_content":"hmm"}}]}"""))
        assertNull(LocalChat.parseDelta("""data: {"choices":[{"delta":{"role":"assistant","content":null}}]}"""))
        assertNull(LocalChat.parseDelta("data: [DONE]"))
        assertNull(LocalChat.parseDelta(": keep-alive"))
    }

    @Test
    fun readsStreamUntilDoneOrCancelled() {
        val stream = """
            data: {"choices":[{"delta":{"content":"Hello"}}]}

            data: {"choices":[{"delta":{"content":" there"}}]}

            data: [DONE]
        """.trimIndent()
        val text = StringBuilder()
        LocalChat.readStream(BufferedReader(StringReader(stream)), { false }) { content, _ -> text.append(content) }
        assertEquals("Hello there", text.toString())

        var pieces = 0
        LocalChat.readStream(BufferedReader(StringReader(stream)), { pieces >= 1 }) { _, _ -> pieces++ }
        assertEquals(1, pieces)
    }
}
