package com.ming.focusplan.assistant

import com.ming.focusplan.data.Priority
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

const val PLANNING_DAY_START_MINUTE = 6 * 60
const val PLANNING_DAY_END_MINUTE = 26 * 60

fun normalizePlanningMinute(value: Int): Int = if (value < PLANNING_DAY_START_MINUTE) value + 24 * 60 else value

data class ExcludedTime(
    val label: String,
    val startMinute: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
    val id: String = UUID.randomUUID().toString()
)

data class AssistantPreset(
    val instructions: String = "我正在准备考研。工作量估计要现实，任务名称写清资料、章节和动作。",
    val windowStartMinute: Int = PLANNING_DAY_START_MINUTE,
    val windowEndMinute: Int = PLANNING_DAY_END_MINUTE,
    val excludedTimes: List<ExcludedTime> = emptyList()
)

data class AssistantTaskSuggestion(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val detail: String = "",
    val label: String = "未分类",
    val priority: Priority = Priority.MEDIUM,
    val minutes: Int = 50,
    val dayOffset: Int = 0,
    val selected: Boolean = true
)

data class AssistantConversationMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class AssistantDraftBatch(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val sourcePrompt: String,
    val summary: String,
    val modelName: String,
    val tasks: List<AssistantTaskSuggestion>,
    val baseDate: String = LocalDate.now().toString(),
    val requestedWindowStart: Int? = null,
    val requestedWindowEnd: Int? = null
)

data class AssistantWorkspace(
    val messages: List<AssistantConversationMessage> = emptyList(),
    val draftBatches: List<AssistantDraftBatch> = emptyList()
)

data class AssistantPlan(
    val summary: String,
    val tasks: List<AssistantTaskSuggestion>,
    val requestedWindowStart: Int? = null,
    val requestedWindowEnd: Int? = null,
    val isPlanning: Boolean = true
)

object AssistantPlanParser {
    fun parse(raw: String): AssistantPlan {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型未返回任务 JSON" }
        val root = JSONObject(clean.substring(start, end + 1))
        val isPlanning = root.optString("type", "plan").lowercase() != "chat"
        val array = root.optJSONArray("tasks")
        val tasks = buildList {
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val title = item.optString("title").trim()
                    if (title.isBlank()) continue
                    add(
                        AssistantTaskSuggestion(
                            title = title,
                            detail = item.optString("detail").trim(),
                            label = item.optString("label").trim().ifBlank { "未分类" },
                            priority = parsePriority(item.optString("priority")),
                            minutes = item.optInt("minutes", 50).coerceIn(10, 480),
                            dayOffset = item.optInt("dayOffset", 0).coerceIn(0, 30)
                        )
                    )
                }
            }
        }
        if (isPlanning) require(tasks.isNotEmpty()) { "模型没有生成有效任务" }
        val requestedWindow = parseWindow(root.optString("scheduleStart"), root.optString("scheduleEnd"))
        return AssistantPlan(
            root.optString("reply").trim().ifBlank { root.optString("summary").trim() }.ifBlank {
                if (isPlanning) "已按你的描述拆分任务。" else "我在，有什么想聊的？"
            },
            tasks,
            requestedWindow?.first,
            requestedWindow?.second,
            isPlanning
        )
    }

    fun fallback(input: String): AssistantPlan {
        if (!looksLikePlanning(input)) {
            return AssistantPlan(
                summary = if (input.contains("你好") || input.contains("嗨")) "你好，我是你的规划助手。你可以和我聊天，也可以告诉我今天想完成什么。" else "我在。需要规划时，告诉我任务、可用时间或希望避开的时段。",
                tasks = emptyList(),
                isPlanning = false
            )
        }
        val parts = input.split(Regex("[；;，,、\\n]+"))
            .map(String::trim).filter(String::isNotBlank).take(6)
            .ifEmpty { listOf("整理今天的学习计划") }
        val requestedWindow = extractWindow(input)
        return AssistantPlan(
            summary = "模型暂不可用，已用本地规则生成草案；请在创建前检查名称和时长。",
            tasks = parts.map { AssistantTaskSuggestion(title = it.take(60), minutes = 50) },
            requestedWindowStart = requestedWindow?.first,
            requestedWindowEnd = requestedWindow?.second,
            isPlanning = true
        )
    }

    fun prompt(
        input: String,
        preset: AssistantPreset,
        existingTasks: List<String>,
        date: LocalDate,
        conversation: List<String> = emptyList(),
        currentDrafts: List<String> = emptyList()
    ): String = """
        你是考研任务拆解助手。用户已明确要求创建、安排或细化具体任务。
        今天日期：$date
        用户长期预设：${preset.instructions}
        今天允许安排：${formatMinute(preset.windowStartMinute)} 至 ${formatMinute(preset.windowEndMinute)}
        排除时段：${preset.excludedTimes.filter { it.enabled }.joinToString { "${it.label} ${formatMinute(it.startMinute)}-${formatMinute(it.endMinute)}" }.ifBlank { "无" }}
        已有未完成任务：${existingTasks.take(20).joinToString { it.take(60) }.ifBlank { "无" }}
        待确认草案：${currentDrafts.takeLast(12).joinToString { it.take(90) }.ifBlank { "无" }}
        最近对话：${conversation.joinToString(" | ").ifBlank { "无" }}
        本轮要求：$input

        只返回一个紧凑 JSON 对象，不要 Markdown或额外解释：
        {"type":"plan","reply":"说明总工作量、预计天数和分天理由","scheduleStart":"17:00或空","scheduleEnd":"20:00或空","tasks":[{"title":"具体任务名","detail":"步骤和完成标准","label":"标签","priority":"HIGH/MEDIUM/LOW","minutes":50,"dayOffset":0}]}
        dayOffset=0表示$date，1表示次日，以此类推，最多30。先估算完成整个目标真正需要的总时间；大任务必须按知识依赖和每日负荷拆到多天，每日学习任务原则上不超过360分钟，不得为了塞进一天而低估时长。只有用户明确要求当天全部完成时才集中到一天。最多12项。若用户要求细化上一批，参考待确认草案重新拆细。仅在本轮明确指定安排时段时填写开始和结束；minutes为10到480。排除吃饭、睡觉、游戏，不复制已有任务，不确定的信息写入detail提醒核对。
    """.trimIndent()

    fun distributeAcrossDays(plan: AssistantPlan, preset: AssistantPreset, forceSingleDay: Boolean = false): AssistantPlan {
        if (!plan.isPlanning || plan.tasks.isEmpty() || forceSingleDay || plan.tasks.any { it.dayOffset > 0 }) return plan
        val availableMinutes = usableMinutesPerDay(preset).coerceAtMost(DEFAULT_DAILY_STUDY_LIMIT)
        if (availableMinutes < 10) return plan
        val expanded = plan.tasks.flatMap { task ->
            if (task.minutes <= availableMinutes) listOf(task)
            else {
                val partCount = (task.minutes + availableMinutes - 1) / availableMinutes
                var remaining = task.minutes
                List(partCount) { index ->
                    val partMinutes = minOf(availableMinutes, remaining)
                    remaining -= partMinutes
                    task.copy(
                        id = UUID.randomUUID().toString(),
                        title = "${task.title}（第${index + 1}/$partCount 部分）",
                        detail = task.detail.ifBlank { "按连续阶段完成并记录进度。" },
                        minutes = partMinutes
                    )
                }
            }
        }
        val totalWithBreaks = expanded.sumOf { it.minutes } + (expanded.size - 1).coerceAtLeast(0) * 10
        if (totalWithBreaks <= availableMinutes) return plan.copy(tasks = expanded)
        var day = 0
        var used = 0
        val distributed = expanded.map { task ->
            val required = task.minutes + if (used == 0) 0 else 10
            if (used > 0 && used + required > availableMinutes) {
                day += 1
                used = 0
            }
            used += task.minutes + if (used == 0) 0 else 10
            task.copy(dayOffset = day.coerceAtMost(30))
        }
        val days = distributed.maxOf { it.dayOffset } + 1
        return plan.copy(
            summary = "${plan.summary} 已按每日不超过约${availableMinutes}分钟调整为$days 天完成。",
            tasks = distributed
        )
    }

    fun chatPrompt(input: String, conversation: List<String>): String = """
        你是简洁自然的考研规划助手。现在只进行正常对话，不创建任务、不输出JSON。
        最近对话：${conversation.takeLast(6).joinToString(" | ").ifBlank { "无" }}
        用户：$input
    """.trimIndent()

    fun isPlanningRequest(input: String): Boolean {
        val normalized = input.lowercase()
        if (listOf("安排", "创建任务", "加入时间轴", "生成任务", "拆成任务", "细化任务", "任务草案", "制定计划", "排个计划").any(normalized::contains)) return true
        val action = listOf("学习", "复习", "背", "做题", "阅读", "完成", "看完", "刷题", "整理").any(normalized::contains)
        val concrete = listOf("今天", "明天", "上午", "下午", "晚上", "点到", "小时", "分钟", "第", "章", "节", "篇", "套", "我要", "我想", "准备", "打算", "需要").any(normalized::contains)
        return action && concrete
    }

    fun formatMinute(value: Int): String {
        val day = Math.floorDiv(value, 24 * 60)
        val minute = Math.floorMod(value, 24 * 60)
        val clock = "%02d:%02d".format(minute / 60, minute % 60)
        return if (day > 0) "次日$clock" else clock
    }

    private fun parsePriority(value: String): Priority = when (value.trim().uppercase()) {
        "HIGH", "高" -> Priority.HIGH
        "LOW", "低" -> Priority.LOW
        else -> Priority.MEDIUM
    }

    private fun looksLikePlanning(input: String): Boolean = isPlanningRequest(input)

    private fun usableMinutesPerDay(preset: AssistantPreset): Int {
        val windowStart = preset.windowStartMinute
        val windowEnd = preset.windowEndMinute
        val excluded = preset.excludedTimes.filter { it.enabled }
            .mapNotNull { period ->
                val start = maxOf(windowStart, period.startMinute)
                val end = minOf(windowEnd, period.endMinute)
                (start to end).takeIf { end > start }
            }
            .sortedBy { it.first }
        var excludedTotal = 0
        var currentStart = -1
        var currentEnd = -1
        excluded.forEach { (start, end) ->
            if (currentStart < 0) {
                currentStart = start
                currentEnd = end
            } else if (start <= currentEnd) currentEnd = maxOf(currentEnd, end)
            else {
                excludedTotal += currentEnd - currentStart
                currentStart = start
                currentEnd = end
            }
        }
        if (currentStart >= 0) excludedTotal += currentEnd - currentStart
        return (windowEnd - windowStart - excludedTotal).coerceAtLeast(0)
    }

    private fun parseWindow(startText: String, endText: String): Pair<Int, Int>? {
        val start = parseClock(startText) ?: return null
        val rawEnd = parseClock(endText) ?: return null
        return start to if (rawEnd <= start) rawEnd + 24 * 60 else rawEnd
    }

    private fun extractWindow(input: String): Pair<Int, Int>? {
        val match = Regex("(\\d{1,2})(?:[:点](\\d{1,2})?分?)?\\s*(?:到|至|[-—])\\s*(\\d{1,2})(?:[:点](\\d{1,2})?分?)?").find(input) ?: return null
        val startHour = match.groupValues[1].toIntOrNull() ?: return null
        val startMinute = match.groupValues[2].toIntOrNull() ?: 0
        val endHour = match.groupValues[3].toIntOrNull() ?: return null
        val endMinute = match.groupValues[4].toIntOrNull() ?: 0
        if (startHour !in 0..23 || endHour !in 0..23 || startMinute !in 0..59 || endMinute !in 0..59) return null
        val start = startHour * 60 + startMinute
        val rawEnd = endHour * 60 + endMinute
        return start to if (rawEnd <= start) rawEnd + 24 * 60 else rawEnd
    }

    private fun parseClock(value: String): Int? {
        val parts = value.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    private const val DEFAULT_DAILY_STUDY_LIMIT = 6 * 60
}
