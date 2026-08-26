package com.ming.focusplan.data

import androidx.room.Entity
import androidx.room.PrimaryKey

fun normalizeTaskLabel(value: String): String = value.trim().ifBlank { "未分类" }

enum class Priority(val rank: Int, val label: String) {
    HIGH(3, "高"), MEDIUM(2, "中"), LOW(1, "低");

    companion object { fun fromRank(rank: Int) = entries.firstOrNull { it.rank == rank } ?: MEDIUM }
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String = "",
    val subject: String = "未分类",
    val priority: Int = Priority.MEDIUM.rank,
    val estimatedMinutes: Int = 50,
    val plannedDayEpoch: Long? = null,
    val dueAt: Long? = null,
    val completed: Boolean = false,
    val parentTaskId: Long? = null,
    val splitGroupId: String? = null,
    val splitIndex: Int? = null,
    val splitCount: Int? = null,
    val hidden: Boolean = false,
    val originalMinutes: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_labels")
data class TaskLabelEntity(
    @PrimaryKey val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "schedule_blocks")
data class ScheduleBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long? = null,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val priority: Int = Priority.MEDIUM.rank,
    val isFixed: Boolean = false
)

@Entity(tableName = "model_profiles")
data class ModelProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val apiKeyAlias: String = "",
    val role: String = "经济",
    val enabled: Boolean = true,
    val supportsJson: Boolean = true
)
