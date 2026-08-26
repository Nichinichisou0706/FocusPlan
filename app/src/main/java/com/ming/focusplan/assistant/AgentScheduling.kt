package com.ming.focusplan.assistant

import com.ming.focusplan.data.Priority
import com.ming.focusplan.data.ScheduleBlockEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class SchedulingTask(
    val key: String,
    val title: String,
    val detail: String,
    val label: String,
    val priority: Priority,
    val minutes: Int,
    val preferredDay: LocalDate? = null,
    val createdAt: Long = System.currentTimeMillis()
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
                )
            }
        }
        val occupiedJson = JSONArray().apply {
            request.existingBlocks.mapNotNull { blockPosition(it, request.startDay, request.dayCount) }.forEach { position ->
                put(JSONArray().put(position.first).put(position.second).put(position.third))
            }
        }
        val exclusionsJson = JSONArray().apply {
            request.preset.excludedTimes.filter { it.enabled }.forEach { period ->
                put(JSONArray().put(period.label.take(20)).put(period.startMinute).put(period.endMinute))
            }
        }
        val preferenceJson = JSONObject.quote(request.preset.instructions.take(300))
        return """
            你只负责决定任务顺序和建议执行日，不决定具体时间，不拆分任务，不改任务内容。
            D=${request.startDay}, N=${request.dayCount}, R=360..1560, NOW=${request.earliestMinuteToday}, WINDOW=[${request.preset.windowStartMinute},${request.preset.windowEndMinute}]
            PREF=$preferenceJson
            X=[label,start,end]：$exclusionsJson
            O=[day,start,minutes]：$occupiedJson
            T=[key,title,detail,priority(H/M/L),minutes,preferredDay或null]：$tasksJson
            规则：PREF是长期偏好，只能辅助同优先级任务排序和建议执行日，不能覆盖优先级、时间范围、排除时段和已有安排。X在规划期内每天重复生效。优先级从高到低；同优先级结合知识依赖、专注负荷和建议日给出顺序。每个key必须且只能出现一次。day为0到${request.dayCount - 1}或null，仅表示建议日；应用会根据真实空档确定具体开始时间，放不下的任务会保留在待办区。
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
        val modelDay = orderEntries.associate { it.taskKey to it.preferredDayOffset }
        Priority.entries.sortedByDescending { it.rank }.forEach { priority ->
            val tier = request.tasks.filter { it.priority == priority }.sortedWith(
                compareBy<SchedulingTask> { modelRank[it.key] ?: Int.MAX_VALUE }
                    .thenByDescending { roundToTen(it.minutes) }
                    .thenBy { it.preferredDay ?: request.startDay }
                    .thenBy { it.createdAt }
            )
            tier.forEach { task ->
                val modelPreferredDay = modelDay[task.key]
                    ?.takeIf { it in 0 until request.dayCount }
                    ?.let { request.startDay.plusDays(it.toLong()) }
                val scheduledTask = if (task.preferredDay == null && modelPreferredDay != null) task.copy(preferredDay = modelPreferredDay) else task
                val whole = placeWhole(scheduledTask, request, grids)
                if (whole == null) unscheduled += UnscheduledTask(
                    task.key,
                    "没有可容纳${roundToTen(task.minutes)}分钟的完整空档；排程阶段不会拆分任务"
                ) else result += whole
            }
        }
        val summary = reply.ifBlank {
            buildString {
                append("哼，本 GPT 娘已经按优先级把任务排进完整空档了。")
                if (unscheduled.isNotEmpty()) append(" 还有 ${unscheduled.size} 项没有足够长的连续空档，已留在待办区。")
            }
        }
        return AgentScheduleProposal(summary, result.sortedWith(compareBy({ it.dayOffset }, { it.startMinute })), emptyList(), unscheduled, usedLocalFallback)
    }

    private fun parseOrder(raw: String, request: SchedulingRequest): ScheduleOrder {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end > start) { "缺少JSON对象" }
        val root = JSONObject(clean.substring(start, end + 1))
        val knownKeys = request.tasks.mapTo(mutableSetOf()) { it.key }
        val entries = mutableListOf<ScheduleOrderEntry>()
        root.optJSONArray("o")?.let { order ->
            for (index in 0 until order.length()) {
                val arrayItem = order.optJSONArray(index)
                val objectItem = order.optJSONObject(index)
                val stringItem = order.opt(index) as? String
                val key = arrayItem?.optString(0)?.trim().orEmpty()
                    .ifBlank { objectItem?.optString("taskKey")?.trim().orEmpty() }
                    .ifBlank { objectItem?.optString("key")?.trim().orEmpty() }
                    .ifBlank { stringItem?.trim().orEmpty() }
                val dayValue = arrayItem?.opt(1).takeUnless { it == null || it == JSONObject.NULL }
                    ?: objectItem?.opt("dayOffset").takeUnless { it == null || it == JSONObject.NULL }
                    ?: objectItem?.opt("day").takeUnless { it == null || it == JSONObject.NULL }
                val day = dayValue?.toString()?.toIntOrNull()
                if (key in knownKeys && entries.none { it.taskKey == key }) entries += ScheduleOrderEntry(key, day)
            }
        }
        if (entries.isEmpty()) {
            // Upgrade compatibility: keep only order/day from the old block protocol and ignore its times and splits.
            val blocks = root.optJSONArray("b") ?: root.optJSONArray("blocks")
            if (blocks != null) for (index in 0 until blocks.length()) {
                val arrayItem = blocks.optJSONArray(index)
                val objectItem = blocks.optJSONObject(index)
                val key = arrayItem?.optString(0)?.trim().orEmpty().ifBlank { objectItem?.optString("taskKey")?.trim().orEmpty() }
                val day = arrayItem?.opt(1)?.toString()?.toIntOrNull() ?: objectItem?.optInt("dayOffset")
                if (key in knownKeys && entries.none { it.taskKey == key }) entries += ScheduleOrderEntry(key, day)
            }
        }
        require(entries.isNotEmpty()) { "模型没有返回有效任务顺序" }
        val missing = request.tasks.filter { task -> entries.none { it.taskKey == task.key } }
            .sortedWith(compareByDescending<SchedulingTask> { it.priority.rank }.thenByDescending { it.minutes }.thenBy { it.createdAt })
            .map { ScheduleOrderEntry(it.key, null) }
        return ScheduleOrder(
            reply = root.optString("r").ifBlank { root.optString("reply") }.ifBlank { "任务顺序已经整理好了，具体时间由空档规则落位。" },
            entries = entries + missing
        )
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
                if (parts.size != 1) return "任务 ${task.title} 在排程阶段被拆分"
                if (parts.single().partIndex != 1 || parts.single().partCount != 1) return "整块任务的分块编号错误"
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

    private fun placeWhole(task: SchedulingTask, request: SchedulingRequest, grids: List<DayGrid>): ProposedScheduleBlock? {
        val slots = roundToTen(task.minutes) / SLOT_MINUTES
        allowedDays(task, request).forEach { day ->
            val start = grids[day].findRun(slots) ?: return@forEach
            grids[day].mark(start, slots)
            return ProposedScheduleBlock(task.key, day, PLANNING_DAY_START_MINUTE + start * SLOT_MINUTES, slots * SLOT_MINUTES)
        }
        return null
    }

    private fun allowedDays(task: SchedulingTask, request: SchedulingRequest): List<Int> {
        val preferred = task.preferredDay?.let { ChronoUnit.DAYS.between(request.startDay, it).toInt() }
        return if (preferred == null) (0 until request.dayCount).toList()
        else if (preferred < 0) (0 until request.dayCount).toList()
        else if (preferred !in 0 until request.dayCount) emptyList()
        else (preferred until request.dayCount).toList()
    }

    private fun buildBaseGrids(request: SchedulingRequest): List<DayGrid> = List(request.dayCount) { dayOffset ->
        DayGrid().also { grid ->
            val day = request.startDay.plusDays(dayOffset.toLong())
            val windowStart = maxOf(request.preset.windowStartMinute, if (dayOffset == 0) request.earliestMinuteToday else PLANNING_DAY_START_MINUTE)
            grid.blockOutside(windowStart, request.preset.windowEndMinute)
            request.preset.excludedTimes.filter { it.enabled }.forEach { grid.block(it.startMinute, it.endMinute - it.startMinute) }
            request.existingBlocks.forEach { block ->
                blockPosition(block, request.startDay, request.dayCount)?.takeIf { it.first == dayOffset }?.let { position ->
                    grid.block(position.second, position.third)
                }
            }
        }
    }

    private fun roundToTen(value: Int): Int = ((value.coerceAtLeast(SLOT_MINUTES) + SLOT_MINUTES - 1) / SLOT_MINUTES) * SLOT_MINUTES

    private fun blockPosition(block: ScheduleBlockEntity, startDay: LocalDate, dayCount: Int): Triple<Int, Int, Int>? {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(block.startAt).atZone(zone)
        val planningDay = if (start.toLocalTime().isBefore(LocalTime.of(2, 0))) start.toLocalDate().minusDays(1) else start.toLocalDate()
        val dayOffset = ChronoUnit.DAYS.between(startDay, planningDay).toInt()
        if (dayOffset !in 0 until dayCount) return null
        val minute = start.hour * 60 + start.minute + if (start.toLocalTime().isBefore(LocalTime.of(2, 0))) 24 * 60 else 0
        val duration = ((block.endAt - block.startAt).coerceAtLeast(0L) / 60_000L).toInt()
        return Triple(dayOffset, minute, roundToTen(duration))
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
        fun findRun(count: Int): Int? = (0..SLOT_COUNT - count).firstOrNull { start ->
            (start until start + count).all { !occupied[it] }
        }
    }

    private const val SLOT_MINUTES = 10
    private const val SLOT_COUNT = (PLANNING_DAY_END_MINUTE - PLANNING_DAY_START_MINUTE) / SLOT_MINUTES
}
