package com.ming.focusplan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ming.focusplan.data.Priority
import com.ming.focusplan.data.TaskEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal data class TaskTypeStyle(val accent: Color, val container: Color, val glyph: String)

private val taskTypePalette = listOf(
    Color(0xFF315E9B) to Color(0xFFE8F0FC),
    Color(0xFF8C4F7D) to Color(0xFFF7EAF3),
    Color(0xFF2F725B) to Color(0xFFE5F3ED),
    Color(0xFFA05A32) to Color(0xFFFAEDE5),
    Color(0xFF76631C) to Color(0xFFF7F1D8),
    Color(0xFF5F5A82) to Color(0xFFEDEBF6)
)

internal fun taskTypeStyle(type: String): TaskTypeStyle {
    val normalized = type.trim().ifBlank { "未分类" }
    val colors = taskTypePalette[(normalized.hashCode() and Int.MAX_VALUE) % taskTypePalette.size]
    val glyph = when {
        normalized.contains("数学") -> "∑"
        normalized.contains("英语") || normalized.contains("英文") -> "A"
        normalized.contains("政治") -> "政"
        normalized.contains("专业") -> "专"
        normalized.contains("复盘") -> "✓"
        else -> normalized.take(1)
    }
    return TaskTypeStyle(colors.first, colors.second, glyph)
}

internal data class TaskEditorValue(
    val title: String = "",
    val detail: String = "",
    val label: String = "未分类",
    val priority: Priority = Priority.MEDIUM,
    val minutes: Int = 50,
    val plannedDayEpoch: Long? = null
)

internal fun TaskEntity.toEditorValue() = TaskEditorValue(
    title = title,
    detail = detail,
    label = subject,
    priority = Priority.fromRank(priority),
    minutes = originalMinutes ?: estimatedMinutes,
    plannedDayEpoch = plannedDayEpoch
)

@Composable
internal fun UnifiedTaskCard(
    task: TaskEntity,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val style = taskTypeStyle(task.subject)
    OutlinedCard(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .then(if (selected) Modifier.border(2.dp, style.accent, RoundedCornerShape(6.dp)) else Modifier),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = style.container)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(style.accent),
                contentAlignment = Alignment.Center
            ) {
                Text(style.glyph, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (task.detail.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        task.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    taskCardMetadata(task),
                    style = MaterialTheme.typography.labelMedium,
                    color = style.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailingContent()
        }
    }
}

@Composable
internal fun TaskDetailsDialog(
    initial: TaskEditorValue,
    knownLabels: List<String>,
    dialogTitle: String,
    splitMessage: String? = null,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onReturnToPending: (() -> Unit)? = null,
    onSave: (TaskEditorValue) -> Unit
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var detail by remember(initial) { mutableStateOf(initial.detail) }
    var label by remember(initial) { mutableStateOf(initial.label.takeUnless { it == "未分类" }.orEmpty()) }
    var priority by remember(initial) { mutableStateOf(initial.priority) }
    var minutesText by remember(initial) { mutableStateOf(initial.minutes.toString()) }
    var hasPlannedDay by remember(initial) { mutableStateOf(initial.plannedDayEpoch != null) }
    var plannedDayEpoch by remember(initial) { mutableLongStateOf(initial.plannedDayEpoch ?: currentPlanningDateForEditor().toEpochDay()) }
    val minutes = minutesText.toIntOrNull()?.coerceIn(10, 480) ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                splitMessage?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                        Text(it, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务名称") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    label = { Text("具体步骤与完成标准") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("标签") },
                    placeholder = { Text("可留空，也可输入新标签") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = label.isBlank(), onClick = { label = "" }, label = { Text("未分类") }) }
                    items(knownLabels, key = { "editor-label-$it" }) { item ->
                        FilterChip(selected = label == item, onClick = { label = item }, label = { Text(item) })
                    }
                }
                Text("优先级", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { item ->
                        FilterChip(selected = priority == item, onClick = { priority = item }, label = { Text(item.label) })
                    }
                }
                Text("预计总时长", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    FilledTonalIconButton(onClick = { minutesText = (minutes.coerceAtLeast(20) - 10).toString() }) {
                        Text("-", style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { value -> if (value.all(Char::isDigit)) minutesText = value },
                        modifier = Modifier.width(128.dp).padding(horizontal = 8.dp),
                        suffix = { Text("分钟") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    FilledTonalIconButton(onClick = { minutesText = (minutes.coerceAtLeast(0) + 10).coerceAtMost(480).toString() }) {
                        Icon(Icons.Default.Add, "增加时长")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("执行日", style = MaterialTheme.typography.labelLarge)
                        Text("不指定时由智能体选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = hasPlannedDay, onCheckedChange = { hasPlannedDay = it })
                }
                if (hasPlannedDay) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        IconButton(onClick = { plannedDayEpoch-- }) { Text("-", style = MaterialTheme.typography.titleLarge) }
                        Text(formatPlannedDay(plannedDayEpoch), Modifier.width(148.dp), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { plannedDayEpoch++ }) { Icon(Icons.Default.Add, "后移一天") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        TaskEditorValue(
                            title = title.trim(),
                            detail = detail.trim(),
                            label = label.trim().ifBlank { "未分类" },
                            priority = priority,
                            minutes = minutes,
                            plannedDayEpoch = plannedDayEpoch.takeIf { hasPlannedDay }
                        )
                    )
                },
                enabled = title.isNotBlank() && minutes in 10..480
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    IconButton(onClick = it) { Icon(Icons.Default.Delete, "删除任务", tint = MaterialTheme.colorScheme.error) }
                }
                onReturnToPending?.let {
                    IconButton(onClick = it) { Icon(Icons.Default.Refresh, "退回待办", tint = MaterialTheme.colorScheme.primary) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

private fun taskCardMetadata(task: TaskEntity): String {
    val base = buildList {
        task.plannedDayEpoch?.let { add(formatPlannedDay(it)) }
        add(task.subject.ifBlank { "未分类" })
        add("${Priority.fromRank(task.priority).label}优先")
        add("${task.estimatedMinutes}分钟")
    }
    val split = when {
        task.parentTaskId == null -> null
        task.splitIndex != null && task.splitCount != null -> "分块 ${task.splitIndex}/${task.splitCount}，整组 ${task.originalMinutes ?: task.estimatedMinutes}分钟"
        else -> "同组分块已融合，剩余 ${task.estimatedMinutes}分钟"
    }
    return (base + listOfNotNull(split)).joinToString(" · ")
}

private fun currentPlanningDateForEditor(): LocalDate = if (LocalTime.now().isBefore(LocalTime.of(2, 0))) {
    LocalDate.now().minusDays(1)
} else {
    LocalDate.now()
}

private fun formatPlannedDay(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val today = currentPlanningDateForEditor()
    val relative = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> date.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    return "$relative · ${date.format(DateTimeFormatter.ofPattern("M月d日"))}"
}
