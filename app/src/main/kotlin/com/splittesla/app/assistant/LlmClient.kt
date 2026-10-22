package com.splittesla.app.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot text calls to Claude, ChatGPT or Gemini with the driver's own key.
 * Plain HTTPS, no SDKs, so the APK stays small.
 */
class LlmClient(private val provider: LlmProvider, private val apiKey: String, private val model: String) {

    suspend fun complete(system: String, user: String, maxTokens: Int = 300): String = withContext(Dispatchers.IO) {
        when (provider) {
            LlmProvider.CLAUDE -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                val reply = post(
                    "https://api.anthropic.com/v1/messages",
                    mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                    body,
                )
                val content = reply.getJSONArray("content")
                (0 until content.length()).map { content.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("") { it.getString("text") }
            }
            LlmProvider.GPT -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("max_completion_tokens", maxTokens)
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", system))
                            .put(JSONObject().put("role", "user").put("content", user)),
                    )
                val reply = post("https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer $apiKey"), body)
                reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            }
            LlmProvider.GEMINI -> {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put(
                        "contents",
                        JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))),
                    )
                    .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
                val reply = post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    mapOf("x-goog-api-key" to apiKey),
                    body,
                )
                val parts = reply.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            }
        }.trim()
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("${provider.label} 오류 $code: ${errorMessage(text)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** The human-readable part of an error body; all three APIs nest it under "error". */
    private fun errorMessage(text: String): String = try {
        val err = JSONObject(text).opt("error")
        when (err) {
            is JSONObject -> err.optString("message", text)
            is String -> err
            else -> text
        }
    } catch (_: Exception) {
        text
    }.take(200)
}
