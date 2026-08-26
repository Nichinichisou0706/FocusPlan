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
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class AssistantReply(
    val text: String,
    val model: String,
    val usedFallback: Boolean = false,
    val localFallback: Boolean = false
)

data class ModelRequestOptions(
    val maxTokens: Int? = null,
    val totalTimeoutMillis: Long? = null,
    val maxCandidates: Int = Int.MAX_VALUE,
    val retryWithoutJson: Boolean = true,
    val preferFastModel: Boolean = false,
    val disableThinking: Boolean = false
)

private data class ModelAttempt(
    val text: String? = null,
    val error: String? = null,
    val retryPlain: Boolean = false,
    val retryWithoutThinking: Boolean = false
)

class ModelRouter(private val apiKeys: ApiKeyStore) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(140, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    suspend fun complete(
        prompt: String,
        profiles: List<ModelProfileEntity>,
        jsonMode: Boolean = false,
        systemPrompt: String? = null,
        options: ModelRequestOptions = ModelRequestOptions()
    ): AssistantReply = withContext(Dispatchers.IO) {
        val candidates = profiles.filter { it.enabled }.sortedBy { it.id }.take(options.maxCandidates.coerceAtLeast(1))
        if (candidates.isEmpty()) return@withContext AssistantReply("尚未启用可用模型，已改用本地规则。", "本地规则", localFallback = true)
        val startedAt = System.nanoTime()
        var lastError: String? = null
        for ((index, profile) in candidates.withIndex()) {
            val requestModelId = effectiveModelId(profile, options.preferFastModel)
            val remainingMillis = options.totalTimeoutMillis?.let { limit ->
                limit - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            }
            if (remainingMillis != null && remainingMillis <= 0) {
                lastError = "智能排程已达到${options.totalTimeoutMillis / 1_000}秒等待上限"
                break
            }
            try {
                var thinkingControlEnabled = options.disableThinking
                var attempt = request(
                    profile = profile,
                    modelId = requestModelId,
                    prompt = prompt,
                    planning = jsonMode,
                    useJsonFormat = jsonMode,
                    systemPrompt = systemPrompt,
                    maxTokens = options.maxTokens,
                    callTimeoutMillis = remainingMillis,
                    disableThinking = thinkingControlEnabled
                )
                if (!attempt.text.isNullOrBlank()) return@withContext AssistantReply(attempt.text, requestModelId, index > 0 || requestModelId != profile.modelId)
                lastError = attempt.error
                var retryRemainingMillis = options.totalTimeoutMillis?.let { limit ->
                    limit - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                }
                if (attempt.retryWithoutThinking && (retryRemainingMillis == null || retryRemainingMillis > 0)) {
                    thinkingControlEnabled = false
                    attempt = request(
                        profile = profile,
                        modelId = requestModelId,
                        prompt = prompt,
                        planning = jsonMode,
                        useJsonFormat = jsonMode,
                        systemPrompt = systemPrompt,
                        maxTokens = options.maxTokens,
                        callTimeoutMillis = retryRemainingMillis,
                        disableThinking = false
                    )
                    if (!attempt.text.isNullOrBlank()) return@withContext AssistantReply(attempt.text, requestModelId, index > 0 || requestModelId != profile.modelId)
                    lastError = attempt.error
                    retryRemainingMillis = options.totalTimeoutMillis?.let { limit ->
                        limit - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    }
                }
                if (jsonMode && attempt.retryPlain && options.retryWithoutJson && (retryRemainingMillis == null || retryRemainingMillis > 0)) {
                    val plain = request(
                        profile = profile,
                        modelId = requestModelId,
                        prompt = prompt,
                        planning = true,
                        useJsonFormat = false,
                        systemPrompt = systemPrompt,
                        maxTokens = options.maxTokens,
                        callTimeoutMillis = retryRemainingMillis,
                        disableThinking = thinkingControlEnabled
                    )
                    if (!plain.text.isNullOrBlank()) return@withContext AssistantReply(plain.text, requestModelId, index > 0 || requestModelId != profile.modelId)
                    lastError = plain.error
                }
            } catch (e: Exception) {
                val timeoutSeconds = options.totalTimeoutMillis?.div(1_000) ?: 150
                val detail = if (e is SocketTimeoutException || e is InterruptedIOException) "生成超时（等待上限${timeoutSeconds}秒）" else e.message ?: e.javaClass.simpleName
                lastError = "${profile.name}: $detail"
            }
        }
        AssistantReply("模型未能及时返回可用结果（${lastError ?: "未知错误"}），已自动改用本地排程。", "本地规则", localFallback = true)
    }

    private fun request(
        profile: ModelProfileEntity,
        modelId: String,
        prompt: String,
        planning: Boolean,
        useJsonFormat: Boolean,
        systemPrompt: String?,
        maxTokens: Int?,
        callTimeoutMillis: Long?,
        disableThinking: Boolean
    ): ModelAttempt {
        val messages = JSONArray().apply {
            systemPrompt?.takeIf { it.isNotBlank() }?.let { put(JSONObject().put("role", "system").put("content", it)) }
            put(JSONObject().put("role", "user").put("content", prompt))
        }
        val body = JSONObject()
            .put("model", modelId)
            .put("stream", false)
            .put("max_tokens", maxTokens ?: if (planning) 4096 else 1600)
            .put("messages", messages)
        if (disableThinking) body.put("thinking", JSONObject().put("type", "disabled"))
        if (useJsonFormat) body.put("response_format", JSONObject().put("type", "json_object"))
        val endpoint = profile.baseUrl.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
        val request = Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { apiKeys.read(profile.apiKeyAlias)?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .build()
        val call = client.newCall(request)
        callTimeoutMillis?.takeIf { it > 0 }?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        call.execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val apiMessage = runCatching { JSONObject(responseText).optJSONObject("error")?.optString("message") }.getOrNull()
                return ModelAttempt(
                    error = "${profile.name}: HTTP ${response.code}${apiMessage?.takeIf { it.isNotBlank() }?.let { "，${it.take(160)}" }.orEmpty()}",
                    retryPlain = useJsonFormat && response.code in listOf(400, 404, 415, 422),
                    retryWithoutThinking = disableThinking && response.code in listOf(400, 404, 415, 422)
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
                retryPlain = useJsonFormat && finishReason.lowercase() !in setOf("length", "max_tokens")
            )
        }
    }

    private fun effectiveModelId(profile: ModelProfileEntity, preferFastModel: Boolean): String {
        if (!preferFastModel) return profile.modelId
        val officialDeepSeek = runCatching {
            java.net.URI(profile.baseUrl).host?.equals("api.deepseek.com", ignoreCase = true) == true
        }.getOrDefault(false)
        return if (officialDeepSeek && profile.modelId.equals("deepseek-reasoner", ignoreCase = true)) "deepseek-chat" else profile.modelId
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
