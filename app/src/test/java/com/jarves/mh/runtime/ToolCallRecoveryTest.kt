package com.jarves.mh.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallRecoveryTest {
    private val tools = setOf("read_file", "write_file", "bash")

    private fun request() = JSONObject()
        .put("messages", JSONArray())
        .put(
            "tools",
            JSONArray(tools.map { JSONObject().put("type", "function").put("function", JSONObject().put("name", it)) }),
        )

    private fun completion(content: String) = JSONObject()
        .put("id", "chatcmpl-1")
        .put("model", "x")
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", content)),
            ),
        )

    // Replies captured from Qwen2.5 Coder 1.5B and 3B running in llama-server b11600.
    @Test
    fun recoversFencedJson() {
        val reply = "```json\n{\n  \"name\": \"read_file\",\n  \"arguments\": {\n    \"path\": \"app/src/main/MainActivity.kt\"\n  }\n}\n```"
        val result = ToolCallRecovery.recover(reply, tools)!!
        val function = result.calls.getJSONObject(0).getJSONObject("function")
        assertEquals("read_file", function.getString("name"))
        assertEquals("app/src/main/MainActivity.kt", JSONObject(function.getString("arguments")).getString("path"))
        assertEquals("", result.remainingText)
    }

    @Test
    fun recoversBareJsonAndToolCallTags() {
        val bare = ToolCallRecovery.recover("""{"name": "read_file", "arguments": {"path": "/workspace/settings.gradle.kts"}}""", tools)!!
        assertEquals(1, bare.calls.length())
        val tagged = ToolCallRecovery.recover(
            "I'll write it.\n<tool_call>\n{\"name\": \"write_file\", \"arguments\": {\"path\": \"a\", \"content\": \"b\"}}\n</tool_call>",
            tools,
        )!!
        assertEquals("write_file", tagged.calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("I'll write it.", tagged.remainingText)
    }

    @Test
    fun recoversACallAfterProseInDoubleBraces() {
        // Streamed through LocalModelGateway from the 1.5B model.
        val reply = "Here's the file `notes.txt` created:\n\n```\nhello\n```\n\n{{\"name\": \"write_file\", \"arguments\": {\"path\": \"notes.txt\", \"content\": \"hello\"}}}"
        val result = ToolCallRecovery.recover(reply, tools)!!
        val function = result.calls.getJSONObject(0).getJSONObject("function")
        assertEquals("write_file", function.getString("name"))
        assertEquals("hello", JSONObject(function.getString("arguments")).getString("content"))
        assertTrue('{' !in result.remainingText)
    }

    @Test
    fun leavesOrdinaryTextAndUnknownToolsAlone() {
        assertNull(ToolCallRecovery.recover("To show you the file, please provide it.", tools))
        assertNull(ToolCallRecovery.recover("```json\n{\"name\": \"delete_everything\", \"arguments\": {}}\n```", tools))
        assertNull(ToolCallRecovery.recover("```kotlin\nfun main() {}\n```", tools))
    }

    @Test
    fun repairsCompletionsAndKeepsRealToolCalls() {
        val repaired = ToolCallRecovery.repairCompletion(request(), completion("""{"name":"bash","arguments":{"command":"ls"}}"""))
        val choice = repaired.getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", choice.getString("finish_reason"))
        assertTrue(choice.getJSONObject("message").isNull("content"))

        val untouched = completion("Done.")
        ToolCallRecovery.repairCompletion(request(), untouched)
        assertEquals("stop", untouched.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test
    fun streamsToolCallsInOpenAiChunks() {
        val repaired = ToolCallRecovery.repairCompletion(request(), completion("""{"name":"bash","arguments":{"command":"ls"}}"""))
        val chunks = ToolCallRecovery.streamChunks(repaired.put("usage", JSONObject().put("total_tokens", 5)), includeUsage = true)
        assertEquals("data: [DONE]\n\n", chunks.last())
        val bodies = chunks.dropLast(1).map { JSONObject(it.removePrefix("data: ").trim()) }
        val toolDelta = bodies[1].getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals(0, toolDelta.getInt("index"))
        assertEquals("bash", toolDelta.getJSONObject("function").getString("name"))
        assertEquals("tool_calls", bodies[2].getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals(5, bodies[3].getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun assemblesStreamedRepliesAndReportsPromptProgress() {
        val progress = mutableListOf<Pair<Int, Int>>()
        val assembler = ChatStreamAssembler { processed, total -> progress += processed to total }
        val lines = listOf(
            """data: {"id":"c1","model":"x","created":5,"choices":[],"prompt_progress":{"total":8000,"cache":6000,"processed":6000,"time_ms":1}}""",
            """data: {"id":"c1","model":"x","created":5,"choices":[],"prompt_progress":{"total":8000,"cache":6000,"processed":8000,"time_ms":900}}""",
            """data: {"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":null},"finish_reason":null}]}""",
            """data: {"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"t1","type":"function","function":{"name":"bash","arguments":"{\"comm"}}]},"finish_reason":null}]}""",
            """data: {"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"and\":\"ls\"}"}}]},"finish_reason":null}]}""",
            """data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            """data: {"id":"c1","choices":[],"usage":{"total_tokens":8010}}""",
            "data: [DONE]",
        )
        val stillOpen = lines.map(assembler::line)
        assertEquals(false, stillOpen.last())
        assertEquals(listOf(6000 to 8000, 8000 to 8000), progress)
        val completion = assembler.completion()
        val message = completion.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val call = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("t1", call.getString("id"))
        assertEquals("ls", JSONObject(call.getJSONObject("function").getString("arguments")).getString("command"))
        assertTrue(message.isNull("content"))
        assertEquals(8010, completion.getJSONObject("usage").getInt("total_tokens"))
    }
}
