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
import java.time.temporal.ChronoUnit
import java.util.UUID

data class TaskScreenState(
    val page: Int = 0,
    val grouping: Int = 0,
    val collapsedGroups: Set<String> = emptySet()
)

data class AssistantUiState(
    val messages: List<AssistantConversationMessage> = emptyList(),
    val draftBatches: List<AssistantDraftBatch> = emptyList(),
    val memoryResetAt: Long = 0L,
    val loading: Boolean = false,
    val scheduling: Boolean = false,
    val scheduleStatus: String? = null,
    val error: String? = null,
    val modelName: String? = null,
    val schedulePreview: SchedulePreviewState? = null
) {
    val suggestionCount: Int get() = draftBatches.sumOf { it.tasks.size }
}

enum class SchedulePreviewKind { DRAFTS, EXISTING, RESCHEDULE }

data class SchedulePreviewState(
    val kind: SchedulePreviewKind,
    val title: String,
    val request: SchedulingRequest,
    val proposal: AgentScheduleProposal,
    val modelName: String,
    val batchId: String? = null,
    val selectedSuggestionIds: Set<String> = emptySet(),
    val selectedTaskIds: Set<Long> = emptySet(),
    val excludedTaskIds: Set<Long> = emptySet(),
    val taskEdit: PendingTaskEdit? = null
)

data class PendingTaskEdit(
    val rootTaskId: Long,
    val sourceTaskId: Long,
    val title: String,
    val detail: String,
    val label: String,
    val priority: Priority,
    val totalMinutes: Int,
    val plannedDayEpoch: Long?
)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private class NoSchedulableDraftsException(message: String) : IllegalStateException(message)

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
    private val initialAssistantPreset = container.assistantPreferences.load()
    private val _assistantPreset = MutableStateFlow(initialAssistantPreset)
    val assistantPreset = _assistantPreset.asStateFlow()
    private val _assistantPresetHistory = MutableStateFlow(container.assistantPreferences.loadPresetHistory(initialAssistantPreset))
    val assistantPresetHistory = _assistantPresetHistory.asStateFlow()
    private val initialAssistantWorkspace = container.assistantPreferences.loadWorkspace()
    private val _assistantUiState = MutableStateFlow(
        AssistantUiState(
            messages = initialAssistantWorkspace.messages,
            draftBatches = initialAssistantWorkspace.draftBatches,
            memoryResetAt = initialAssistantWorkspace.memoryResetAt
        )
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
        val effectiveDay = currentPlanningDay().toEpochDay()
        val updatedHistory = (
            _assistantPresetHistory.value.filter { it.effectiveDayEpoch < effectiveDay } +
                AssistantPresetRevision(effectiveDay, normalized)
            ).sortedBy { it.effectiveDayEpoch }
        _assistantPreset.value = normalized
        _assistantPresetHistory.value = updatedHistory
        container.assistantPreferences.save(normalized, updatedHistory)
    }

    fun requestAssistantPlan(input: String) {
        val request = input.trim()
        if (request.isBlank() || _assistantUiState.value.loading) return
        val planningRequest = AssistantPlanParser.isPlanningRequest(request)
        val memoryResetAt = _assistantUiState.value.memoryResetAt
        val conversationHistory = _assistantUiState.value.messages
            .filter { it.createdAt >= memoryResetAt }
            .takeLast(6)
            .map { message ->
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
            var planningDay = currentPlanningDay()
            var timelineBlocks: List<ScheduleBlockEntity> = emptyList()
            var timelineLoadByDay: Map<Int, Int> = emptyMap()
            val result = runCatching {
                planningDay = currentPlanningDay()
                timelineBlocks = scheduleBlocksForHorizon(planningDay, 7)
                val actualProfiles = container.models.observeAll().first()
                val actualTasks = container.tasks.observeAll().first()
                val actualLabels = container.labels.observeAll().first().map { it.name }
                val timeline = timelineContext(
                    timelineBlocks,
                    actualTasks,
                    planningDay,
                    _assistantPreset.value
                )
                timelineLoadByDay = timelineBlocks
                    .groupingBy { planningDayOffset(it.startAt, planningDay) }
                    .fold(0) { total, block -> total + ((block.endAt - block.startAt).coerceAtLeast(0L) / 60_000L).toInt() }
                val prompt = if (planningRequest) {
                    AssistantPlanParser.prompt(
                        request,
                        _assistantPreset.value,
                        actualTasks.filterNot { it.completed }.map { it.title },
                        planningDay,
                        conversationHistory,
                        _assistantUiState.value.draftBatches.takeLast(3).flatMap { batch -> batch.tasks.map { "${it.title}：${it.detail}" } },
                        timeline,
                        existingLabels = actualLabels
                    )
                } else AssistantPlanParser.chatPrompt(request, conversationHistory, timeline)
                val reply = router.complete(
                    prompt = prompt,
                    profiles = actualProfiles,
                    jsonMode = planningRequest,
                    systemPrompt = GPT_GIRL_SYSTEM_PROMPT,
                    options = if (planningRequest) {
                        ModelRequestOptions(
                            maxTokens = 2_048,
                            totalTimeoutMillis = 60_000,
                            maxCandidates = 2,
                            preferFastModel = true,
                            disableThinking = true
                        )
                    } else {
                        ModelRequestOptions()
                    }
                )
                val resolved = if (reply.localFallback) Triple(AssistantPlanParser.fallback(request), reply, true)
                else if (!planningRequest) Triple(AssistantPlan(reply.text.trim(), emptyList(), isPlanning = false), reply, false)
                else {
                    val parsed = runCatching { AssistantPlanParser.parse(reply.text) }
                    parsed.fold(
                        onSuccess = { Triple(it, reply, false) },
                        onFailure = { Triple(AssistantPlanParser.fallback(request), reply.copy(text = "模型返回格式无法解析：${it.message}"), true) }
                    )
                }
                val normalizedResolved = if (planningRequest) {
                    resolved.first.copy(tasks = resolved.first.tasks.map { suggestion ->
                        suggestion.copy(label = reuseExistingLabel(suggestion.label, actualLabels))
                    })
                } else resolved.first
                val resolvedWithLabels = resolved.copy(first = normalizedResolved)
                val forceSingleDay = listOf("今天完成", "今天做完", "今天全部", "今天内", "一天内").any(request::contains)
                val distributed = AssistantPlanParser.distributeAcrossDays(
                    resolvedWithLabels.first,
                    effectiveAssistantPreset(resolvedWithLabels.first),
                    forceSingleDay,
                    timelineLoadByDay
                )
                resolvedWithLabels.copy(first = keepSchedulableDrafts(alignAssistantPlan(distributed, planningDay, timelineBlocks)))
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
                            baseDate = planningDay.toString(),
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
                val distributedFallback = AssistantPlanParser.distributeAcrossDays(
                    rawFallback,
                    effectiveAssistantPreset(rawFallback),
                    forceSingleDay,
                    occupiedMinutesByDay = timelineLoadByDay
                )
                val fallback = keepSchedulableDrafts(alignAssistantPlan(distributedFallback, planningDay, timelineBlocks))
                updateAssistantState { state ->
                    val batch = if (fallback.isPlanning && fallback.tasks.isNotEmpty()) {
                        AssistantDraftBatch(
                            sourcePrompt = request,
                            summary = fallback.summary,
                            modelName = "本地规则",
                            tasks = fallback.tasks,
                            baseDate = planningDay.toString(),
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

    fun setAssistantBatchSelection(batchId: String, selected: Boolean) = updateAssistantState { state ->
        state.copy(draftBatches = state.draftBatches.map { batch ->
            if (batch.id == batchId) batch.copy(tasks = batch.tasks.map { it.copy(selected = selected) }) else batch
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

    fun deleteSelectedAssistantSuggestions(batchId: String) = updateAssistantState { state ->
        state.copy(draftBatches = state.draftBatches.mapNotNull { batch ->
            if (batch.id != batchId) batch else batch.copy(tasks = batch.tasks.filterNot { it.selected }).takeIf { it.tasks.isNotEmpty() }
        })
    }

    fun deleteAssistantBatch(batchId: String) = updateAssistantState { state ->
        state.copy(draftBatches = state.draftBatches.filterNot { it.id == batchId })
    }

    fun createAssistantSuggestions(batchId: String, schedule: Boolean = false, onResult: (String) -> Unit) = viewModelScope.launch {
        if (schedule) {
            requestDraftScheduleInternal(batchId, onResult)
            return@launch
        }
        val batch = _assistantUiState.value.draftBatches.firstOrNull { it.id == batchId }
        val selected = batch?.tasks.orEmpty().filter { it.selected }
        if (selected.isEmpty()) { onResult("请先选择至少一个任务草案"); return@launch }
        runCatching {
            val existingLabels = container.labels.observeAll().first().map { it.name }.toSet()
            val selectedLabels = selected.map { normalizeTaskLabel(it.label) }.filter { it != "未分类" }.distinct()
            val newLabels = selectedLabels.filterNot { label -> existingLabels.any { it.equals(label, ignoreCase = true) } }
            val reusedLabels = selectedLabels.filter { label -> existingLabels.any { it.equals(label, ignoreCase = true) } }
            val created = container.database.withTransaction {
                val baseDate = runCatching { LocalDate.parse(batch?.baseDate) }.getOrDefault(currentPlanningDay())
                selected.map { suggestion ->
                    val label = normalizeTaskLabel(suggestion.label)
                    if (label != "未分类" && newLabels.any { it.equals(label, ignoreCase = true) }) container.labels.insert(TaskLabelEntity(label))
                    container.tasks.insert(
                        TaskEntity(
                            title = suggestion.title,
                            detail = assistantTaskDetail(suggestion),
                            subject = label,
                            priority = suggestion.priority.rank,
                            estimatedMinutes = roundTaskMinutes(suggestion.minutes),
                            plannedDayEpoch = suggestion.dayOffset?.let { baseDate.plusDays(it.toLong()).toEpochDay() }
                        )
                    )
                }
            }
            val resultMessage = buildString {
                append("已创建 ${created.size} 项任务")
                if (reusedLabels.isNotEmpty()) append("；复用已有标签：${reusedLabels.joinToString("、")}")
                if (newLabels.isNotEmpty()) append("；新建标签：${newLabels.joinToString("、")}，请确认")
            }
            updateAssistantState { state ->
                state.copy(
                    draftBatches = state.draftBatches.mapNotNull { currentBatch ->
                        if (currentBatch.id != batchId) currentBatch else currentBatch.copy(tasks = currentBatch.tasks.filterNot { draft -> draft.id in selected.map { it.id }.toSet() }).takeIf { it.tasks.isNotEmpty() }
                    },
                    messages = state.messages + AssistantConversationMessage(fromUser = false, text = "哼，$resultMessage。接下来可别只看着它们发呆。")
                )
            }
            resultMessage
        }.onSuccess(onResult).onFailure { onResult("操作失败，未继续修改时间轴：${it.message ?: "未知错误"}") }
    }

    fun requestDraftSchedule(batchId: String, onResult: (String) -> Unit) = viewModelScope.launch {
        requestDraftScheduleInternal(batchId, onResult)
    }

    private suspend fun requestDraftScheduleInternal(batchId: String, onResult: (String) -> Unit) {
        val batch = _assistantUiState.value.draftBatches.firstOrNull { it.id == batchId }
        val selected = batch?.tasks.orEmpty().filter { it.selected }
        if (batch == null || selected.isEmpty()) { onResult("请先选择至少一个任务草案"); return }
        val baseDate = runCatching { LocalDate.parse(batch.baseDate) }.getOrDefault(currentPlanningDay())
        val schedulingTasks = selected.map { suggestion ->
            SchedulingTask(
                key = draftTaskKey(batch.id, suggestion.id),
                title = suggestion.title,
                detail = suggestion.detail,
                label = normalizeTaskLabel(suggestion.label),
                priority = suggestion.priority,
                minutes = roundTaskMinutes(suggestion.minutes),
                preferredDay = suggestion.dayOffset?.let { baseDate.plusDays(it.toLong()) },
                createdAt = batch.createdAt,
                assistantOrder = suggestion.assistantOrder ?: selected.indexOf(suggestion),
                schedulingHint = suggestion.schedulingHint,
                parentTaskTitle = suggestion.parentTaskTitle,
                preferredStartMinute = suggestion.recommendedStartMinute
            )
        }
        val preset = effectiveAssistantPreset(
            AssistantPlan(
                summary = "",
                tasks = emptyList(),
                requestedWindowStart = batch.requestedWindowStart,
                requestedWindowEnd = batch.requestedWindowEnd
            )
        )
        generateSchedulePreview(
            kind = SchedulePreviewKind.DRAFTS,
            title = "草案智能排程",
            schedulingTasks = schedulingTasks,
            preset = preset,
            batchId = batch.id,
            selectedSuggestionIds = selected.mapTo(mutableSetOf()) { it.id },
            onResult = onResult
        )
    }

    fun scheduleExistingTasks(taskIds: Set<Long>, onResult: (String) -> Unit) = viewModelScope.launch {
        if (taskIds.isEmpty()) { onResult("请先选择已有任务"); return@launch }
        val scheduledIds = container.schedule.getScheduledTaskIds().toSet()
        val selected = container.tasks.observeAll().first().filter { it.id in taskIds }
            .filter { !it.completed && !it.hidden && it.id !in scheduledIds }
            .distinctBy(::familyRootId)
        if (selected.isEmpty()) { onResult("所选任务已排程或不可用"); return@launch }
        generateSchedulePreview(
            kind = SchedulePreviewKind.EXISTING,
            title = "本地任务智能排程",
            schedulingTasks = selected.mapIndexed { index, task -> taskToSchedulingTask(task, index) },
            preset = _assistantPreset.value,
            selectedTaskIds = selected.mapTo(mutableSetOf()) { it.id },
            onResult = onResult
        )
    }

    private suspend fun generateSchedulePreview(
        kind: SchedulePreviewKind,
        title: String,
        schedulingTasks: List<SchedulingTask>,
        preset: AssistantPreset,
        batchId: String? = null,
        selectedSuggestionIds: Set<String> = emptySet(),
        selectedTaskIds: Set<Long> = emptySet(),
        excludedTaskIds: Set<Long> = emptySet(),
        taskEdit: PendingTaskEdit? = null,
        onResult: (String) -> Unit = {}
    ) {
        if (_assistantUiState.value.scheduling) { onResult("智能体正在排程，请稍等"); return }
        updateAssistantState {
            it.copy(
                scheduling = true,
                scheduleStatus = "正在读取任务、时间轴空档与排程预设…",
                error = null,
                schedulePreview = null
            )
        }
        val startDay = currentPlanningDay()
        val request = SchedulingRequest(
            tasks = schedulingTasks,
            existingBlocks = scheduleBlocksForHorizon(startDay, 7).filterNot { it.taskId in excludedTaskIds },
            preset = preset,
            startDay = startDay,
            dayCount = 7,
            earliestMinuteToday = currentPlanningMinute().coerceAtLeast(preset.windowStartMinute)
        )
        runCatching {
            val profiles = container.models.observeAll().first()
            val primaryModel = profiles.filter { it.enabled }.minByOrNull { it.id }
            updateAssistantState {
                it.copy(
                    scheduleStatus = primaryModel?.let { profile -> "正在请 ${profile.name} 给出任务顺序，最长等待45秒…" }
                        ?: "未启用模型，正在使用本地规则排序…"
                )
            }
            val reply = router.complete(
                prompt = AgentSchedulePlanner.prompt(request),
                profiles = profiles,
                jsonMode = true,
                systemPrompt = GPT_GIRL_SYSTEM_PROMPT,
                options = ModelRequestOptions(
                    maxTokens = 1_536,
                    totalTimeoutMillis = 45_000,
                    maxCandidates = 2,
                    preferFastModel = true,
                    disableThinking = true
                )
            )
            updateAssistantState {
                it.copy(
                    scheduleStatus = if (reply.localFallback) "模型未及时返回，正在使用本地顺序和空档规则…" else "模型顺序已返回，正在校验真实空档与任务时长…"
                )
            }
            val proposal = if (reply.localFallback) {
                AgentSchedulePlanner.localPlan(request).copy(validationNote = reply.text)
            } else {
                AgentSchedulePlanner.resolve(reply.text, request)
            }
            val modelName = when {
                reply.localFallback -> "本地规则"
                proposal.usedLocalFallback -> "本地规则（模型结果无法解析，已回退）"
                reply.usedFallback -> "${reply.model}（自动切换）"
                else -> reply.model
            }
            SchedulePreviewState(
                kind = kind,
                title = title,
                request = request,
                proposal = proposal,
                modelName = modelName,
                batchId = batchId,
                selectedSuggestionIds = selectedSuggestionIds,
                selectedTaskIds = selectedTaskIds,
                excludedTaskIds = excludedTaskIds,
                taskEdit = taskEdit
            )
        }.onSuccess { preview ->
            updateAssistantState { it.copy(scheduling = false, scheduleStatus = null, schedulePreview = preview, modelName = preview.modelName) }
            onResult("排程预览已生成，请检查后确认")
        }.onFailure { error ->
            val local = AgentSchedulePlanner.localPlan(request).copy(validationNote = error.message ?: "本地排程失败")
            val preview = SchedulePreviewState(
                kind = kind,
                title = title,
                request = request,
                proposal = local,
                modelName = "本地规则",
                batchId = batchId,
                selectedSuggestionIds = selectedSuggestionIds,
                selectedTaskIds = selectedTaskIds,
                excludedTaskIds = excludedTaskIds,
                taskEdit = taskEdit
            )
            updateAssistantState { it.copy(scheduling = false, scheduleStatus = null, schedulePreview = preview, error = "本地排程出现异常，已生成可校验预览：${error.message ?: "未知错误"}") }
            onResult("本地排程出现异常，已生成预览")
        }
    }

    fun dismissSchedulePreview() = updateAssistantState { it.copy(schedulePreview = null) }

    fun applySchedulePreview(onResult: (String) -> Unit) = viewModelScope.launch {
        if (_assistantUiState.value.scheduling) {
            onResult("正在写入排程，请稍等")
            return@launch
        }
        val preview = _assistantUiState.value.schedulePreview ?: return@launch
        updateAssistantState { it.copy(scheduling = true, scheduleStatus = "正在校验预览并写入时间轴…", error = null) }
        runCatching {
            val freshRequest = preview.request.copy(
                existingBlocks = scheduleBlocksForHorizon(preview.request.startDay, preview.request.dayCount)
                    .filterNot { it.taskId in preview.excludedTaskIds }
            )
            AgentSchedulePlanner.validateProposal(preview.proposal, freshRequest)?.let { problem ->
                throw IllegalStateException("预览已过期：$problem")
            }
            when (preview.kind) {
                SchedulePreviewKind.DRAFTS -> applyDraftPreview(preview)
                SchedulePreviewKind.EXISTING, SchedulePreviewKind.RESCHEDULE -> applyExistingPreview(preview)
            }
        }.onSuccess { result ->
            updateAssistantState { state ->
                state.copy(
                    schedulePreview = null,
                    scheduling = false,
                    scheduleStatus = null,
                    messages = state.messages + AssistantConversationMessage(fromUser = false, text = "哼，排程已经写进时间轴了。$result")
                )
            }
            onResult(result)
        }.onFailure { error ->
            val message = error.message ?: "未知错误"
            val userMessage = if (error is NoSchedulableDraftsException) message else "应用失败，时间轴未修改：$message"
            updateAssistantState { it.copy(scheduling = false, scheduleStatus = null, error = userMessage) }
            onResult(userMessage)
        }
    }

    private suspend fun applyDraftPreview(preview: SchedulePreviewState): String {
        val batch = _assistantUiState.value.draftBatches.firstOrNull { it.id == preview.batchId }
            ?: throw IllegalStateException("草案批次已经不存在")
        val selected = batch.tasks.filter { it.id in preview.selectedSuggestionIds }
        val baseDate = runCatching { LocalDate.parse(batch.baseDate) }.getOrDefault(preview.request.startDay)
        val currentSchedulingTasks = selected.map { suggestion ->
            SchedulingTask(
                key = draftTaskKey(batch.id, suggestion.id),
                title = suggestion.title,
                detail = suggestion.detail,
                label = normalizeTaskLabel(suggestion.label),
                priority = suggestion.priority,
                minutes = roundTaskMinutes(suggestion.minutes),
                preferredDay = suggestion.dayOffset?.let { baseDate.plusDays(it.toLong()) },
                createdAt = batch.createdAt,
                assistantOrder = suggestion.assistantOrder ?: selected.indexOf(suggestion),
                schedulingHint = suggestion.schedulingHint,
                parentTaskTitle = suggestion.parentTaskTitle,
                preferredStartMinute = suggestion.recommendedStartMinute
            )
        }
        if (currentSchedulingTasks != preview.request.tasks) throw IllegalStateException("草案在预览后发生变化，请重新排程")
        val schedulable = selected.filter { suggestion ->
            val key = draftTaskKey(batch.id, suggestion.id)
            preview.proposal.blocks.any { it.taskKey == key }
        }
        if (schedulable.isEmpty()) {
            throw NoSchedulableDraftsException("当前预览没有可写入的完整空档；草案仍保留，请调整排程预设、任务时长或已有时间轴后重试。")
        }
        val existingLabels = container.labels.observeAll().first().map { it.name }.toSet()
        val selectedLabels = schedulable.map { normalizeTaskLabel(it.label) }.filter { it != "未分类" }.distinct()
        val newLabels = selectedLabels.filterNot { label -> existingLabels.any { it.equals(label, ignoreCase = true) } }
        val reusedLabels = selectedLabels.filter { label -> existingLabels.any { it.equals(label, ignoreCase = true) } }
        var scheduledBlocks = 0
        container.database.withTransaction {
            schedulable.forEach { suggestion ->
                val label = normalizeTaskLabel(suggestion.label)
                if (label != "未分类" && newLabels.any { it.equals(label, ignoreCase = true) }) container.labels.insert(TaskLabelEntity(label))
                val key = draftTaskKey(batch.id, suggestion.id)
                val root = TaskEntity(
                    title = suggestion.title,
                    detail = assistantTaskDetail(suggestion),
                    subject = label,
                    priority = suggestion.priority.rank,
                    estimatedMinutes = roundTaskMinutes(suggestion.minutes),
                    plannedDayEpoch = suggestion.dayOffset?.let { baseDate.plusDays(it.toLong()).toEpochDay() }
                )
                scheduledBlocks += insertNewTaskWithProposal(root, key, preview)
            }
        }
        updateAssistantState { state ->
            state.copy(draftBatches = state.draftBatches.mapNotNull { current ->
                if (current.id != batch.id) current
                else current.copy(tasks = current.tasks.filterNot { draft -> schedulable.any { it.id == draft.id } }).takeIf { it.tasks.isNotEmpty() }
            })
        }
        val pendingCount = selected.size - schedulable.size
        return buildString {
            append("已创建 ${schedulable.size} 项任务并写入 $scheduledBlocks 个时间块；${pendingCount} 项因当前没有完整空档，仍保留在草案中")
            if (reusedLabels.isNotEmpty()) append("；复用已有标签：${reusedLabels.joinToString("、")}")
            if (newLabels.isNotEmpty()) append("；新建标签：${newLabels.joinToString("、")}，请确认")
        }
    }

    private suspend fun applyExistingPreview(preview: SchedulePreviewState): String {
        preview.taskEdit?.let { edit ->
            val source = container.tasks.getById(edit.sourceTaskId)
                ?: container.tasks.getById(edit.rootTaskId)
                ?: throw IllegalStateException("任务已经不存在")
            val expected = taskToSchedulingTask(source, 0).copy(
                title = edit.title,
                detail = edit.detail,
                label = edit.label,
                priority = edit.priority,
                minutes = remainingMinutesForEdit(source, edit.totalMinutes),
                preferredDay = edit.plannedDayEpoch?.let(LocalDate::ofEpochDay)
            )
            if (preview.request.tasks.singleOrNull() != expected) {
                throw IllegalStateException("任务在预览后发生变化，请重新排程")
            }
            val blocks = preview.proposal.blocks.filter { it.taskKey == expected.key }
            var scheduledBlocks = 0
            container.database.withTransaction {
                if (edit.label != "未分类") container.labels.insert(TaskLabelEntity(edit.label))
                scheduledBlocks = replaceTaskFamilySchedule(source, blocks, preview.request.startDay, edit)
            }
            return "任务修改已保存，并写入 $scheduledBlocks 个时间块；未排入时会保留在待办区。"
        }
        val current = preview.selectedTaskIds.mapNotNull { container.tasks.getById(it) }
            .distinctBy(::familyRootId)
        val currentByKey = current.associateBy(::existingTaskKey)
        val currentScheduling = preview.request.tasks.mapIndexedNotNull { index, requested ->
            currentByKey[requested.key]?.let { task -> taskToSchedulingTask(task, requested.assistantOrder ?: index) }
        }
        if (current.size != preview.selectedTaskIds.size || currentScheduling != preview.request.tasks) {
            throw IllegalStateException("任务在预览后发生变化，请重新排程")
        }
        var scheduledBlocks = 0
        container.database.withTransaction {
            current.forEach { task ->
                val blocks = preview.proposal.blocks.filter { it.taskKey == existingTaskKey(task) }
                if (blocks.isNotEmpty()) scheduledBlocks += replaceTaskFamilySchedule(task, blocks, preview.request.startDay)
            }
        }
        return "已写入 $scheduledBlocks 个时间块；${preview.proposal.unscheduled.size} 项因容量不足保持待办。"
    }

    private suspend fun insertNewTaskWithProposal(rootTemplate: TaskEntity, taskKey: String, preview: SchedulePreviewState): Int {
        val blocks = preview.proposal.blocks.filter { it.taskKey == taskKey }.sortedBy { it.partIndex }
        if (blocks.size <= 1) {
            val plannedDay = blocks.singleOrNull()?.let { preview.request.startDay.plusDays(it.dayOffset.toLong()).toEpochDay() }
            val root = rootTemplate.copy(plannedDayEpoch = plannedDay ?: rootTemplate.plannedDayEpoch)
            val rootId = container.tasks.insert(root)
            blocks.singleOrNull()?.let { insertScheduleBlock(root.copy(id = rootId), it, preview.request.startDay) }
            return blocks.size
        }
        val groupId = UUID.randomUUID().toString()
        val total = rootTemplate.estimatedMinutes
        val rootId = container.tasks.insert(rootTemplate.copy(hidden = true, originalMinutes = total))
        blocks.forEach { proposed ->
            val child = rootTemplate.copy(
                id = 0,
                estimatedMinutes = proposed.minutes,
                plannedDayEpoch = preview.request.startDay.plusDays(proposed.dayOffset.toLong()).toEpochDay(),
                parentTaskId = rootId,
                splitGroupId = groupId,
                splitIndex = proposed.partIndex,
                splitCount = proposed.partCount,
                hidden = false,
                originalMinutes = total,
                createdAt = rootTemplate.createdAt + proposed.partIndex
            )
            val childId = container.tasks.insert(child)
            insertScheduleBlock(child.copy(id = childId), proposed, preview.request.startDay)
        }
        return blocks.size
    }

    private suspend fun replaceTaskFamilySchedule(
        source: TaskEntity,
        blocks: List<ProposedScheduleBlock>,
        startDay: LocalDate,
        edit: PendingTaskEdit? = null
    ): Int {
        val rootId = familyRootId(source)
        val family = container.tasks.getFamily(rootId).ifEmpty { listOf(source) }
        val root = family.firstOrNull { it.id == rootId } ?: source
        val children = family.filter { it.parentTaskId == rootId }
        val isSplitFamily = root.hidden || children.isNotEmpty()
        val completedChildren = children.filter { it.completed }
        val incompleteChildren = children.filterNot { it.completed }
        val completedMinutes = completedChildren.sumOf { it.estimatedMinutes }
        val totalMinutes = edit?.totalMinutes ?: root.originalMinutes ?: root.estimatedMinutes
        val remainingMinutes = if (isSplitFamily) {
            edit?.let { (it.totalMinutes - completedMinutes).coerceAtLeast(0) }
                ?: incompleteChildren.sumOf { it.estimatedMinutes }
        } else {
            edit?.totalMinutes ?: root.estimatedMinutes
        }
        if (blocks.isNotEmpty() && blocks.sumOf { it.minutes } != remainingMinutes) {
            throw IllegalStateException("排程分块总时长与任务剩余时长不一致")
        }

        val metadataRoot = root.withTaskEdit(edit).copy(
            estimatedMinutes = totalMinutes,
            plannedDayEpoch = edit?.plannedDayEpoch ?: root.plannedDayEpoch,
            originalMinutes = if (isSplitFamily || blocks.size > 1) totalMinutes else null
        )
        if (!isSplitFamily && blocks.size <= 1) {
            container.schedule.deleteByTaskId(root.id)
            val proposed = blocks.singleOrNull()
            val updated = metadataRoot.copy(
                hidden = false,
                completed = false,
                estimatedMinutes = remainingMinutes,
                plannedDayEpoch = proposed?.let { startDay.plusDays(it.dayOffset.toLong()).toEpochDay() }
                    ?: edit?.plannedDayEpoch
                    ?: root.plannedDayEpoch
            )
            container.tasks.update(updated)
            proposed?.let { insertScheduleBlock(updated, it, startDay) }
            return blocks.size
        }

        val incompleteIds = incompleteChildren.map { it.id }
        if (incompleteIds.isNotEmpty()) {
            container.schedule.deleteByTaskIds(incompleteIds)
            container.tasks.deleteByIds(incompleteIds)
        }
        val synchronizedCompleted = completedChildren.map { child -> child.withTaskEdit(edit).copy(originalMinutes = totalMinutes) }
        if (synchronizedCompleted.isNotEmpty()) container.tasks.updateAll(synchronizedCompleted)
        val completedIds = synchronizedCompleted.map { it.id }
        if (completedIds.isNotEmpty()) {
            container.schedule.updateTaskMetadata(completedIds, metadataRoot.title, metadataRoot.priority)
        }
        val hiddenRoot = metadataRoot.copy(
            hidden = true,
            completed = remainingMinutes == 0,
            originalMinutes = totalMinutes
        )
        container.tasks.update(hiddenRoot)
        if (remainingMinutes == 0) return 0

        val groupId = children.firstNotNullOfOrNull { it.splitGroupId } ?: UUID.randomUUID().toString()
        if (blocks.isEmpty()) {
            container.tasks.insert(
                hiddenRoot.copy(
                    id = 0,
                    estimatedMinutes = remainingMinutes,
                    plannedDayEpoch = edit?.plannedDayEpoch,
                    parentTaskId = rootId,
                    splitGroupId = groupId,
                    splitIndex = null,
                    splitCount = null,
                    hidden = false,
                    completed = false,
                    originalMinutes = totalMinutes,
                    createdAt = root.createdAt + completedChildren.size + 1
                )
            )
            return 0
        }
        blocks.sortedBy { it.partIndex }.forEach { proposed ->
            val child = hiddenRoot.copy(
                id = 0,
                estimatedMinutes = proposed.minutes,
                plannedDayEpoch = startDay.plusDays(proposed.dayOffset.toLong()).toEpochDay(),
                parentTaskId = rootId,
                splitGroupId = groupId,
                splitIndex = proposed.partIndex,
                splitCount = proposed.partCount,
                hidden = false,
                completed = false,
                originalMinutes = totalMinutes,
                createdAt = root.createdAt + completedChildren.size + proposed.partIndex
            )
            val childId = container.tasks.insert(child)
            insertScheduleBlock(child.copy(id = childId), proposed, startDay)
        }
        return blocks.size
    }

    private suspend fun insertScheduleBlock(task: TaskEntity, proposed: ProposedScheduleBlock, startDay: LocalDate) {
        val day = startDay.plusDays(proposed.dayOffset.toLong())
        val start = minuteAt(day, proposed.startMinute, ZoneId.systemDefault())
        container.schedule.insert(
            ScheduleBlockEntity(
                taskId = task.id,
                title = task.title,
                startAt = start,
                endAt = start + proposed.minutes * 60_000L,
                priority = task.priority
            )
        )
    }

    private suspend fun scheduleBlocksForHorizon(startDay: LocalDate, dayCount: Int): List<ScheduleBlockEntity> {
        val zone = ZoneId.systemDefault()
        val start = minuteAt(startDay, PLANNING_DAY_START_MINUTE, zone)
        val end = minuteAt(startDay.plusDays((dayCount - 1).toLong()), PLANNING_DAY_END_MINUTE, zone)
        return container.schedule.getBetween(start, end)
    }

    private fun timelineContext(
        blocks: List<ScheduleBlockEntity>,
        tasks: List<TaskEntity>,
        startDay: LocalDate,
        preset: AssistantPreset
    ): List<String> {
        val taskById = tasks.associateBy { it.id }
        val zone = ZoneId.systemDefault()
        val occupied = blocks.sortedBy { it.startAt }.map { block ->
            val start = Instant.ofEpochMilli(block.startAt).atZone(zone)
            val planningDate = if (start.toLocalTime().isBefore(LocalTime.of(2, 0))) {
                start.toLocalDate().minusDays(1)
            } else start.toLocalDate()
            val dayOffset = ChronoUnit.DAYS.between(startDay, planningDate).toInt()
            val startMinute = start.hour * 60 + start.minute + if (start.toLocalTime().isBefore(LocalTime.of(2, 0))) 24 * 60 else 0
            val duration = ((block.endAt - block.startAt).coerceAtLeast(0L) / 60_000L).toInt()
            val task = block.taskId?.let(taskById::get)
            val status = when {
                task == null -> "固定时段"
                task.completed -> "已完成"
                else -> "未完成"
            }
            "第${dayOffset + 1}天 ${AssistantPlanParser.formatMinute(startMinute)}-${AssistantPlanParser.formatMinute(startMinute + duration)} ${block.title}（$status）"
        }
        // Use the exact same 06:00-to-next-day-02:00 grid as the scheduler so
        // chat replies cannot infer that an empty interval is unavailable.
        val availability = AgentSchedulePlanner.availableRuns(
            SchedulingRequest(
                tasks = emptyList(),
                existingBlocks = blocks,
                preset = preset,
                startDay = startDay,
                dayCount = 7,
                earliestMinuteToday = currentPlanningMinute().coerceAtLeast(preset.windowStartMinute)
            ),
            minimumMinutes = 10
        )
        val availableByDay = (0 until 7).map { dayOffset ->
                val runs = availability.filter { it.dayOffset == dayOffset }
                val description = runs.joinToString("、") { run ->
                    "${AssistantPlanParser.formatMinute(run.startMinute)}-${AssistantPlanParser.formatMinute(run.startMinute + run.minutes)}（${run.minutes}分钟）"
                }.ifBlank { "无连续空档" }
                "第${dayOffset + 1}天可用连续空档：$description"
            }
        return occupied + availableByDay
    }

    private fun planningDayOffset(startAt: Long, startDay: LocalDate): Int {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(startAt).atZone(zone)
        val planningDate = if (start.toLocalTime().isBefore(LocalTime.of(2, 0))) {
            start.toLocalDate().minusDays(1)
        } else start.toLocalDate()
        return ChronoUnit.DAYS.between(startDay, planningDate).toInt()
    }

    private fun taskToSchedulingTask(task: TaskEntity, assistantOrder: Int? = null) = SchedulingTask(
        key = existingTaskKey(task),
        title = task.title,
        detail = task.detail,
        label = normalizeTaskLabel(task.subject),
        priority = Priority.fromRank(task.priority),
        minutes = roundTaskMinutes(task.estimatedMinutes),
        preferredDay = task.plannedDayEpoch?.let(LocalDate::ofEpochDay),
        createdAt = task.createdAt,
        assistantOrder = task.detail.detailAssistantOrder() ?: assistantOrder,
        schedulingHint = task.detail.detailSchedulingHint(),
        parentTaskTitle = task.detail.detailParentTask(),
        preferredStartMinute = task.detail.detailSchedulingHint()?.let(::parseRecommendedMinute)
    )

    private fun reuseExistingLabel(candidate: String, existingLabels: List<String>): String {
        val normalized = normalizeTaskLabel(candidate)
        return existingLabels.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
    }

    private fun assistantTaskDetail(suggestion: AssistantTaskSuggestion): String = buildString {
        append(suggestion.detail.trim())
        suggestion.schedulingHint?.takeIf { it.isNotBlank() }?.let { hint ->
            if (isNotEmpty()) append("\n")
            append("排程建议：").append(hint.removePrefix("排程建议："))
        }
        suggestion.parentTaskTitle?.takeIf { it.isNotBlank() }?.let { parent ->
            if (isNotEmpty()) append("\n")
            append("所属整体任务：").append(parent)
        }
        suggestion.assistantOrder?.let { order ->
            if (isNotEmpty()) append("\n")
            append("助手顺序：").append(order + 1)
        }
    }

    private fun String.detailSchedulingHint(): String? = lineSequence()
        .firstOrNull { it.startsWith("排程建议：") }
        ?.removePrefix("排程建议：")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun String.detailParentTask(): String? = lineSequence()
        .firstOrNull { it.startsWith("所属整体任务：") }
        ?.removePrefix("所属整体任务：")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun String.detailAssistantOrder(): Int? = Regex("助手顺序：(\\d+)")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.minus(1)

    private fun parseRecommendedMinute(hint: String): Int? = Regex("(?:次日)?(\\d{1,2}):(\\d{2})")
        .find(hint)
        ?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let null
            val minute = match.groupValues[2].toIntOrNull() ?: return@let null
            if (hour !in 0..23 || minute !in 0..59) null else normalizePlanningMinute(hour * 60 + minute)
        }

    private fun effectiveAssistantPreset(plan: AssistantPlan): AssistantPreset {
        val base = _assistantPreset.value
        val rawStart = plan.requestedWindowStart ?: return base
        val rawEnd = plan.requestedWindowEnd ?: return base
        val start = normalizePlanningMinute(rawStart)
        val end = normalizePlanningMinute(rawEnd)
        if (start !in PLANNING_DAY_START_MINUTE until PLANNING_DAY_END_MINUTE || end <= start) return base
        return base.copy(
            windowStartMinute = start,
            windowEndMinute = end.coerceIn(start + 10, PLANNING_DAY_END_MINUTE)
        )
    }

    private fun alignAssistantPlan(
        plan: AssistantPlan,
        planningDay: LocalDate,
        timelineBlocks: List<ScheduleBlockEntity>
    ): AssistantPlan {
        if (!plan.isPlanning || plan.tasks.isEmpty()) return plan
        val preset = effectiveAssistantPreset(plan)
        val request = SchedulingRequest(
            tasks = plan.tasks.mapIndexed { index, suggestion ->
                SchedulingTask(
                    key = suggestion.id,
                    title = suggestion.title,
                    detail = suggestion.detail,
                    label = normalizeTaskLabel(suggestion.label),
                    priority = suggestion.priority,
                    minutes = roundTaskMinutes(suggestion.minutes),
                    preferredDay = suggestion.dayOffset?.let { planningDay.plusDays(it.toLong()) },
                    // Keep tie-breaking identical to the assistant's insertion order.
                    createdAt = index.toLong(),
                    assistantOrder = suggestion.assistantOrder ?: index,
                    schedulingHint = suggestion.schedulingHint,
                    parentTaskTitle = suggestion.parentTaskTitle,
                    preferredStartMinute = suggestion.recommendedStartMinute
                )
            },
            existingBlocks = timelineBlocks,
            preset = preset,
            startDay = planningDay,
            dayCount = 7,
            earliestMinuteToday = currentPlanningMinute().coerceAtLeast(preset.windowStartMinute)
        )
        return plan.alignToSchedule(request)
    }

    /** Generation should only expose drafts that the current seven-day grid can place. */
    private fun keepSchedulableDrafts(plan: AssistantPlan): AssistantPlan {
        if (!plan.isPlanning || plan.tasks.isEmpty()) return plan
        val dropped = plan.tasks.count { it.scheduleNote != null }
        if (dropped == 0) return plan
        return plan.copy(
            tasks = plan.tasks.filter { it.scheduleNote == null },
            summary = "${plan.summary} ${dropped} 项因未来7天没有可用空档未生成草案，请先调整预设或时间轴。"
        )
    }

    private fun familyRootId(task: TaskEntity) = task.parentTaskId ?: task.id
    private fun existingTaskKey(task: TaskEntity) = "task:${familyRootId(task)}"
    private fun draftTaskKey(batchId: String, suggestionId: String) = "draft:$batchId:$suggestionId"
    private fun roundTaskMinutes(minutes: Int) = ((minutes.coerceIn(10, 480) + 9) / 10) * 10

    private fun TaskEntity.withTaskEdit(edit: PendingTaskEdit?): TaskEntity = if (edit == null) this else copy(
        title = edit.title,
        detail = edit.detail,
        subject = edit.label,
        priority = edit.priority.rank
    )

    private suspend fun remainingMinutesForEdit(source: TaskEntity, totalMinutes: Int): Int {
        val family = container.tasks.getFamily(familyRootId(source)).ifEmpty { listOf(source) }
        val completedMinutes = family.filter { it.parentTaskId != null && it.completed }.sumOf { it.estimatedMinutes }
        return (totalMinutes - completedMinutes).coerceAtLeast(0)
    }

    private suspend fun synchronizeTaskFamily(family: List<TaskEntity>, edit: PendingTaskEdit, updateDuration: Boolean) {
        val root = family.firstOrNull { it.id == edit.rootTaskId } ?: family.first()
        val isSplitFamily = root.hidden || family.any { it.parentTaskId == root.id }
        val updated = family.map { task ->
            task.withTaskEdit(edit).copy(
                estimatedMinutes = if (updateDuration && !isSplitFamily) edit.totalMinutes else task.estimatedMinutes,
                plannedDayEpoch = if (!isSplitFamily) edit.plannedDayEpoch else task.plannedDayEpoch,
                originalMinutes = if (isSplitFamily) edit.totalMinutes else null
            )
        }
        container.tasks.updateAll(updated)
        container.schedule.updateTaskMetadata(updated.map { it.id }, edit.title, edit.priority.rank)
    }

    private suspend fun mergeIncompleteFamilyToPending(taskId: Long) {
        val source = container.tasks.getById(taskId) ?: return
        val rootId = familyRootId(source)
        val family = container.tasks.getFamily(rootId).ifEmpty { listOf(source) }
        val root = family.firstOrNull { it.id == rootId } ?: source
        val children = family.filter { it.parentTaskId == rootId }
        if (!root.hidden && children.isEmpty()) {
            container.schedule.deleteByTaskId(root.id)
            container.tasks.update(root.copy(plannedDayEpoch = null))
            return
        }
        val completedChildren = children.filter { it.completed }
        val incompleteChildren = children.filterNot { it.completed }
        val totalMinutes = root.originalMinutes ?: children.sumOf { it.estimatedMinutes }.coerceAtLeast(root.estimatedMinutes)
        val remainingMinutes = (totalMinutes - completedChildren.sumOf { it.estimatedMinutes }).coerceAtLeast(0)
        val incompleteIds = incompleteChildren.map { it.id }
        if (incompleteIds.isNotEmpty()) {
            container.schedule.deleteByTaskIds(incompleteIds)
            container.tasks.deleteByIds(incompleteIds)
        }
        container.tasks.update(
            root.copy(
                hidden = true,
                completed = remainingMinutes == 0,
                estimatedMinutes = totalMinutes,
                plannedDayEpoch = null,
                originalMinutes = totalMinutes
            )
        )
        if (remainingMinutes == 0) return
        val groupId = children.firstNotNullOfOrNull { it.splitGroupId } ?: UUID.randomUUID().toString()
        container.tasks.insert(
            root.copy(
                id = 0,
                estimatedMinutes = remainingMinutes,
                plannedDayEpoch = null,
                parentTaskId = rootId,
                splitGroupId = groupId,
                splitIndex = null,
                splitCount = null,
                hidden = false,
                completed = false,
                originalMinutes = totalMinutes,
                createdAt = root.createdAt + completedChildren.size + 1
            )
        )
    }

    private fun updateAssistantState(transform: (AssistantUiState) -> AssistantUiState) {
        _assistantUiState.update(transform)
        val state = _assistantUiState.value
        container.assistantPreferences.saveWorkspace(
            AssistantWorkspace(
                messages = state.messages,
                draftBatches = state.draftBatches,
                memoryResetAt = state.memoryResetAt
            )
        )
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
    fun toggle(task: TaskEntity) = viewModelScope.launch {
        runCatching { container.database.withTransaction {
            val updated = task.copy(completed = !task.completed)
            container.tasks.update(updated)
            task.parentTaskId?.let { rootId ->
                val family = container.tasks.getFamily(rootId)
                val children = family.filter { it.parentTaskId == rootId }
                family.firstOrNull { it.id == rootId }?.let { root ->
                    container.tasks.update(root.copy(completed = children.isNotEmpty() && children.all { it.completed }))
                }
            }
        } }
    }

    fun setTasksCompleted(taskIds: Set<Long>, completed: Boolean) = viewModelScope.launch {
        if (taskIds.isEmpty()) return@launch
        runCatching {
            container.database.withTransaction {
                val selected = taskIds.mapNotNull { container.tasks.getById(it) }
                if (selected.isEmpty()) return@withTransaction
                container.tasks.updateAll(selected.map { it.copy(completed = completed) })
                selected.mapNotNull { it.parentTaskId }.distinct().forEach { rootId ->
                    val family = container.tasks.getFamily(rootId)
                    val children = family.filter { it.parentTaskId == rootId }
                    family.firstOrNull { it.id == rootId }?.let { root ->
                        container.tasks.update(root.copy(completed = children.isNotEmpty() && children.all { it.completed }))
                    }
                }
            }
        }
    }

    fun saveTask(
        existing: TaskEntity?,
        title: String,
        detail: String,
        type: String,
        priority: Priority,
        minutes: Int,
        plannedDayEpoch: Long?,
        onResult: (String) -> Unit = {}
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            try {
                val label = normalizeTaskLabel(type)
                if (existing == null) {
                if (label != "未分类") container.labels.insert(TaskLabelEntity(label))
                container.tasks.insert(
                    TaskEntity(
                        title = title.trim(),
                        detail = detail.trim(),
                        subject = label,
                        priority = priority.rank,
                        estimatedMinutes = roundTaskMinutes(minutes),
                        plannedDayEpoch = plannedDayEpoch
                    )
                )
                onResult("任务已创建")
                } else {
                val family = container.tasks.getFamily(familyRootId(existing)).ifEmpty { listOf(existing) }
                val root = family.firstOrNull { it.id == familyRootId(existing) } ?: existing
                val completedMinutes = family.filter { it.parentTaskId != null && it.completed }.sumOf { it.estimatedMinutes }
                val normalizedMinutes = roundTaskMinutes(minutes)
                if (normalizedMinutes < completedMinutes) {
                    onResult("总时长不能少于已完成分块的 $completedMinutes 分钟")
                    return@launch
                }
                val oldTotal = root.originalMinutes ?: root.estimatedMinutes
                val isSplitFamily = root.hidden || family.any { it.parentTaskId == root.id }
                val familyIds = family.mapTo(mutableSetOf()) { it.id }
                val scheduled = container.schedule.getByTaskIds(familyIds.toList())
                val edit = PendingTaskEdit(
                    rootTaskId = root.id,
                    sourceTaskId = existing.id,
                    title = title.trim(),
                    detail = detail.trim(),
                    label = label,
                    priority = priority,
                    totalMinutes = normalizedMinutes,
                    plannedDayEpoch = plannedDayEpoch
                )
                val needsReschedule = normalizedMinutes != oldTotal && (isSplitFamily || scheduled.any { block ->
                    family.firstOrNull { it.id == block.taskId }?.completed == false
                })
                if (!needsReschedule) {
                    container.database.withTransaction {
                        if (label != "未分类") container.labels.insert(TaskLabelEntity(label))
                        synchronizeTaskFamily(family, edit, updateDuration = normalizedMinutes != oldTotal)
                    }
                    onResult("任务修改已保存")
                    return@launch
                }
                val remainingMinutes = (normalizedMinutes - completedMinutes).coerceAtLeast(0)
                if (remainingMinutes == 0) {
                    container.database.withTransaction {
                        if (label != "未分类") container.labels.insert(TaskLabelEntity(label))
                        replaceTaskFamilySchedule(existing, emptyList(), currentPlanningDay(), edit)
                    }
                    onResult("已保留完成记录，任务没有剩余时长")
                    return@launch
                }
                val schedulingTask = taskToSchedulingTask(existing, 0).copy(
                    title = edit.title,
                    detail = edit.detail,
                    label = edit.label,
                    priority = edit.priority,
                    minutes = remainingMinutes,
                    preferredDay = edit.plannedDayEpoch?.let(LocalDate::ofEpochDay)
                )
                val incompleteIds = family.filterNot { it.completed }.mapTo(mutableSetOf()) { it.id }
                generateSchedulePreview(
                    kind = SchedulePreviewKind.RESCHEDULE,
                    title = "修改后重新排程",
                    schedulingTasks = listOf(schedulingTask),
                    preset = _assistantPreset.value,
                    selectedTaskIds = setOf(existing.id),
                    excludedTaskIds = incompleteIds,
                    taskEdit = edit,
                    onResult = onResult
                )
                }
            } catch (error: Exception) {
                onResult("任务保存失败，原数据未修改：${error.message ?: "未知错误"}")
            }
        }
    }
    fun deleteLabel(label: String) = viewModelScope.launch {
        if (label == "未分类") return@launch
        container.tasks.moveLabelToUncategorized(label)
        container.labels.deleteByName(label)
    }
    fun deleteTask(task: TaskEntity) = viewModelScope.launch {
        runCatching { container.database.withTransaction {
            val family = container.tasks.getFamily(familyRootId(task)).ifEmpty { listOf(task) }
            val ids = family.map { it.id }
            container.schedule.deleteByTaskIds(ids)
            container.tasks.deleteByIds(ids)
        } }
    }
    fun deleteTasks(taskIds: Set<Long>, onResult: (String) -> Unit = {}) = viewModelScope.launch {
        if (taskIds.isEmpty()) return@launch
        runCatching {
            container.database.withTransaction {
                val selected = taskIds.mapNotNull { container.tasks.getById(it) }
                val rootIds = selected.map(::familyRootId).distinct()
                val familyTasks = selected.flatMap { task ->
                    container.tasks.getFamily(familyRootId(task)).ifEmpty { listOf(task) }
                }.distinctBy { it.id }
                val ids = familyTasks.map { it.id }
                if (ids.isNotEmpty()) {
                    container.schedule.deleteByTaskIds(ids)
                    container.tasks.deleteByIds(ids)
                }
                rootIds.size
            }
        }.onSuccess { count ->
            onResult(if (count == 0) "没有可删除的任务" else "已删除 $count 组任务及关联时间块")
        }.onFailure { error ->
            onResult("删除失败，原数据未修改：${error.message ?: "未知错误"}")
        }
    }
    fun scheduleDay(day: LocalDate, onResult: (String) -> Unit = {}) = viewModelScope.launch {
        runCatching {
            val zone = ZoneId.systemDefault()
            val start = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
            val scheduledIds = container.schedule.getScheduledTaskIds().toSet()
            val eligible = tasks.value.filter { !it.completed && it.id !in scheduledIds }
            val drafts = Planner.planAvailable(eligible, container.schedule.getBetween(start, end), day)
            container.schedule.insertAll(drafts.map { ScheduleBlockEntity(taskId = it.taskId, title = it.title, startAt = it.startAt, endAt = it.endAt, priority = it.priority.rank) })
            drafts.size
        }.onSuccess { count ->
            onResult(if (count == 0) "没有可自动安排的待办任务" else "已安排 $count 项，原有时间块保持不变")
        }.onFailure { onResult("自动排程失败：${it.message ?: "未知错误"}") }
    }
    fun resetDay(day: LocalDate, onResult: (String) -> Unit = {}) = viewModelScope.launch {
        runCatching {
            val zone = ZoneId.systemDefault()
            val start = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
            val affected = container.schedule.getBetween(start, end).mapNotNull { block ->
                block.taskId?.let { container.tasks.getById(it) }?.takeUnless { it.completed }
            }.distinctBy(::familyRootId)
            container.database.withTransaction { affected.forEach { mergeIncompleteFamilyToPending(it.id) } }
            affected.size
        }.onSuccess { count ->
            onResult(if (count == 0) "本天没有可回收的未完成任务" else "已将本天 $count 组未完成任务融合并退回待安排")
        }.onFailure { onResult("回收失败，时间轴未修改：${it.message ?: "未知错误"}") }
    }
    fun placeTask(task: TaskEntity, startAt: Long, latestEnd: Long, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching {
            val endAt = startAt + task.estimatedMinutes * 60_000L
            val startClock = Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).toLocalTime()
            val startMinute = normalizePlanningMinute(startClock.hour * 60 + startClock.minute)
            val endMinute = startMinute + task.estimatedMinutes
            val exclusion = _assistantPreset.value.excludedTimes.firstOrNull {
                it.enabled && startMinute < it.endMinute && endMinute > it.startMinute
            }
            when {
                container.schedule.countByTaskId(task.id) > 0 -> "该任务已经排入时间轴"
                endAt > latestEnd -> "任务会超过次日 02:00，请选择更早的时间"
                exclusion != null -> "该时间与排除时段“${exclusion.label}”重叠"
                container.schedule.countOverlapping(startAt, endAt) > 0 -> "这个时间段已有安排"
                else -> {
                    container.schedule.insert(ScheduleBlockEntity(taskId = task.id, title = task.title, startAt = startAt, endAt = endAt, priority = task.priority))
                    null
                }
            }
        }.onSuccess(onResult).onFailure { onResult("安排失败：${it.message ?: "未知错误"}") }
    }
    fun unschedule(block: ScheduleBlockEntity, onResult: (String) -> Unit = {}) = viewModelScope.launch {
        runCatching {
            val taskId = block.taskId
            if (taskId == null) {
                container.schedule.delete(block)
                return@runCatching "时间块已移除"
            }
            val task = container.tasks.getById(taskId)
            if (task == null || task.completed) return@runCatching "已完成记录不会退回待办"
            container.database.withTransaction { mergeIncompleteFamilyToPending(taskId) }
            if (task.parentTaskId == null) "任务已退回待安排" else "同组未完成分块已融合并退回待安排"
        }.onSuccess(onResult).onFailure { onResult("退回失败，原排程已保留：${it.message ?: "未知错误"}") }
    }
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

    fun clearAssistantMemory(onResult: (String) -> Unit = {}) {
        val cleared = _assistantUiState.value.messages.size
        updateAssistantState {
            it.copy(
                memoryResetAt = System.currentTimeMillis(),
                loading = false,
                scheduling = false,
                scheduleStatus = null,
                schedulePreview = null,
                error = null,
                modelName = null
            )
        }
        onResult(
            if (cleared == 0) {
                "当前没有对话记忆可清除；任务草案、本地任务、时间预设均保留，七天自动清理规则不变。"
            } else {
                "已清除 $cleared 条对话记忆（用户消息和助手回复）；任务草案、本地任务、时间预设均保留，七天自动清理规则不变。"
            }
        )
    }
}
