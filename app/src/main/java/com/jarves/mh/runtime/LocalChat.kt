package com.jarves.mh.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** One message in the plain on-device chat. */
data class LocalChatMessage(val fromUser: Boolean, val text: String, val thinking: String = "")

/**
 * Plain question-and-answer chat with the on-device model. It talks to llama-server directly, with
 * no agent instructions and no tools, so a question gets an answer instead of starting a task.
 */
object LocalChat {
    const val SYSTEM_PROMPT = "You are a helpful assistant running offline on the user's phone. Answer clearly and concisely."

    /** OpenAI-style request for [history], which ends with the user's new message. */
    fun request(history: List<LocalChatMessage>, maxTokens: Int = 2048): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        history.filter { it.text.isNotBlank() }.forEach {
            messages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
        }
        return JSONObject().put("messages", messages).put("stream", true).put("max_tokens", maxTokens)
    }

    /** The answer text and reasoning text in one streamed `data:` line, or null for other lines. */
    fun parseDelta(line: String): Pair<String, String>? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val delta = runCatching {
            JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        }.getOrNull() ?: return null
        val content = delta.optString("content").takeUnless { delta.isNull("content") }.orEmpty()
        val reasoning = delta.optString("reasoning_content").takeUnless { delta.isNull("reasoning_content") }.orEmpty()
        return if (content.isEmpty() && reasoning.isEmpty()) null else content to reasoning
    }

    /** Reads a streamed reply, calling [onDelta] with each piece; stops early when [cancelled]. */
    fun readStream(reader: BufferedReader, cancelled: () -> Boolean, onDelta: (content: String, reasoning: String) -> Unit) {
        while (!cancelled()) {
            val line = reader.readLine() ?: return
            if (line.trim() == "data: [DONE]") return
            parseDelta(line)?.let { (content, reasoning) -> onDelta(content, reasoning) }
        }
    }

    /** Streams an answer from llama-server at [baseUrl]; returns when it finishes or is cancelled. */
    fun stream(
        baseUrl: String,
        history: List<LocalChatMessage>,
        cancelled: () -> Boolean,
        onDelta: (content: String, reasoning: String) -> Unit,
    ) {
        val connection = (URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 600_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(request(history).toString().toByteArray()) }
            if (connection.responseCode !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                error(message ?: "The on-device model answered ${connection.responseCode}.")
            }
            connection.inputStream.bufferedReader().use { readStream(it, cancelled, onDelta) }
        } finally {
            connection.disconnect()
        }
    }
}
