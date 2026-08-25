package com.ming.focusplan.assistant

import com.ming.focusplan.data.ModelProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class AssistantReply(
    val text: String,
    val model: String,
    val usedFallback: Boolean = false,
    val localFallback: Boolean = false
)

private data class ModelAttempt(val text: String? = null, val error: String? = null, val retryPlain: Boolean = false)

class ModelRouter(private val apiKeys: ApiKeyStore) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(140, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    suspend fun complete(prompt: String, profiles: List<ModelProfileEntity>, jsonMode: Boolean = false): AssistantReply = withContext(Dispatchers.IO) {
        val candidates = profiles.filter { it.enabled }.sortedBy { it.id }
        if (candidates.isEmpty()) return@withContext AssistantReply("尚未启用可用模型，已改用本地规则。", "本地规则", localFallback = true)
        var lastError: String? = null
        for ((index, profile) in candidates.withIndex()) {
            try {
                val first = request(profile, prompt, planning = jsonMode, useJsonFormat = jsonMode)
                if (!first.text.isNullOrBlank()) return@withContext AssistantReply(first.text, profile.modelId, index > 0)
                lastError = first.error
                if (jsonMode && first.retryPlain) {
                    val plain = request(profile, prompt, planning = true, useJsonFormat = false)
                    if (!plain.text.isNullOrBlank()) return@withContext AssistantReply(plain.text, profile.modelId, index > 0)
                    lastError = plain.error
                }
            } catch (e: Exception) {
                val detail = if (e is SocketTimeoutException) "生成超时（已等待最长150秒）" else e.message ?: e.javaClass.simpleName
                lastError = "${profile.name}: $detail"
            }
        }
        AssistantReply("暂时无法连接模型（${lastError ?: "未知错误"}）。我已保留你的输入，可改用本地排程。", "本地规则", localFallback = true)
    }

    private fun request(profile: ModelProfileEntity, prompt: String, planning: Boolean, useJsonFormat: Boolean): ModelAttempt {
        val body = JSONObject()
            .put("model", profile.modelId)
            .put("stream", false)
            .put("max_tokens", if (planning) 8192 else 1600)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        if (useJsonFormat) body.put("response_format", JSONObject().put("type", "json_object"))
        val endpoint = profile.baseUrl.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
        val request = Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { apiKeys.read(profile.apiKeyAlias)?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val apiMessage = runCatching { JSONObject(responseText).optJSONObject("error")?.optString("message") }.getOrNull()
                return ModelAttempt(
                    error = "${profile.name}: HTTP ${response.code}${apiMessage?.takeIf { it.isNotBlank() }?.let { "，${it.take(160)}" }.orEmpty()}",
                    retryPlain = useJsonFormat && response.code in listOf(400, 404, 415, 422)
                )
            }
            val json = JSONObject(responseText)
            val text = extractResponseText(json, allowReasoningJson = planning)
            if (!text.isNullOrBlank()) return ModelAttempt(text = text)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val finishReason = choice?.optString("finish_reason")?.takeIf { it.isNotBlank() } ?: "未知"
            val completionTokens = json.optJSONObject("usage")?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }
            val tokenText = completionTokens?.let { "，输出${it} tokens" }.orEmpty()
            return ModelAttempt(
                error = "${profile.name}: 模型返回为空（finish_reason=$finishReason$tokenText）",
                retryPlain = useJsonFormat
            )
        }
    }

    private fun extractResponseText(root: JSONObject, allowReasoningJson: Boolean): String? {
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        extractContent(message?.opt("content"))?.takeIf { it.isNotBlank() }?.let { return it }
        choice?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optString("output_text")?.takeIf { it.isNotBlank() }?.let { return it }
        if (allowReasoningJson) {
            message?.optString("reasoning_content")?.takeIf { it.isNotBlank() }?.let { reasoning ->
                extractJsonObject(reasoning)?.let { return it }
            }
        }
        return null
    }

    private fun extractContent(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> value.optString("text").ifBlank { value.optString("content") }
        is JSONArray -> buildList {
            for (index in 0 until value.length()) extractContent(value.opt(index))?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("\n")
        else -> null
    }

    private fun extractJsonObject(text: String): String? {
        val end = text.lastIndexOf('}')
        if (end < 0) return null
        text.indices.filter { text[it] == '{' }.forEach { start ->
            val candidate = text.substring(start, end + 1)
            if (runCatching { JSONObject(candidate) }.isSuccess) return candidate
        }
        return null
    }
}
