package com.ming.focusplan.assistant

import com.ming.focusplan.data.Priority
import com.ming.focusplan.data.ScheduleBlockEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class SchedulingTask(
    val key: String,
    val title: String,
    val detail: String,
    val label: String,
    val priority: Priority,
    val minutes: Int,
    val preferredDay: LocalDate? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Explicit order from the assistant/draft; used before database timestamps. */
    val assistantOrder: Int? = null,
    /** Human-readable guidance preserved from an assistant draft. */
    val schedulingHint: String? = null,
    val parentTaskTitle: String? = null,
    /** Optional concrete slot suggested by the assistant. */
    val preferredStartMinute: Int? = null
)

data class SchedulingRequest(
    val tasks: List<SchedulingTask>,
    val existingBlocks: List<ScheduleBlockEntity>,
    val preset: AssistantPreset,
    val startDay: LocalDate,
    val dayCount: Int = 7,
    val earliestMinuteToday: Int = PLANNING_DAY_START_MINUTE
)

data class ProposedScheduleBlock(
    val taskKey: String,
    val dayOffset: Int,
    val startMinute: Int,
    val minutes: Int,
    val partIndex: Int = 1,
    val partCount: Int = 1
)

data class ProposedBuffer(
    val dayOffset: Int,
    val startMinute: Int,
    val minutes: Int,
    val reason: String = "机动"
)

/** A real continuous run available on the planning-day grid. */
data class AvailableScheduleRun(
    val dayOffset: Int,
    val startMinute: Int,
    val minutes: Int
)

data class UnscheduledTask(val taskKey: String, val reason: String)

data class AgentScheduleProposal(
    val reply: String,
    val blocks: List<ProposedScheduleBlock>,
    val buffers: List<ProposedBuffer>,
    val unscheduled: List<UnscheduledTask>,
    val usedLocalFallback: Boolean = false,
    val validationNote: String? = null
) {
    val splitTaskCount: Int get() = blocks.groupBy { it.taskKey }.count { it.value.size > 1 }
}

private data class ScheduleOrderEntry(val taskKey: String, val preferredDayOffset: Int?)
private data class ScheduleOrder(val reply: String, val entries: List<ScheduleOrderEntry>)

object AgentSchedulePlanner {
    fun validateProposal(proposal: AgentScheduleProposal, request: SchedulingRequest): String? = validate(proposal, request)

    /**
     * Returns the same free runs used by the scheduler. Keeping this public lets
     * the assistant explain availability from the exact grid that will be used
     * when the preview is applied.
     */
    fun availableRuns(request: SchedulingRequest, minimumMinutes: Int = SLOT_MINUTES): List<AvailableScheduleRun> =
        buildBaseGrids(request).flatMapIndexed { dayOffset, grid ->
            grid.freeRuns(minimumMinutes).map { (startMinute, minutes) ->
                AvailableScheduleRun(dayOffset, startMinute, minutes)
            }
        }

    fun prompt(request: SchedulingRequest): String {
        val tasksJson = JSONArray().apply {
            request.tasks.forEach { task ->
                put(
                    JSONArray()
                        .put(task.key)
                        .put(task.title.take(60))
                        .put(task.detail.take(80))
                        .put(task.priority.name.first().toString())
                        .put(roundToTen(task.minutes))
                        .put(task.preferredDay?.let { ChronoUnit.DAYS.between(request.startDay, it).toInt() } ?: JSONObject.NULL)
                        .put(task.assistantOrder ?: JSONObject.NULL)
                        .put(task.schedulingHint?.take(120) ?: JSONObject.NULL)
                        .put(task.parentTaskTitle?.take(80) ?: JSONObject.NULL)
                )
            }
        }
        val occupiedJson = JSONArray().apply {
            request.existingBlocks.forEach { block ->
                blockPositions(block, request.startDay, request.dayCount).forEach { position ->
                    put(
                        JSONArray()
                            .put(position.first)
                            .put(position.second)
                            .put(position.third)
                            .put(block.title.take(60))
                    )
                }
            }
        }
        val freeJson = JSONArray().apply {
            buildBaseGrids(request).forEachIndexed { dayOffset, grid ->
                grid.freeRuns(minimumMinutes = 30).forEach { (startMinute, minutes) ->
                    put(JSONArray().put(dayOffset).put(startMinute).put(minutes))
                }
            }
        }
        val exclusionsJson = JSONArray().apply {
            request.preset.excludedTimes.filter { it.enabled }.forEach { period ->
                put(
                    JSONArray()
                        .put(period.label.take(20))
                        .put(normalizePlanningMinute(period.startMinute))
                        .put(normalizePlanningMinute(period.endMinute))
                )
            }
        }
        val preferenceJson = JSONObject.quote(request.preset.instructions.take(300))
        return """
            你只负责决定任务顺序和建议执行日，不决定具体时间，不改任务内容；具体时间和必要分块由本地规则完成。
            D=${request.startDay}, N=${request.dayCount}, R=360..1560, NOW=${request.earliestMinuteToday}, WINDOW=[${request.preset.windowStartMinute},${request.preset.windowEndMinute}]
            PREF=$preferenceJson
            X=[label,start,end]：$exclusionsJson
            O=[day,start,minutes,title]：$occupiedJson
            F=[day,start,minutes]，仅列出至少30分钟的连续空档：$freeJson
            T=[key,title,detail,priority(H/M/L),minutes,preferredDay或null]：$tasksJson（后3项依次为助手顺序、排程备注、所属整体任务）
            规则：PREF是长期偏好，只能辅助同优先级任务排序和建议执行日，不能覆盖优先级、时间范围、排除时段和已有安排。X在规划期内每天重复生效。F用于判断哪些连续空档值得利用；先按优先级，再按助手顺序和排程备注排序。单个任务不超过120分钟时，只要存在一个能容纳它的连续空档就必须整块安排；只有不存在完整空档时才按真实空档拆成30到90分钟为主的连续块（30/40分钟只是可用示例，不是固定值），必要时允许最后一块小于30分钟以保证总时长不丢失。总时长必须保持不变。每个key必须且只能出现一次。day为0到${request.dayCount - 1}或null，仅表示建议日；应用会根据真实空档确定具体开始时间，真正没有任何可用空档的任务才保留在待办区。
            只返回JSON且使用以下短字段，不要解释字段，不要Markdown：
            {"r":"一句简短GPT娘口吻总结","o":[["key",day或null]]}
        """.trimIndent()
    }

    fun resolve(raw: String, request: SchedulingRequest): AgentScheduleProposal {
        return runCatching { parseOrder(raw, request) }.fold(
            onSuccess = { order -> planWholeTasks(request, order.entries, order.reply, usedLocalFallback = false) },
            onFailure = { error ->
                localPlan(request).copy(validationNote = "模型排序格式无法解析：${error.message ?: "未知错误"}")
            }
        )
    }

    fun localPlan(request: SchedulingRequest): AgentScheduleProposal = planWholeTasks(request, emptyList(), "", usedLocalFallback = true)

    private fun planWholeTasks(
        request: SchedulingRequest,
        orderEntries: List<ScheduleOrderEntry>,
        reply: String,
        usedLocalFallback: Boolean
    ): AgentScheduleProposal {
        if (request.tasks.isEmpty()) return AgentScheduleProposal("没有选中的任务，当然排不出东西啦。", emptyList(), emptyList(), emptyList(), true)
        val grids = buildBaseGrids(request)
        val result = mutableListOf<ProposedScheduleBlock>()
        val unscheduled = mutableListOf<UnscheduledTask>()
        val modelRank = orderEntries.mapIndexed { index, entry -> entry.taskKey to index }.toMap()
        val inputRank = request.tasks.mapIndexed { index, task -> task.key to index }.toMap()
        val modelDay = orderEntries.associate { it.taskKey to it.preferredDayOffset }
        Priority.entries.sortedByDescending { it.rank }.forEach { priority ->
            val priorityTasks = request.tasks.filter { it.priority == priority }
            val hasNumberedSequence = priorityTasks.count { taskSequenceNumber(it) != null } >= 2
            val tier = priorityTasks.sortedWith(
                compareBy<SchedulingTask> {
                    if (hasNumberedSequence) taskSequenceNumber(it) ?: Int.MAX_VALUE else 0
                }
                    .thenBy {
                        modelRank[it.key] ?: it.assistantOrder ?: inputRank[it.key] ?: Int.MAX_VALUE
                    }
                    .thenByDescending { roundToTen(it.minutes) }
                    .thenBy { it.preferredDay ?: request.startDay }
                    .thenBy { it.createdAt }
            )
            tier.forEach { task ->
                val modelPreferredDay = modelDay[task.key]
                    ?.takeIf { it in 0 until request.dayCount }
                    ?.let { request.startDay.plusDays(it.toLong()) }
                val scheduledTask = if (task.preferredDay == null && modelPreferredDay != null) task.copy(preferredDay = modelPreferredDay) else task
                val placed = placeTask(scheduledTask, request, grids)
                if (placed == null) unscheduled += UnscheduledTask(
                    task.key,
                    "没有任何可用连续空档；本地规则已尝试按30到90分钟为主分块"
                ) else result += placed
            }
        }
        val summary = reply.ifBlank {
            buildString {
                append("哼，本 GPT 娘已经按优先级、助手顺序和真实空档排好了；只有完整空档不足时才会分块。")
                if (unscheduled.isNotEmpty()) append(" 还有 ${unscheduled.size} 项连最小分块空档都没有，已留在待办区。")
            }
        }
        return AgentScheduleProposal(summary, result.sortedWith(compareBy({ it.dayOffset }, { it.startMinute })), emptyList(), unscheduled, usedLocalFallback)
    }

    private fun parseOrder(raw: String, request: SchedulingRequest): ScheduleOrder {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val payload = extractJsonPayload(clean)
        val root = payload.first as? JSONObject
        val rootArray = payload.second
        val knownKeys = request.tasks.mapTo(mutableSetOf()) { it.key }
        val keyByTitle = request.tasks.associateBy { normalizeTaskText(it.title) }
        val entries = mutableListOf<ScheduleOrderEntry>()
        fun resolveKey(rawKey: String): String? {
            val candidate = rawKey.trim()
            if (candidate.isBlank()) return null
            knownKeys.firstOrNull { it == candidate || it.equals(candidate, ignoreCase = true) }?.let { return it }
            val normalizedCandidate = normalizeTaskText(candidate)
            keyByTitle[normalizedCandidate]?.let { return it.key }
            // Some providers strip the draft/task prefix from an otherwise valid key.
            knownKeys.firstOrNull { normalizeTaskText(it.substringAfterLast(':')) == normalizedCandidate }?.let { return it }
            return null
        }

        fun addEntry(rawKey: String?, rawDay: Any?) {
            val key = rawKey?.let(::resolveKey) ?: return
            val day = rawDay?.takeUnless { it == JSONObject.NULL }?.toString()?.toIntOrNull()
            if (entries.none { it.taskKey == key }) entries += ScheduleOrderEntry(key, day)
        }

        fun parseOrderArray(order: JSONArray) {
            for (index in 0 until order.length()) {
                val value = order.opt(index)
                val arrayItem = value as? JSONArray
                val objectItem = value as? JSONObject
                val stringItem = value as? String
                val rawKey = arrayItem?.optString(0)?.trim().orEmpty()
                    .ifBlank { objectItem?.firstNonBlankString("taskKey", "key", "id", "taskId", "title", "name", "task") ?: "" }
                    .ifBlank { stringItem?.trim().orEmpty() }
                val rawDay = arrayItem?.opt(1).takeUnless { it == null || it == JSONObject.NULL }
                    ?: objectItem?.firstValue("dayOffset", "day", "dayIndex", "preferredDay")
                addEntry(rawKey, rawDay)
            }
        }

        if (rootArray != null) parseOrderArray(rootArray)
        if (root != null) {
            listOf("o", "order", "orders", "taskOrder", "scheduleOrder", "list", "tasks").firstNotNullOfOrNull { root.optJSONArray(it) }
                ?.let(::parseOrderArray)
            // A few compatible APIs return {"order":{"taskKey":0,...}}.
            if (entries.isEmpty()) {
                val orderObject = listOf("o", "order", "orders", "taskOrder")
                    .firstNotNullOfOrNull { root.optJSONObject(it) }
                orderObject?.keys()?.forEachRemaining { key -> addEntry(key, orderObject.opt(key)) }
            }
            // Upgrade compatibility: keep only order/day from the old block protocol and ignore its times and splits.
            if (entries.isEmpty()) {
                listOf("b", "blocks", "schedule", "items").firstNotNullOfOrNull { root.optJSONArray(it) }
                    ?.let(::parseOrderArray)
            }
        }
        require(entries.isNotEmpty()) { "模型没有返回有效任务顺序" }
        // Keep omitted tasks deterministic and in the same order supplied by the
        // assistant/draft; priority is applied later by the planner as a hard tier.
        val missing = request.tasks.filter { task -> entries.none { it.taskKey == task.key } }
            .map { ScheduleOrderEntry(it.key, null) }
        return ScheduleOrder(
            reply = root?.optString("r").orEmpty().ifBlank { root?.optString("reply").orEmpty() }
                .ifBlank { "任务顺序已经整理好了，具体时间由空档规则落位。" },
            entries = entries + missing
        )
    }

    private fun JSONObject.firstValue(vararg names: String): Any? = names
        .firstNotNullOfOrNull { name -> opt(name).takeUnless { it == null || it == JSONObject.NULL } }

    private fun JSONObject.firstNonBlankString(vararg names: String): String? = names
        .firstNotNullOfOrNull { name -> optString(name).trim().takeIf { it.isNotBlank() } }

    private fun normalizeTaskText(value: String): String = value
        .lowercase()
        .replace(Regex("\\s+"), "")
        .replace(Regex("[：:，,。.!！？?（）()\\[\\]【】]"), "")

    /** Extracts the first valid JSON object/array even when the model adds a code fence or prose. */
    private fun extractJsonPayload(text: String): Pair<JSONObject?, JSONArray?> {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it to null }
        }
        if (trimmed.startsWith("[")) {
            runCatching { JSONArray(trimmed) }.getOrNull()?.let { return null to it }
        }
        val candidates = buildList {
            val objectStart = text.indexOf('{')
            val objectEnd = text.lastIndexOf('}')
            val arrayStart = text.indexOf('[')
            val arrayEnd = text.lastIndexOf(']')
            if (arrayStart >= 0 && arrayEnd > arrayStart && (objectStart < 0 || arrayStart < objectStart)) {
                add(text.substring(arrayStart, arrayEnd + 1))
            }
            if (objectStart >= 0 && objectEnd > objectStart) add(text.substring(objectStart, objectEnd + 1))
            if (arrayStart >= 0 && arrayEnd > arrayStart && (objectStart < 0 || arrayStart >= objectStart)) {
                add(text.substring(arrayStart, arrayEnd + 1))
            }
        }
        candidates.forEach { candidate ->
            runCatching { JSONObject(candidate) }.getOrNull()?.let { return it to null }
            runCatching { JSONArray(candidate) }.getOrNull()?.let { return null to it }
        }
        throw IllegalArgumentException("缺少有效JSON对象或数组")
    }

    private fun validate(proposal: AgentScheduleProposal, request: SchedulingRequest): String? {
        val taskByKey = request.tasks.associateBy { it.key }
        if (taskByKey.size != request.tasks.size) return "任务标识重复"
        if ((proposal.blocks.map { it.taskKey } + proposal.unscheduled.map { it.taskKey }).any { it !in taskByKey }) return "模型返回了未知任务"
        if (proposal.unscheduled.map { it.taskKey }.distinct().size != proposal.unscheduled.size) return "未排入任务重复"
        val grids = buildBaseGrids(request)
        proposal.blocks.sortedWith(compareBy({ it.dayOffset }, { it.startMinute })).forEach { block ->
            val error = validateInterval(block.dayOffset, block.startMinute, block.minutes, request.dayCount) ?: grids[block.dayOffset].occupy(block.startMinute, block.minutes)
            if (error != null) return "任务块无效：$error"
        }
        request.tasks.forEach { task ->
            val parts = proposal.blocks.filter { it.taskKey == task.key }
            val markedUnscheduled = proposal.unscheduled.any { it.taskKey == task.key }
            if (parts.isEmpty() == !markedUnscheduled) return "任务 ${task.title} 被遗漏或同时排入和标记未排入"
            if (parts.isNotEmpty()) {
                val expected = roundToTen(task.minutes)
                if (parts.sumOf { it.minutes } != expected) return "任务 ${task.title} 的分块总时长不等于 $expected 分钟"
                val expectedPartCount = parts.size
                if (parts.any { it.partCount != expectedPartCount }) return "任务 ${task.title} 的分块总数标记错误"
                if (parts.map { it.partIndex }.distinct().sorted() != (1..expectedPartCount).toList()) {
                    return "任务 ${task.title} 的分块编号错误"
                }
            }
        }
        return null
    }

    private fun validateInterval(dayOffset: Int, startMinute: Int, minutes: Int, dayCount: Int): String? = when {
        dayOffset !in 0 until dayCount -> "规划日越界"
        startMinute !in PLANNING_DAY_START_MINUTE until PLANNING_DAY_END_MINUTE -> "开始时间越界"
        startMinute % SLOT_MINUTES != 0 || minutes % SLOT_MINUTES != 0 -> "必须对齐10分钟网格"
        minutes < SLOT_MINUTES -> "时长不足10分钟"
        startMinute + minutes > PLANNING_DAY_END_MINUTE -> "结束时间超过次日02:00"
        else -> null
    }

    private data class CandidateRun(val dayOffset: Int, val startMinute: Int, val minutes: Int)

    private val numberedTaskPattern = Regex(
        "第\\s*([0-9]{1,3}|[零〇一二两三四五六七八九十百千]+)\\s*(?:讲|课|章|节|单元|部分)"
    )

    /** Extracts a natural lesson/chapter order without relying on model output. */
    private fun taskSequenceNumber(task: SchedulingTask): Int? =
        numberedTaskPattern.find("${task.title} ${task.detail}")?.groupValues?.getOrNull(1)?.let { token ->
            token.toIntOrNull() ?: chineseNumber(token)
        }?.takeIf { it > 0 }

    private fun chineseNumber(value: String): Int? {
        val digit = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7,
            '八' to 8, '九' to 9
        )
        val unit = mapOf('十' to 10, '百' to 100, '千' to 1000)
        if (value.isEmpty()) return null
        if (value.all { it in digit }) return value.fold(0) { result, char -> result * 10 + digit.getValue(char) }
        var total = 0
        var section = 0
        var number = 0
        value.forEach { char ->
            when {
                char in digit -> number = digit.getValue(char)
                char in unit -> {
                    val multiplier = unit.getValue(char)
                    section += (if (number == 0) 1 else number) * multiplier
                    number = 0
                    if (multiplier >= 1000) {
                        total += section
                        section = 0
                    }
                }
                else -> return null
            }
        }
        return total + section + number
    }

    /** Places a task as one block when possible; only fragments when no whole gap can hold it. */
    private fun placeTask(task: SchedulingTask, request: SchedulingRequest, grids: List<DayGrid>): List<ProposedScheduleBlock>? {
        val totalMinutes = roundToTen(task.minutes)
        val rawRuns = allowedDays(task, request).flatMap { day ->
            grids[day].freeRuns(minimumMinutes = SLOT_MINUTES).map { (startMinute, minutes) ->
                CandidateRun(day, startMinute, minutes)
            }
        }
        val preferredDayOffset = task.preferredDay
            ?.let { ChronoUnit.DAYS.between(request.startDay, it).toInt() }
            ?.takeIf { it in 0 until request.dayCount }
        val dayOrderedRuns = rawRuns.sortedWith(
            compareBy<CandidateRun> { preferredDayOffset?.let { preferred -> abs(it.dayOffset - preferred) } ?: 0 }
                .thenBy { it.dayOffset }
                .thenBy { it.startMinute }
        )
        val runs = task.preferredStartMinute?.let { preferred ->
            dayOrderedRuns.sortedWith(compareBy<CandidateRun> { abs(it.startMinute - preferred) }.thenBy { it.dayOffset }.thenBy { it.startMinute })
        } ?: dayOrderedRuns
        if (runs.isEmpty()) return null

        // A task up to two hours stays whole whenever one continuous gap can hold it.
        val sufficient = runs.filter { it.minutes >= totalMinutes && totalMinutes <= MAX_WHOLE_TASK_MINUTES }
            .minWithOrNull(
                compareBy<CandidateRun> { candidate ->
                    task.preferredStartMinute?.let { abs(candidate.startMinute - it) } ?: 0
                }
                    .thenBy { candidate -> preferredDayOffset?.let { abs(candidate.dayOffset - it) } ?: 0 }
                    .thenBy { it.minutes - totalMinutes }
                    .thenBy { it.dayOffset }
                    .thenBy { it.startMinute }
            )
        if (sufficient != null) {
            val start = (sufficient.startMinute - PLANNING_DAY_START_MINUTE) / SLOT_MINUTES
            grids[sufficient.dayOffset].mark(start, totalMinutes / SLOT_MINUTES)
            return listOf(
                ProposedScheduleBlock(
                    taskKey = task.key,
                    dayOffset = sufficient.dayOffset,
                    startMinute = sufficient.startMinute,
                    minutes = totalMinutes,
                    partIndex = 1,
                    partCount = 1
                )
            )
        }

        // No whole gap exists (or the task exceeds the whole-task limit): consume
        // real gaps in deterministic order and split each consumed run into balanced
        // 30-90 minute chunks where possible.
        val selectedRuns = runs
        var remaining = totalMinutes
        val candidates = mutableListOf<CandidateRun>()
        selectedRuns.forEach { run ->
            if (remaining <= 0) return@forEach
            val take = minOf(remaining, run.minutes)
            val chunks = chunkSizesForCapacity(take)
            var offset = 0
            chunks.forEach { chunk ->
                candidates += CandidateRun(run.dayOffset, run.startMinute + offset, chunk)
                offset += chunk
            }
            remaining -= take
        }
        if (remaining > 0) return null

        val orderedCandidates = candidates.sortedWith(compareBy<CandidateRun> { it.dayOffset }.thenBy { it.startMinute })
        val partCount = orderedCandidates.size
        orderedCandidates.forEach { candidate ->
            val start = (candidate.startMinute - PLANNING_DAY_START_MINUTE) / SLOT_MINUTES
            grids[candidate.dayOffset].mark(start, candidate.minutes / SLOT_MINUTES)
        }
        return orderedCandidates.mapIndexed { index, candidate ->
            ProposedScheduleBlock(
                taskKey = task.key,
                dayOffset = candidate.dayOffset,
                startMinute = candidate.startMinute,
                minutes = candidate.minutes,
                partIndex = index + 1,
                partCount = partCount
            )
        }
    }

    private fun chunkSizesForCapacity(minutes: Int): List<Int> {
        val capacity = roundToTen(minutes)
        if (capacity <= MAX_SPLIT_CHUNK_MINUTES) return listOf(capacity)
        val totalSlots = capacity / SLOT_MINUTES
        val maxSlots = MAX_SPLIT_CHUNK_MINUTES / SLOT_MINUTES
        val count = (totalSlots + maxSlots - 1) / maxSlots
        val baseSlots = totalSlots / count
        val extraSlots = totalSlots % count
        return List(count) { index -> (baseSlots + if (index < extraSlots) 1 else 0) * SLOT_MINUTES }
    }

    private fun allowedDays(task: SchedulingTask, request: SchedulingRequest): List<Int> {
        val preferred = task.preferredDay?.let { ChronoUnit.DAYS.between(request.startDay, it).toInt() }
        if (preferred == null || preferred !in 0 until request.dayCount) return (0 until request.dayCount).toList()
        // A model day is a preference, not a hard lower bound. Try it first,
        // then use every other planning day so an otherwise empty earlier gap
        // is not reported as unusable when the preferred day is full.
        return listOf(preferred) + (0 until request.dayCount).filter { it != preferred }
    }

    private fun buildBaseGrids(request: SchedulingRequest): List<DayGrid> = List(request.dayCount) { dayOffset ->
        DayGrid().also { grid ->
            val day = request.startDay.plusDays(dayOffset.toLong())
            val windowStart = maxOf(request.preset.windowStartMinute, if (dayOffset == 0) request.earliestMinuteToday else PLANNING_DAY_START_MINUTE)
            grid.blockOutside(windowStart, request.preset.windowEndMinute)
            request.preset.excludedTimes.filter { it.enabled }.forEach { period ->
                val startMinute = normalizePlanningMinute(period.startMinute)
                val endMinute = normalizePlanningMinute(period.endMinute)
                if (endMinute > startMinute) grid.block(startMinute, endMinute - startMinute)
            }
            request.existingBlocks.forEach { block ->
                blockPositions(block, request.startDay, request.dayCount)
                    .filter { it.first == dayOffset }
                    .forEach { position -> grid.block(position.second, position.third) }
            }
        }
    }

    private fun roundToTen(value: Int): Int = ((value.coerceAtLeast(SLOT_MINUTES) + SLOT_MINUTES - 1) / SLOT_MINUTES) * SLOT_MINUTES

    /**
     * Splits an existing block at planning-day boundaries. A block may cross
     * midnight (or even 02:00), so using only its start date would leave the
     * following day's early-morning slots falsely free.
     */
    private fun blockPositions(block: ScheduleBlockEntity, startDay: LocalDate, dayCount: Int): List<Triple<Int, Int, Int>> {
        val zone = ZoneId.systemDefault()
        val blockStart = block.startAt
        val blockEnd = block.endAt.coerceAtLeast(blockStart)
        if (blockEnd <= blockStart) return emptyList()
        return (0 until dayCount).mapNotNull { dayOffset ->
            val day = startDay.plusDays(dayOffset.toLong())
            val dayStart = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
            val visibleStart = maxOf(blockStart, dayStart)
            val visibleEnd = minOf(blockEnd, dayEnd)
            if (visibleEnd <= visibleStart) return@mapNotNull null
            val startMinute = PLANNING_DAY_START_MINUTE +
                ((visibleStart - dayStart).coerceAtLeast(0L) / 60_000L).toInt()
            val endMinute = PLANNING_DAY_START_MINUTE +
                ((visibleEnd - dayStart + 59_999L) / 60_000L).toInt()
            Triple(dayOffset, startMinute, roundToTen((endMinute - startMinute).coerceAtLeast(SLOT_MINUTES)))
        }
    }

    private class DayGrid(private var occupied: BooleanArray = BooleanArray(SLOT_COUNT)) {
        fun blockOutside(startMinute: Int, endMinute: Int) {
            occupied.indices.forEach { index ->
                val minute = PLANNING_DAY_START_MINUTE + index * SLOT_MINUTES
                if (minute < startMinute || minute >= endMinute) occupied[index] = true
            }
        }
        fun occupy(startMinute: Int, minutes: Int): String? {
            val start = (startMinute - PLANNING_DAY_START_MINUTE) / SLOT_MINUTES
            val count = minutes / SLOT_MINUTES
            if (start < 0 || start + count > SLOT_COUNT) return "时间段越界"
            if ((start until start + count).any { occupied[it] }) return "时间段发生冲突"
            mark(start, count)
            return null
        }
        fun block(startMinute: Int, minutes: Int) {
            val start = ((startMinute - PLANNING_DAY_START_MINUTE) / SLOT_MINUTES).coerceAtLeast(0)
            val endExclusive = ((startMinute + minutes - PLANNING_DAY_START_MINUTE + SLOT_MINUTES - 1) / SLOT_MINUTES)
                .coerceAtMost(SLOT_COUNT)
            if (start < endExclusive) mark(start, endExclusive - start)
        }
        fun mark(start: Int, count: Int) {
            (start until start + count).forEach { index -> occupied[index] = true }
        }
        fun findRun(count: Int): Int? {
            var index = 0
            var bestStart: Int? = null
            var bestWaste = Int.MAX_VALUE
            while (index < SLOT_COUNT) {
                while (index < SLOT_COUNT && occupied[index]) index++
                val start = index
                while (index < SLOT_COUNT && !occupied[index]) index++
                val runLength = index - start
                if (runLength >= count) {
                    val waste = runLength - count
                    if (waste < bestWaste) {
                        bestWaste = waste
                        bestStart = start
                    }
                }
            }
            return bestStart
        }

        fun freeRuns(minimumMinutes: Int): List<Pair<Int, Int>> {
            val runs = mutableListOf<Pair<Int, Int>>()
            var index = 0
            val minimumSlots = (minimumMinutes + SLOT_MINUTES - 1) / SLOT_MINUTES
            while (index < SLOT_COUNT) {
                while (index < SLOT_COUNT && occupied[index]) index++
                val start = index
                while (index < SLOT_COUNT && !occupied[index]) index++
                val count = index - start
                if (count >= minimumSlots) {
                    runs += (PLANNING_DAY_START_MINUTE + start * SLOT_MINUTES) to (count * SLOT_MINUTES)
                }
            }
            return runs
        }
    }

    private const val SLOT_MINUTES = 10
    private const val MAX_WHOLE_TASK_MINUTES = 120
    private const val MAX_SPLIT_CHUNK_MINUTES = 90
    private const val SLOT_COUNT = (PLANNING_DAY_END_MINUTE - PLANNING_DAY_START_MINUTE) / SLOT_MINUTES
}
