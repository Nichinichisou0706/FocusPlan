package com.ming.focusplan.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ModelCatalogClient {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun fetch(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(modelsEndpoint(baseUrl))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val data = JSONObject(body).optJSONArray("data") ?: error("响应中没有模型列表")
                buildList {
                    for (index in 0 until data.length()) {
                        data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.distinct().sorted().ifEmpty { error("模型列表为空") }
            }
        }
    }

    companion object {
        fun modelsEndpoint(baseUrl: String): String {
            val clean = baseUrl.trim().trimEnd('/')
                .removeSuffix("/chat/completions")
            return "$clean/models"
        }
    }
}
