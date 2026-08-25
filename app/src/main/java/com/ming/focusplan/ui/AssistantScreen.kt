package com.ming.focusplan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ming.focusplan.assistant.*
import com.ming.focusplan.data.Priority
import com.ming.focusplan.data.TaskEntity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun AssistantScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.assistantUiState.collectAsState()
    val preset by vm.assistantPreset.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val scheduledIds by vm.scheduledTaskIds.collectAsState()
    val models by vm.models.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showPreset by remember { mutableStateOf(false) }
    var selectedExisting by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var editingDraft by remember { mutableStateOf<Pair<String, AssistantTaskSuggestion>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val availableExisting = tasks.filter { !it.completed && it.id !in scheduledIds }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("智能助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${models.count { it.enabled }} 个模型可用 · 对话保留 7 天", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Mascot(
                    mood = when {
                        state.error != null -> MascotMood.BLOCKED
                        state.loading -> MascotMood.WORKING
                        else -> MascotMood.WELCOME
                    },
                    contentDescription = null,
                    size = 62.dp
                )
                IconButton(onClick = { showPreset = true }) { Icon(Icons.Default.Settings, "排程预设") }
            }

            PresetScheduleBar(preset, onClick = { showPreset = true })

            TabRow(selectedTabIndex = page) {
                listOf("对话", "草案 ${state.suggestionCount}", "待办 ${availableExisting.size}").forEachIndexed { index, title ->
                    Tab(selected = page == index, onClick = { page = index }, text = { Text(title, maxLines = 1) })
                }
            }

            when (page) {
                0 -> AssistantChatPage(
                    state = state,
                    enabledModelCount = models.count { it.enabled },
                    input = input,
                    onInputChange = { input = it },
                    onSend = { vm.requestAssistantPlan(input); input = "" },
                    onOpenDrafts = { page = 1 },
                    modifier = Modifier.weight(1f)
                )
                1 -> AssistantDraftsPage(
                    state = state,
                    onToggle = vm::toggleAssistantSuggestion,
                    onEdit = { batchId, suggestion -> editingDraft = batchId to suggestion },
                    onDeleteTask = vm::deleteAssistantSuggestion,
                    onDeleteBatch = vm::deleteAssistantBatch,
                    onCreate = { batchId, schedule ->
                        vm.createAssistantSuggestions(batchId, schedule) { scope.launch { snackbar.showSnackbar(it) } }
                    },
                    modifier = Modifier.weight(1f)
                )
                else -> ExistingTasksPage(
                    tasks = availableExisting,
                    selectedIds = selectedExisting,
                    onSelectionChange = { selectedExisting = it.distinct() },
                    onSchedule = {
                        vm.scheduleExistingTasks(selectedExisting.toSet()) {
                            selectedExisting = emptyList()
                            scope.launch { snackbar.showSnackbar(it) }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showPreset) AssistantPresetDialog(preset, onDismiss = { showPreset = false }) {
        vm.saveAssistantPreset(it)
        showPreset = false
    }
    editingDraft?.let { (batchId, draft) ->
        EditSuggestionDialog(
            suggestion = draft,
            onDismiss = { editingDraft = null },
            onSave = { vm.updateAssistantSuggestion(batchId, it); editingDraft = null }
        )
    }
}

@Composable
private fun PresetScheduleBar(preset: AssistantPreset, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        tonalElevation = 1.dp
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DateRange, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "可安排 ${AssistantPlanParser.formatMinute(preset.windowStartMinute)} 至 ${AssistantPlanParser.formatMinute(preset.windowEndMinute)}",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    preset.excludedTimes.filter { it.enabled }.joinToString(" · ") { "${it.label} ${AssistantPlanParser.formatMinute(it.startMinute)} 至 ${AssistantPlanParser.formatMinute(it.endMinute)}" }.ifBlank { "没有启用排除时段" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.Edit, "编辑时间预设", Modifier.size(19.dp))
        }
    }
}

@Composable
private fun AssistantChatPage(
    state: AssistantUiState,
    enabledModelCount: Int,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenDrafts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.loading, state.error, state.suggestionCount) {
        if (listState.layoutInfo.totalItemsCount > 0) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
    OutlinedCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (enabledModelCount == 0) item(key = "model-warning") { AssistantNotice("尚未启用模型，将使用本地规则。请在设置中添加并开启模型。", true) }
                state.modelName?.let { modelName ->
                    item(key = "reply-source") { Text("本次回复：$modelName", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (state.messages.isEmpty()) {
                    item(key = "chat-empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Mascot(MascotMood.WELCOME, contentDescription = null, size = 126.dp)
                            Text("先聊聊今天吧", fontWeight = FontWeight.SemiBold)
                            Text(
                                "提到具体任务或安排时，我会生成可编辑草案",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                items(state.messages, key = { it.id }) { message -> ChatBubble(message) }
                if (state.loading) {
                    item(key = "assistant-loading") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Mascot(MascotMood.WORKING, contentDescription = null, size = 48.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在回复，任务拆解可能需要更长时间…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.error?.let { error -> item(key = "assistant-error") { AssistantNotice(error, true) } }
                if (state.draftBatches.isNotEmpty()) {
                    item(key = "draft-ready-${state.draftBatches.size}-${state.suggestionCount}") {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDrafts),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null)
                                Spacer(Modifier.width(10.dp))
                                Text("${state.draftBatches.size} 批 · ${state.suggestionCount} 项待确认", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看任务草案")
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息，或说“今天安排…”") },
                    minLines = 1,
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onSend, enabled = input.isNotBlank() && !state.loading) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
            }
        }
    }
}

@Composable
private fun AssistantDraftsPage(
    state: AssistantUiState,
    onToggle: (String, String) -> Unit,
    onEdit: (String, AssistantTaskSuggestion) -> Unit,
    onDeleteTask: (String, String) -> Unit,
    onDeleteBatch: (String) -> Unit,
    onCreate: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.draftBatches.isEmpty()) {
            item(key = "draft-empty") {
                Text("暂无草案。普通聊天不会生成任务；请在对话中明确说明要做或安排的事项。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.draftBatches.asReversed(), key = { it.id }) { batch ->
                DraftBatchCard(
                    batch = batch,
                    onToggle = { onToggle(batch.id, it) },
                    onEdit = { onEdit(batch.id, it) },
                    onDeleteTask = { onDeleteTask(batch.id, it) },
                    onDeleteBatch = { onDeleteBatch(batch.id) },
                    onCreate = { onCreate(batch.id, it) }
                )
            }
        }
    }
}

@Composable
private fun DraftBatchCard(
    batch: AssistantDraftBatch,
    onToggle: (String) -> Unit,
    onEdit: (AssistantTaskSuggestion) -> Unit,
    onDeleteTask: (String) -> Unit,
    onDeleteBatch: () -> Unit,
    onCreate: (Boolean) -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(formatBatchTime(batch.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(batch.sourcePrompt, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("${batch.modelName} · ${batch.tasks.size} 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDeleteBatch) { Icon(Icons.Default.Delete, "删除本批草案", tint = MaterialTheme.colorScheme.error) }
            }
            if (batch.requestedWindowStart != null && batch.requestedWindowEnd != null) {
                Text(
                    "本批时段 ${AssistantPlanParser.formatMinute(batch.requestedWindowStart)} 至 ${AssistantPlanParser.formatMinute(batch.requestedWindowEnd)}",
                    Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider()
            val sortedTasks = batch.tasks.sortedWith(compareBy<AssistantTaskSuggestion> { it.dayOffset }.thenByDescending { it.priority.rank })
            var previousDay: Int? = null
            sortedTasks.forEachIndexed { index, suggestion ->
                if (previousDay != suggestion.dayOffset) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)) {
                        Text(
                            planDayLabel(batch.baseDate, suggestion.dayOffset),
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    previousDay = suggestion.dayOffset
                }
                DraftTaskRow(suggestion, batch.baseDate, onToggle = { onToggle(suggestion.id) }, onEdit = { onEdit(suggestion) }, onDelete = { onDeleteTask(suggestion.id) })
                if (index < sortedTasks.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCreate(false) }, modifier = Modifier.weight(1f), enabled = batch.tasks.any { it.selected }) { Text("创建任务") }
                Button(onClick = { onCreate(true) }, modifier = Modifier.weight(1f), enabled = batch.tasks.any { it.selected }) {
                    Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("创建并排程")
                }
            }
        }
    }
}

@Composable
private fun DraftTaskRow(suggestion: AssistantTaskSuggestion, baseDate: String, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 4.dp, end = 2.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.Top) {
        Checkbox(suggestion.selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(top = 5.dp)) {
            Text(suggestion.title, fontWeight = FontWeight.SemiBold)
            if (suggestion.detail.isNotBlank()) Text(suggestion.detail, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${planDayLabel(baseDate, suggestion.dayOffset)} · ${suggestion.label} · ${suggestion.priority.label}优先 · ${suggestion.minutes}分钟", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑并细化", Modifier.size(19.dp)) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除草案", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun EditSuggestionDialog(suggestion: AssistantTaskSuggestion, onDismiss: () -> Unit, onSave: (AssistantTaskSuggestion) -> Unit) {
    var title by remember(suggestion.id) { mutableStateOf(suggestion.title) }
    var detail by remember(suggestion.id) { mutableStateOf(suggestion.detail) }
    var label by remember(suggestion.id) { mutableStateOf(suggestion.label) }
    var priority by remember(suggestion.id) { mutableStateOf(suggestion.priority) }
    var minutes by remember(suggestion.id) { mutableIntStateOf(suggestion.minutes) }
    var dayOffset by remember(suggestion.id) { mutableIntStateOf(suggestion.dayOffset) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑并细化任务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("任务名称") }, maxLines = 2)
                OutlinedTextField(detail, { detail = it }, Modifier.fillMaxWidth(), label = { Text("具体步骤与完成标准") }, minLines = 3, maxLines = 6)
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("标签") }, singleLine = true)
                Text("优先级", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Priority.entries.forEach { item -> FilterChip(selected = priority == item, onClick = { priority = item }, label = { Text(item.label) }) }
                }
                Text("预计时长", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { minutes = (minutes - 10).coerceAtLeast(10) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text("$minutes 分钟", Modifier.width(100.dp), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { minutes = (minutes + 10).coerceAtMost(480) }) { Icon(Icons.Default.Add, "增加10分钟") }
                }
                Text("执行日", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { dayOffset = (dayOffset - 1).coerceAtLeast(0) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text(if (dayOffset == 0) "今天" else "第 ${dayOffset + 1} 天", Modifier.width(100.dp), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { dayOffset = (dayOffset + 1).coerceAtMost(30) }) { Icon(Icons.Default.Add, "后移一天") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(suggestion.copy(title = title.trim(), detail = detail.trim(), label = label.trim().ifBlank { "未分类" }, priority = priority, minutes = minutes, dayOffset = dayOffset)) },
                enabled = title.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ExistingTasksPage(
    tasks: List<TaskEntity>,
    selectedIds: List<Long>,
    onSelectionChange: (List<Long>) -> Unit,
    onSchedule: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "existing-heading") {
            Column(Modifier.padding(bottom = 6.dp)) {
                Text("未排程的已有待办", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("选择后寻找今天的可用时间，不会移动已有时间块。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tasks.isEmpty()) {
            item(key = "existing-empty") { Text("没有可加入时间轴的已有任务", Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(tasks, key = { "assistant-existing-${it.id}" }) { task ->
                val selected = task.id in selectedIds
                ListItem(
                    modifier = Modifier.clickable { onSelectionChange(if (selected) selectedIds - task.id else selectedIds + task.id) },
                    headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${task.subject} · ${Priority.fromRank(task.priority).label}优先 · ${task.estimatedMinutes}分钟") },
                    leadingContent = { Checkbox(selected, onCheckedChange = { checked -> onSelectionChange(if (checked) selectedIds + task.id else selectedIds - task.id) }) }
                )
            }
            item(key = "existing-action") {
                Button(onClick = onSchedule, enabled = selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Default.DateRange, null); Spacer(Modifier.width(6.dp)); Text("将所选任务加入时间轴")
                }
            }
        }
    }
}

@Composable
private fun AssistantNotice(text: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChatBubble(message: AssistantConversationMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) { Text(message.text, Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) }
    }
}

private data class TimeEditTarget(val type: String, val exclusionId: String? = null)

@Composable
private fun AssistantPresetDialog(preset: AssistantPreset, onDismiss: () -> Unit, onSave: (AssistantPreset) -> Unit) {
    var instructions by remember(preset) { mutableStateOf(preset.instructions) }
    var windowStart by remember(preset) { mutableIntStateOf(preset.windowStartMinute) }
    var windowEnd by remember(preset) { mutableIntStateOf(preset.windowEndMinute) }
    var exclusions by remember(preset) { mutableStateOf(preset.excludedTimes) }
    var timeTarget by remember { mutableStateOf<TimeEditTarget?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("助手排程预设") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SchedulePreview(windowStart, windowEnd, exclusions)
                OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text("长期说明") }, minLines = 2, maxLines = 5)
                Text("允许安排时段", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeSelectorButton("开始", windowStart, Modifier.weight(1f)) { timeTarget = TimeEditTarget("windowStart") }
                    TimeSelectorButton("结束", windowEnd, Modifier.weight(1f)) { timeTarget = TimeEditTarget("windowEnd") }
                }
                Text("排除时段", style = MaterialTheme.typography.labelLarge)
                Text("每项都可开关、改名、调整时间或删除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                exclusions.forEach { period ->
                    key(period.id) {
                        ExclusionSwitchRow(
                            period = period,
                            onChange = { changed -> exclusions = exclusions.map { if (it.id == changed.id) changed else it } },
                            onDelete = { exclusions = exclusions.filterNot { it.id == period.id } },
                            onStartClick = { timeTarget = TimeEditTarget("exclusionStart", period.id) },
                            onEndClick = { timeTarget = TimeEditTarget("exclusionEnd", period.id) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        val label = "新时段 ${exclusions.size + 1}"
                        exclusions = exclusions + ExcludedTime(label, 12 * 60, 13 * 60)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("新增排除时段") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalizedEnd = if (windowEnd <= windowStart) windowEnd + 24 * 60 else windowEnd
                val normalizedExclusions = exclusions.map { it.copy(label = it.label.trim().ifBlank { "未命名时段" }) }.sortedBy { it.startMinute }
                onSave(AssistantPreset(instructions.trim().ifBlank { AssistantPreset().instructions }, windowStart, normalizedEnd, normalizedExclusions))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    timeTarget?.let { target ->
        val initial = when (target.type) {
            "windowStart" -> windowStart
            "windowEnd" -> windowEnd
            "exclusionStart" -> exclusions.firstOrNull { it.id == target.exclusionId }?.startMinute ?: 0
            else -> exclusions.firstOrNull { it.id == target.exclusionId }?.endMinute ?: 0
        }
        val allowedRange = when (target.type) {
            "windowStart" -> PLANNING_DAY_START_MINUTE..(PLANNING_DAY_END_MINUTE - 10)
            "windowEnd" -> (windowStart + 10)..PLANNING_DAY_END_MINUTE
            "exclusionStart" -> PLANNING_DAY_START_MINUTE..(PLANNING_DAY_END_MINUTE - 10)
            else -> ((exclusions.firstOrNull { it.id == target.exclusionId }?.startMinute ?: PLANNING_DAY_START_MINUTE) + 10)..PLANNING_DAY_END_MINUTE
        }
        WheelTimePickerDialog(
            title = when (target.type) {
                "windowStart" -> "选择允许开始时间"
                "windowEnd" -> "选择允许结束时间"
                "exclusionStart" -> "${exclusions.firstOrNull { it.id == target.exclusionId }?.label.orEmpty()}开始"
                else -> "${exclusions.firstOrNull { it.id == target.exclusionId }?.label.orEmpty()}结束"
            },
            initialMinute = initial.coerceIn(allowedRange),
            allowedRange = allowedRange,
            onDismiss = { timeTarget = null },
            onConfirm = { raw ->
                when (target.type) {
                    "windowStart" -> {
                        windowStart = raw
                        if (windowEnd <= raw) windowEnd = (raw + 10).coerceAtMost(PLANNING_DAY_END_MINUTE)
                    }
                    "windowEnd" -> windowEnd = raw
                    "exclusionStart" -> target.exclusionId?.let { id ->
                        exclusions.firstOrNull { it.id == id }?.let { period ->
                            val end = if (period.endMinute <= raw) (raw + 10).coerceAtMost(PLANNING_DAY_END_MINUTE) else period.endMinute
                            exclusions = exclusions.map { if (it.id == id) period.copy(startMinute = raw, endMinute = end) else it }
                        }
                    }
                    else -> target.exclusionId?.let { id ->
                        exclusions.firstOrNull { it.id == id }?.let { period ->
                            exclusions = exclusions.map { if (it.id == id) period.copy(endMinute = raw) else it }
                        }
                    }
                }
                timeTarget = null
            }
        )
    }
}

@Composable
private fun SchedulePreview(start: Int, end: Int, exclusions: List<ExcludedTime>) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("简易时间表", fontWeight = FontWeight.Bold)
            Text("可安排  ${AssistantPlanParser.formatMinute(start)} 至 ${AssistantPlanParser.formatMinute(end)}", color = MaterialTheme.colorScheme.primary)
            val enabled = exclusions.filter { it.enabled }
            if (enabled.isEmpty()) Text("排除    无", style = MaterialTheme.typography.bodySmall)
            else enabled.sortedBy { it.startMinute }.forEach { Text("排除    ${it.label}  ${AssistantPlanParser.formatMinute(it.startMinute)} 至 ${AssistantPlanParser.formatMinute(it.endMinute)}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TimeSelectorButton(label: String, minute: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(58.dp), enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(AssistantPlanParser.formatMinute(minute), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExclusionSwitchRow(
    period: ExcludedTime,
    onChange: (ExcludedTime) -> Unit,
    onDelete: () -> Unit,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit
) {
    Surface(color = if (period.enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = period.enabled, onCheckedChange = { onChange(period.copy(enabled = it)) })
                OutlinedTextField(
                    value = period.label,
                    onValueChange = { onChange(period.copy(label = it.take(24))) },
                    modifier = Modifier.weight(1f).padding(start = 7.dp),
                    label = { Text("名称") },
                    singleLine = true
                )
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除排除时段", tint = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 48.dp, top = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeSelectorButton("开始", period.startMinute, Modifier.weight(1f), period.enabled, onStartClick)
                TimeSelectorButton("结束", period.endMinute, Modifier.weight(1f), period.enabled, onEndClick)
            }
        }
    }
}

@Composable
private fun WheelTimePickerDialog(
    title: String,
    initialMinute: Int,
    allowedRange: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val timeOptions = remember(allowedRange.first, allowedRange.last) { (allowedRange.first..allowedRange.last step 10).toList() }
    val initialIndex = ((initialMinute.coerceIn(allowedRange) - allowedRange.first) / 10).coerceIn(timeOptions.indices)
    val timeState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val selectedIndex by remember { derivedStateOf { centeredWheelIndex(timeState, initialIndex).coerceIn(timeOptions.indices) } }
    val selectedMinute = timeOptions[selectedIndex]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "上下拨动时间（${AssistantPlanParser.formatMinute(allowedRange.first)} 至 ${AssistantPlanParser.formatMinute(allowedRange.last)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TimeWheel(timeOptions, timeState, Modifier.width(190.dp))
                Text("当前 ${AssistantPlanParser.formatMinute(selectedMinute)}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selectedMinute) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TimeWheel(values: List<Int>, state: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    Box(modifier.height(144.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().height(48.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {}
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 48.dp)) {
            items(values) { value ->
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    Text(AssistantPlanParser.formatMinute(value), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

private fun formatBatchTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm"))

private fun planDayLabel(baseDate: String, dayOffset: Int): String {
    val date = runCatching { LocalDate.parse(baseDate).plusDays(dayOffset.toLong()) }.getOrNull()
    val relative = when (dayOffset) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        else -> "第 ${dayOffset + 1} 天"
    }
    return date?.let { "$relative · ${it.monthValue}月${it.dayOfMonth}日" } ?: relative
}

private fun centeredWheelIndex(state: androidx.compose.foundation.lazy.LazyListState, fallback: Int): Int {
    val layout = state.layoutInfo
    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return layout.visibleItemsInfo.minByOrNull { item -> abs(item.offset + item.size / 2 - center) }?.index ?: fallback
}
