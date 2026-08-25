package com.ming.focusplan.assistant

import android.content.Context
import com.ming.focusplan.data.Priority
import org.json.JSONArray
import org.json.JSONObject

class AssistantPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("assistant_planning_preset", Context.MODE_PRIVATE)

    fun load(): AssistantPreset = runCatching {
        val root = JSONObject(preferences.getString(KEY_PRESET, null) ?: return AssistantPreset())
        val excluded = root.optJSONArray("excluded") ?: JSONArray()
        val loadedStart = root.optInt("windowStart", PLANNING_DAY_START_MINUTE)
            .coerceIn(PLANNING_DAY_START_MINUTE, PLANNING_DAY_END_MINUTE - 10)
        val loadedEnd = normalizePlanningMinute(root.optInt("windowEnd", PLANNING_DAY_END_MINUTE))
            .coerceIn(loadedStart + 10, PLANNING_DAY_END_MINUTE)
        AssistantPreset(
            instructions = root.optString("instructions").ifBlank { AssistantPreset().instructions },
            windowStartMinute = loadedStart,
            windowEndMinute = loadedEnd,
            excludedTimes = buildList {
                for (index in 0 until excluded.length()) {
                    excluded.optJSONObject(index)?.let { item ->
                        val start = normalizePlanningMinute(item.optInt("start", 12 * 60))
                            .coerceIn(PLANNING_DAY_START_MINUTE, PLANNING_DAY_END_MINUTE - 10)
                        val end = normalizePlanningMinute(item.optInt("end", 13 * 60))
                            .coerceIn(start + 10, PLANNING_DAY_END_MINUTE)
                        add(
                            ExcludedTime(
                                label = item.optString("label").ifBlank { "排除" },
                                startMinute = start,
                                endMinute = end,
                                enabled = item.optBoolean("enabled", true),
                                id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                            )
                        )
                    }
                }
            }
        )
    }.getOrDefault(AssistantPreset())

    fun save(preset: AssistantPreset) {
        val excluded = JSONArray().apply {
            preset.excludedTimes.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("label", item.label)
                        .put("start", item.startMinute)
                        .put("end", item.endMinute)
                        .put("enabled", item.enabled)
                )
            }
        }
        val root = JSONObject()
            .put("instructions", preset.instructions)
            .put("windowStart", preset.windowStartMinute)
            .put("windowEnd", preset.windowEndMinute)
            .put("excluded", excluded)
        preferences.edit().putString(KEY_PRESET, root.toString()).apply()
    }

    fun loadWorkspace(now: Long = System.currentTimeMillis()): AssistantWorkspace = runCatching {
        val root = JSONObject(preferences.getString(KEY_WORKSPACE, null) ?: return AssistantWorkspace())
        val cutoff = now - HISTORY_RETENTION_MILLIS
        val messagesJson = root.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val item = messagesJson.optJSONObject(index) ?: continue
                val createdAt = item.optLong("createdAt", now)
                if (createdAt >= cutoff) {
                    add(
                        AssistantConversationMessage(
                            id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            fromUser = item.optBoolean("fromUser"),
                            text = item.optString("text"),
                            createdAt = createdAt
                        )
                    )
                }
            }
        }
        val batchesJson = root.optJSONArray("draftBatches") ?: JSONArray()
        val batches = buildList {
            for (index in 0 until batchesJson.length()) {
                val item = batchesJson.optJSONObject(index) ?: continue
                val tasksJson = item.optJSONArray("tasks") ?: JSONArray()
                val tasks = buildList {
                    for (taskIndex in 0 until tasksJson.length()) {
                        val task = tasksJson.optJSONObject(taskIndex) ?: continue
                        val title = task.optString("title").trim()
                        if (title.isBlank()) continue
                        add(
                            AssistantTaskSuggestion(
                                id = task.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                                title = title,
                                detail = task.optString("detail"),
                                label = task.optString("label").ifBlank { "未分类" },
                                priority = runCatching { Priority.valueOf(task.optString("priority")) }.getOrDefault(Priority.MEDIUM),
                                minutes = task.optInt("minutes", 50).coerceIn(10, 480),
                                dayOffset = task.optInt("dayOffset", 0).coerceIn(0, 30),
                                selected = task.optBoolean("selected", true)
                            )
                        )
                    }
                }
                if (tasks.isNotEmpty()) {
                    add(
                        AssistantDraftBatch(
                            id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            createdAt = item.optLong("createdAt", now),
                            sourcePrompt = item.optString("sourcePrompt"),
                            summary = item.optString("summary"),
                            modelName = item.optString("modelName").ifBlank { "未知模型" },
                            tasks = tasks,
                            baseDate = item.optString("baseDate").ifBlank {
                                java.time.Instant.ofEpochMilli(item.optLong("createdAt", now))
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                            },
                            requestedWindowStart = item.optionalInt("windowStart"),
                            requestedWindowEnd = item.optionalInt("windowEnd")
                        )
                    )
                }
            }
        }
        AssistantWorkspace(messages, batches.takeLast(MAX_DRAFT_BATCHES)).also { workspace ->
            if (workspace.messages.size != messagesJson.length() || workspace.draftBatches.size != batchesJson.length()) saveWorkspace(workspace)
        }
    }.getOrDefault(AssistantWorkspace())

    fun saveWorkspace(workspace: AssistantWorkspace) {
        val messages = JSONArray().apply {
            workspace.messages.forEach { message ->
                put(
                    JSONObject()
                        .put("id", message.id)
                        .put("fromUser", message.fromUser)
                        .put("text", message.text)
                        .put("createdAt", message.createdAt)
                )
            }
        }
        val batches = JSONArray().apply {
            workspace.draftBatches.takeLast(MAX_DRAFT_BATCHES).forEach { batch ->
                val tasks = JSONArray().apply {
                    batch.tasks.forEach { task ->
                        put(
                            JSONObject()
                                .put("id", task.id)
                                .put("title", task.title)
                                .put("detail", task.detail)
                                .put("label", task.label)
                                .put("priority", task.priority.name)
                                .put("minutes", task.minutes)
                                .put("dayOffset", task.dayOffset)
                                .put("selected", task.selected)
                        )
                    }
                }
                put(
                    JSONObject()
                        .put("id", batch.id)
                        .put("createdAt", batch.createdAt)
                        .put("sourcePrompt", batch.sourcePrompt)
                        .put("summary", batch.summary)
                        .put("modelName", batch.modelName)
                        .put("baseDate", batch.baseDate)
                        .put("windowStart", batch.requestedWindowStart ?: JSONObject.NULL)
                        .put("windowEnd", batch.requestedWindowEnd ?: JSONObject.NULL)
                        .put("tasks", tasks)
                )
            }
        }
        preferences.edit().putString(KEY_WORKSPACE, JSONObject().put("messages", messages).put("draftBatches", batches).toString()).apply()
    }

    private fun JSONObject.optionalInt(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    companion object {
        private const val KEY_PRESET = "preset"
        private const val KEY_WORKSPACE = "workspace"
        private const val HISTORY_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_DRAFT_BATCHES = 40
    }
}
