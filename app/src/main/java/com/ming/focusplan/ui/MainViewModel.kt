package com.ming.focusplan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.ming.focusplan.assistant.ModelRouter
import com.ming.focusplan.assistant.ModelCatalogClient
import com.ming.focusplan.assistant.*
import com.ming.focusplan.data.*
import com.ming.focusplan.planning.Planner
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.Instant

data class TaskScreenState(
    val page: Int = 0,
    val grouping: Int = 0,
    val collapsedGroups: Set<String> = emptySet()
)

data class AssistantUiState(
    val messages: List<AssistantConversationMessage> = emptyList(),
    val draftBatches: List<AssistantDraftBatch> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val modelName: String? = null
) {
    val suggestionCount: Int get() = draftBatches.sumOf { it.tasks.size }
}

class MainViewModel(private val container: AppContainer) : ViewModel() {
    val tasks = container.tasks.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val labels = container.labels.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val models = container.models.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val rangeStart = LocalDate.now().minusYears(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val rangeEnd = LocalDate.now().plusYears(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val blocks = container.schedule.observeBetween(rangeStart, rangeEnd).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scheduledTaskIds = container.schedule.observeScheduledTaskIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    private val router = ModelRouter(container.apiKeys)
    private val catalog = ModelCatalogClient()
    private val _taskScreenState = MutableStateFlow(TaskScreenState())
    val taskScreenState = _taskScreenState.asStateFlow()
    private val _selectedFocusTaskId = MutableStateFlow<Long?>(null)
    val selectedFocusTaskId = _selectedFocusTaskId.asStateFlow()
    private val _assistantPreset = MutableStateFlow(container.assistantPreferences.load())
    val assistantPreset = _assistantPreset.asStateFlow()
    private val initialAssistantWorkspace = container.assistantPreferences.loadWorkspace()
    private val _assistantUiState = MutableStateFlow(
        AssistantUiState(messages = initialAssistantWorkspace.messages, draftBatches = initialAssistantWorkspace.draftBatches)
    )
    val assistantUiState = _assistantUiState.asStateFlow()

    fun setTaskPage(page: Int) = _taskScreenState.update { it.copy(page = page.coerceIn(0, 1)) }
    fun setTaskGrouping(grouping: Int) = _taskScreenState.update { it.copy(grouping = grouping.coerceIn(0, 1)) }
    fun toggleTaskGroup(key: String) = _taskScreenState.update { state ->
        state.copy(collapsedGroups = if (key in state.collapsedGroups) state.collapsedGroups - key else state.collapsedGroups + key)
    }
    fun selectFocusTask(taskId: Long?) { _selectedFocusTaskId.value = taskId }
    fun saveAssistantPreset(preset: AssistantPreset) {
        val start = normalizePlanningMinute(preset.windowStartMinute)
            .coerceIn(PLANNING_DAY_START_MINUTE, PLANNING_DAY_END_MINUTE - 10)
        val end = normalizePlanningMinute(preset.windowEndMinute)
            .coerceIn(start + 10, PLANNING_DAY_END_MINUTE)
        val normalized = preset.copy(
            windowStartMinute = start,
            windowEndMinute = end,
            excludedTimes = preset.excludedTimes.mapNotNull { period ->
                val periodStart = normalizePlanningMinute(period.startMinute)
                    .coerceIn(PLANNING_DAY_START_MINUTE, PLANNING_DAY_END_MINUTE - 10)
                val periodEnd = normalizePlanningMinute(period.endMinute)
                    .coerceIn(periodStart + 10, PLANNING_DAY_END_MINUTE)
                period.copy(startMinute = periodStart, endMinute = periodEnd)
            }
        )
        _assistantPreset.value = normalized
        container.assistantPreferences.save(normalized)
    }

    fun requestAssistantPlan(input: String) {
        val request = input.trim()
        if (request.isBlank() || _assistantUiState.value.loading) return
        val planningRequest = AssistantPlanParser.isPlanningRequest(request)
        val conversationHistory = _assistantUiState.value.messages.takeLast(6).map { message ->
            (if (message.fromUser) "用户：" else "助手：") + message.text
        }
        updateAssistantState { state ->
            state.copy(
                messages = state.messages + AssistantConversationMessage(fromUser = true, text = request),
                loading = true,
                error = null
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                val actualProfiles = container.models.observeAll().first()
                val actualTasks = container.tasks.observeAll().first()
                val prompt = if (planningRequest) {
                    AssistantPlanParser.prompt(
                        request,
                        _assistantPreset.value,
                        actualTasks.filterNot { it.completed }.map { it.title },
                        currentPlanningDay(),
                        conversationHistory,
                        _assistantUiState.value.draftBatches.takeLast(3).flatMap { batch -> batch.tasks.map { "${it.title}：${it.detail}" } }
                    )
                } else AssistantPlanParser.chatPrompt(request, conversationHistory)
                val reply = router.complete(prompt, actualProfiles, jsonMode = planningRequest)
                val resolved = if (reply.localFallback) Triple(AssistantPlanParser.fallback(request), reply, true)
                else if (!planningRequest) Triple(AssistantPlan(reply.text.trim(), emptyList(), isPlanning = false), reply, false)
                else {
                    val parsed = runCatching { AssistantPlanParser.parse(reply.text) }
                    parsed.fold(
                        onSuccess = { Triple(it, reply, false) },
                        onFailure = { Triple(AssistantPlanParser.fallback(request), reply.copy(text = "模型返回格式无法解析：${it.message}"), true) }
                    )
                }
                val forceSingleDay = listOf("今天完成", "今天做完", "今天全部", "今天内", "一天内").any(request::contains)
                resolved.copy(first = AssistantPlanParser.distributeAcrossDays(resolved.first, _assistantPreset.value, forceSingleDay))
            }
            result.onSuccess { (plan, reply, fallback) ->
                val sourceModel = if (reply.usedFallback && !reply.localFallback) "${reply.model}（自动切换）" else reply.model
                updateAssistantState { state ->
                    val newBatch = if (plan.isPlanning && plan.tasks.isNotEmpty()) {
                        AssistantDraftBatch(
                            sourcePrompt = request,
                            summary = plan.summary,
                            modelName = sourceModel,
                            tasks = plan.tasks,
                            baseDate = currentPlanningDay().toString(),
                            requestedWindowStart = plan.requestedWindowStart,
                            requestedWindowEnd = plan.requestedWindowEnd
                        )
                    } else null
                    state.copy(
                        messages = state.messages + AssistantConversationMessage(fromUser = false, text = plan.summary),
                        draftBatches = newBatch?.let { state.draftBatches + it } ?: state.draftBatches,
                        loading = false,
                        error = if (fallback) reply.text else null,
                        modelName = sourceModel
                    )
                }
            }.onFailure { error ->
                val rawFallback = AssistantPlanParser.fallback(request)
                val forceSingleDay = listOf("今天完成", "今天做完", "今天全部", "今天内", "一天内").any(request::contains)
                val fallback = AssistantPlanParser.distributeAcrossDays(rawFallback, _assistantPreset.value, forceSingleDay)
                updateAssistantState { state ->
                    val batch = if (fallback.isPlanning && fallback.tasks.isNotEmpty()) {
                        AssistantDraftBatch(
                            sourcePrompt = request,
                            summary = fallback.summary,
                            modelName = "本地规则",
                            tasks = fallback.tasks,
                            baseDate = currentPlanningDay().toString(),
                            requestedWindowStart = fallback.requestedWindowStart,
                            requestedWindowEnd = fallback.requestedWindowEnd
                        )
                    } else null
                    state.copy(
                        messages = state.messages + AssistantConversationMessage(fromUser = false, text = fallback.summary),
                        draftBatches = batch?.let { state.draftBatches + it } ?: state.draftBatches,
                        loading = false,
                        error = if (fallback.isPlanning) "助手发生错误，已回退本地草案：${error.message ?: "未知错误"}" else "助手发生错误：${error.message ?: "未知错误"}",
                        modelName = "本地规则"
                    )
                }
            }
        }
    }

    fun toggleAssistantSuggestion(batchId: String, suggestionId: String) = updateAssistantState { state ->
        state.copy(draftBatches = state.draftBatches.map { batch ->
            if (batch.id == batchId) batch.copy(tasks = batch.tasks.map { if (it.id == suggestionId) it.copy(selected = !it.selected) else it }) else batch
        })
    }

    fun updateAssistantSuggestion(batchId: String, suggestion: AssistantTaskSuggestion) = updateAssistantState { state ->
        state.copy(draftBatches = state.draftBatches.map { batch ->
            if (batch.id == batchId) batch.copy(tasks = batch.tasks.map { if (it.id == suggestion.id) suggestion else it }) else batch
        })
    }

    fun deleteAssistantSuggestion(batchId: String, suggestionId: String) = updateAssistantState { state ->
        state.copy(draftBatches = state.draftBatches.mapNotNull { batch ->
            if (batch.id != batchId) batch else batch.copy(tasks = batch.tasks.filterNot { it.id == suggestionId }).takeIf { it.tasks.isNotEmpty() }
        })
    }

    fun deleteAssistantBatch(batchId: String) = updateAssistantState { state ->
        state.copy(draftBatches = state.draftBatches.filterNot { it.id == batchId })
    }

    fun createAssistantSuggestions(batchId: String, schedule: Boolean, onResult: (String) -> Unit) = viewModelScope.launch {
        val batch = _assistantUiState.value.draftBatches.firstOrNull { it.id == batchId }
        val selected = batch?.tasks.orEmpty().filter { it.selected }
        if (selected.isEmpty()) { onResult("请先选择至少一个任务草案"); return@launch }
        runCatching {
            val (created, scheduled) = container.database.withTransaction {
                val created = selected.map { suggestion ->
                    val label = normalizeTaskLabel(suggestion.label)
                    if (label != "未分类") container.labels.insert(TaskLabelEntity(label))
                    val task = TaskEntity(title = suggestion.title, subject = label, priority = suggestion.priority.rank, estimatedMinutes = suggestion.minutes)
                    suggestion to task.copy(id = container.tasks.insert(task))
                }
                var scheduled = 0
                if (schedule) {
                    val baseDate = runCatching { LocalDate.parse(batch?.baseDate) }.getOrDefault(currentPlanningDay())
                    created.groupBy { it.first.dayOffset }.toSortedMap().forEach { (dayOffset, entries) ->
                        scheduled += scheduleAssistantTasks(
                            entries.map { it.second },
                            batch?.requestedWindowStart,
                            batch?.requestedWindowEnd,
                            baseDate.plusDays(dayOffset.toLong())
                        )
                    }
                }
                created to scheduled
            }
            updateAssistantState { state ->
                state.copy(
                    draftBatches = state.draftBatches.mapNotNull { currentBatch ->
                        if (currentBatch.id != batchId) currentBatch else currentBatch.copy(tasks = currentBatch.tasks.filterNot { draft -> draft.id in selected.map { it.id }.toSet() }).takeIf { it.tasks.isNotEmpty() }
                    },
                    messages = state.messages + AssistantConversationMessage(fromUser = false, text = if (schedule) "已创建 ${created.size} 项任务，其中 $scheduled 项已按分天建议加入时间轴。" else "已创建 ${created.size} 项任务。")
                )
            }
            if (schedule && scheduled < created.size) "已创建 ${created.size} 项，排入 $scheduled 项；其余因时间不足保留在任务池" else if (schedule) "已创建并排入 $scheduled 项" else "已创建 ${created.size} 项任务"
        }.onSuccess(onResult).onFailure { onResult("操作失败，未继续修改时间轴：${it.message ?: "未知错误"}") }
    }

    fun scheduleExistingTasks(taskIds: Set<Long>, onResult: (String) -> Unit) = viewModelScope.launch {
        if (taskIds.isEmpty()) { onResult("请先选择已有任务"); return@launch }
        runCatching {
            val scheduledIds = container.schedule.getScheduledTaskIds().toSet()
            val selected = tasks.value.filter { it.id in taskIds && !it.completed && it.id !in scheduledIds }
            val count = scheduleAssistantTasks(selected)
            if (count < selected.size) "已排入 $count 项；${selected.size - count} 项因时间不足保留在任务池" else "已将 $count 项已有任务加入时间轴"
        }.onSuccess(onResult).onFailure { onResult("排程失败，原时间轴未被移动：${it.message ?: "未知错误"}") }
    }

    private suspend fun scheduleAssistantTasks(
        selected: List<TaskEntity>,
        requestedStart: Int? = null,
        requestedEnd: Int? = null,
        planningDay: LocalDate = currentPlanningDay()
    ): Int {
        if (selected.isEmpty()) return 0
        val preset = _assistantPreset.value
        val windowStart = normalizePlanningMinute(requestedStart ?: preset.windowStartMinute)
            .coerceIn(PLANNING_DAY_START_MINUTE, PLANNING_DAY_END_MINUTE - 10)
        val windowEnd = normalizePlanningMinute(requestedEnd ?: preset.windowEndMinute)
            .coerceIn(windowStart + 10, PLANNING_DAY_END_MINUTE)
        val day = planningDay
        val zone = ZoneId.systemDefault()
        val start = minuteAt(day, windowStart, zone)
        val end = minuteAt(day, windowEnd, zone)
        val existing = container.schedule.getBetween(start, end)
        val scheduledIds = container.schedule.getScheduledTaskIds().toSet()
        val eligible = selected.filter { it.id !in scheduledIds && !it.completed }
        val earliest = if (day == currentPlanningDay()) currentPlanningMinute().coerceAtLeast(windowStart) else windowStart
        val drafts = Planner.planAvailableInWindow(
            eligible,
            existing,
            day,
            windowStart,
            windowEnd,
            preset.excludedTimes.filter { it.enabled }.map { it.startMinute to it.endMinute },
            earliest
        )
        container.schedule.insertAll(drafts.map { ScheduleBlockEntity(taskId = it.taskId, title = it.title, startAt = it.startAt, endAt = it.endAt, priority = it.priority.rank) })
        return drafts.size
    }

    private fun updateAssistantState(transform: (AssistantUiState) -> AssistantUiState) {
        _assistantUiState.update(transform)
        val state = _assistantUiState.value
        container.assistantPreferences.saveWorkspace(AssistantWorkspace(state.messages, state.draftBatches))
    }

    private fun currentPlanningDay(): LocalDate = if (LocalTime.now().isBefore(LocalTime.of(2, 0))) LocalDate.now().minusDays(1) else LocalDate.now()
    private fun currentPlanningMinute(): Int {
        val now = ZonedDateTime.now()
        val minute = now.hour * 60 + now.minute
        return if (now.toLocalTime().isBefore(LocalTime.of(2, 0))) minute + 24 * 60 else minute
    }
    private fun minuteAt(day: LocalDate, minute: Int, zone: ZoneId): Long {
        val dayOffset = Math.floorDiv(minute, 24 * 60)
        val clock = Math.floorMod(minute, 24 * 60)
        return day.plusDays(dayOffset.toLong()).atTime(clock / 60, clock % 60).atZone(zone).toInstant().toEpochMilli()
    }

    fun addTask(title: String, priority: Priority, minutes: Int = 50, type: String = "") {
        if (title.isBlank()) return
        viewModelScope.launch {
            val label = normalizeTaskLabel(type)
            if (label != "未分类") container.labels.insert(TaskLabelEntity(label))
            container.tasks.insert(TaskEntity(title = title.trim(), subject = label, priority = priority.rank, estimatedMinutes = minutes.coerceIn(10, 480)))
        }
    }
    fun toggle(task: TaskEntity) = viewModelScope.launch { container.tasks.update(task.copy(completed = !task.completed)) }
    fun saveTask(existing: TaskEntity?, title: String, type: String, priority: Priority, minutes: Int) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val label = normalizeTaskLabel(type)
            if (label != "未分类") container.labels.insert(TaskLabelEntity(label))
            if (existing == null) {
                container.tasks.insert(TaskEntity(title = title.trim(), subject = label, priority = priority.rank, estimatedMinutes = minutes.coerceIn(10, 480)))
            } else {
                val updated = existing.copy(title = title.trim(), subject = label, priority = priority.rank, estimatedMinutes = minutes.coerceIn(10, 480))
                container.tasks.update(updated)
                container.schedule.updateTaskDetails(updated.id, updated.title, updated.priority, updated.estimatedMinutes * 60_000L)
            }
        }
    }
    fun deleteLabel(label: String) = viewModelScope.launch {
        if (label == "未分类") return@launch
        container.tasks.moveLabelToUncategorized(label)
        container.labels.deleteByName(label)
    }
    fun deleteTask(task: TaskEntity) = viewModelScope.launch {
        container.schedule.deleteByTaskId(task.id)
        container.tasks.delete(task)
    }
    fun scheduleDay(day: LocalDate, onResult: (String) -> Unit = {}) = viewModelScope.launch {
        val zone = ZoneId.systemDefault()
        val start = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
        val scheduledIds = container.schedule.getScheduledTaskIds().toSet()
        val eligible = tasks.value.filter { !it.completed && it.id !in scheduledIds }
        val drafts = Planner.planAvailable(eligible, container.schedule.getBetween(start, end), day)
        container.schedule.insertAll(drafts.map { ScheduleBlockEntity(taskId = it.taskId, title = it.title, startAt = it.startAt, endAt = it.endAt, priority = it.priority.rank) })
        onResult(if (drafts.isEmpty()) "没有可自动安排的待办任务" else "已安排 ${drafts.size} 项，原有时间块保持不变")
    }
    fun resetDay(day: LocalDate, onResult: (String) -> Unit = {}) = viewModelScope.launch {
        val zone = ZoneId.systemDefault()
        val start = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
        val count = container.schedule.clearIncompleteTaskBlocksBetween(start, end)
        onResult(if (count == 0) "本天没有可回收的未完成任务" else "已将本天 $count 项未完成任务退回待安排")
    }
    fun placeTask(task: TaskEntity, startAt: Long, latestEnd: Long, onResult: (String?) -> Unit) = viewModelScope.launch {
        val endAt = startAt + task.estimatedMinutes * 60_000L
        val startClock = Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).toLocalTime()
        val startMinute = normalizePlanningMinute(startClock.hour * 60 + startClock.minute)
        val endMinute = startMinute + task.estimatedMinutes
        val exclusion = _assistantPreset.value.excludedTimes.firstOrNull {
            it.enabled && startMinute < it.endMinute && endMinute > it.startMinute
        }
        when {
            container.schedule.countByTaskId(task.id) > 0 -> onResult("该任务已经排入时间轴")
            endAt > latestEnd -> onResult("任务会超过次日 02:00，请选择更早的时间")
            exclusion != null -> onResult("该时间与排除时段“${exclusion.label}”重叠")
            container.schedule.countOverlapping(startAt, endAt) > 0 -> onResult("这个时间段已有安排")
            else -> {
                container.schedule.insert(ScheduleBlockEntity(taskId = task.id, title = task.title, startAt = startAt, endAt = endAt, priority = task.priority))
                onResult(null)
            }
        }
    }
    fun unschedule(block: ScheduleBlockEntity) = viewModelScope.launch { container.schedule.delete(block) }
    fun saveModel(existing: ModelProfileEntity?, name: String, baseUrl: String, modelId: String, apiKey: String) {
        if (name.isBlank() || baseUrl.isBlank() || modelId.isBlank()) return
        viewModelScope.launch {
            val alias = existing?.apiKeyAlias?.takeIf { it.isNotBlank() } ?: "model_${System.currentTimeMillis()}"
            container.apiKeys.save(alias, apiKey)
            container.models.upsert(
                existing?.copy(name = name.trim(), baseUrl = baseUrl.trim(), modelId = modelId.trim(), apiKeyAlias = alias, role = "经济")
                    ?: ModelProfileEntity(name = name.trim(), baseUrl = baseUrl.trim(), modelId = modelId.trim(), apiKeyAlias = alias, role = "经济")
            )
        }
    }
    fun loadAvailableModels(baseUrl: String, apiKey: String, existingAlias: String?, onResult: (Result<List<String>>) -> Unit) {
        viewModelScope.launch {
            val secret = apiKey.ifBlank { existingAlias?.let(container.apiKeys::read).orEmpty() }
            onResult(catalog.fetch(baseUrl, secret))
        }
    }
    fun setModelEnabled(profile: ModelProfileEntity, enabled: Boolean) = viewModelScope.launch {
        container.models.upsert(profile.copy(enabled = enabled, role = "经济"))
    }
    fun askAssistant(prompt: String, onReply: (String) -> Unit) = viewModelScope.launch {
        onReply(router.complete(prompt, models.value).text)
    }
}
