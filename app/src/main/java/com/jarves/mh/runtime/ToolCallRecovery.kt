package com.jarves.mh.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Small local models often answer a tool request with the call written as JSON text — in a
 * ```json fence, bare, or in <tool_call> tags — instead of the structured form llama-server
 * parses. This turns such text back into OpenAI tool_calls so the agent can act on it.
 */
internal object ToolCallRecovery {
    data class Result(val calls: JSONArray, val remainingText: String)

    private val FENCE = Regex("```(?:json|JSON)?\\s*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val EMPTY_BRACES = Regex("\\{\\s*\\}")
    private val TAGGED = Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)

    /** Returns recovered calls, or null when the text holds no call to one of [toolNames]. */
    fun recover(content: String, toolNames: Set<String>, idPrefix: String = "call"): Result? {
        if (content.isBlank() || toolNames.isEmpty()) return null
        val calls = JSONArray()
        var remaining = content
        for (pattern in listOf(TAGGED, FENCE)) {
            pattern.findAll(remaining).toList().forEach { match ->
                val found = parseCalls(match.groupValues[1].trim(), toolNames)
                if (found.isNotEmpty()) {
                    found.forEach(calls::put)
                    remaining = remaining.replace(match.value, "")
                }
            }
        }
        if (calls.length() == 0) {
            // Bare JSON, possibly after prose or wrapped in extra braces.
            for (range in jsonObjectRanges(remaining)) {
                val found = parseCalls(remaining.substring(range), toolNames)
                if (found.isNotEmpty()) {
                    found.forEach(calls::put)
                    remaining = remaining.removeRange(range).replace(EMPTY_BRACES, "")
                    break
                }
            }
        }
        if (calls.length() == 0) return null
        for (index in 0 until calls.length()) {
            calls.getJSONObject(index).put("id", "${idPrefix}_$index")
        }
        return Result(calls, remaining.trim())
    }

    /** Balanced {...} spans in [text], outermost first, skipping braces inside strings. */
    private fun jsonObjectRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (start in text.indices) {
            if (text[start] != '{') continue
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until text.length) {
                val char = text[index]
                when {
                    escaped -> escaped = false
                    char == '\\' && inString -> escaped = true
                    char == '"' -> inString = !inString
                    inString -> Unit
                    char == '{' -> depth++
                    char == '}' -> if (--depth == 0) {
                        ranges += start..index
                        break
                    }
                }
            }
        }
        return ranges
    }

    private fun parseCalls(text: String, toolNames: Set<String>): List<JSONObject> {
        val value = runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: return emptyList()
        val items = when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull(value::optJSONObject)
            else -> emptyList()
        }
        return items.mapNotNull { item -> toCall(item, toolNames) }
    }

    private fun toCall(item: JSONObject, toolNames: Set<String>): JSONObject? {
        // Accept {"name","arguments"}, {"name","parameters"} and {"function":{...}} shapes.
        val source = item.optJSONObject("function") ?: item
        val name = source.optString("name").takeIf { it in toolNames } ?: return null
        val arguments = when (val raw = source.opt("arguments") ?: source.opt("parameters")) {
            is JSONObject -> raw.toString()
            is String -> raw.takeIf { runCatching { JSONObject(it) }.isSuccess } ?: return null
            null -> "{}"
            else -> return null
        }
        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments))
    }

    /** Names of the function tools offered in an OpenAI chat request. */
    fun toolNames(request: JSONObject): Set<String> {
        val tools = request.optJSONArray("tools") ?: return emptySet()
        return (0 until tools.length()).mapNotNull { index ->
            tools.optJSONObject(index)?.optJSONObject("function")?.optString("name")?.takeIf(String::isNotBlank)
        }.toSet()
    }

    /** Rewrites a non-streaming chat completion in place when its text holds a tool call. */
    fun repairCompletion(request: JSONObject, completion: JSONObject): JSONObject {
        val names = toolNames(request)
        val choice = completion.optJSONArray("choices")?.optJSONObject(0) ?: return completion
        val message = choice.optJSONObject("message") ?: return completion
        val existing = message.optJSONArray("tool_calls")
        if (existing != null && existing.length() > 0) return completion
        val recovered = recover(message.optString("content"), names, completion.optString("id", "call")) ?: return completion
        message.put("tool_calls", recovered.calls)
        message.put("content", recovered.remainingText.ifBlank { JSONObject.NULL })
        choice.put("finish_reason", "tool_calls")
        return completion
    }

    /** The completion as OpenAI streaming chunks, ending with [DONE]. */
    fun streamChunks(completion: JSONObject, includeUsage: Boolean): List<String> {
        val id = completion.optString("id", "chatcmpl-local")
        val model = completion.optString("model", "local")
        val created = completion.optLong("created", System.currentTimeMillis() / 1000)
        val choice = completion.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: JSONObject()
        fun chunk(delta: JSONObject, finish: String?) = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL),
                ),
            )
        val chunks = mutableListOf<JSONObject>()
        val first = JSONObject().put("role", "assistant")
        message.optString("reasoning_content").takeIf { it.isNotBlank() && !message.isNull("reasoning_content") }
            ?.let { first.put("reasoning_content", it) }
        val text = if (message.isNull("content")) "" else message.optString("content")
        if (text.isNotEmpty()) first.put("content", text)
        chunks += chunk(first, null)
        message.optJSONArray("tool_calls")?.let { calls ->
            for (index in 0 until calls.length()) {
                val call = JSONObject(calls.getJSONObject(index).toString()).put("index", index)
                chunks += chunk(JSONObject().put("tool_calls", JSONArray().put(call)), null)
            }
        }
        chunks += chunk(JSONObject(), choice.optString("finish_reason", "stop"))
        if (includeUsage) completion.optJSONObject("usage")?.let { usage ->
            chunks += JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", created)
                .put("model", model).put("choices", JSONArray()).put("usage", usage)
        }
        return chunks.map { "data: $it\n\n" } + "data: [DONE]\n\n"
    }
}
