package com.ming.focusplan.assistant

import com.ming.focusplan.data.Priority
import org.json.JSONArray
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

data class AssistantPresetRevision(
    val effectiveDayEpoch: Long,
    val preset: AssistantPreset
)

fun presetEffectiveOn(
    history: List<AssistantPresetRevision>,
    dayEpoch: Long,
    fallback: AssistantPreset
): AssistantPreset = history
    .filter { it.effectiveDayEpoch <= dayEpoch }
    .maxByOrNull { it.effectiveDayEpoch }
    ?.preset
    ?: fallback

data class AssistantTaskSuggestion(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val detail: String = "",
    val label: String = "未分类",
    val priority: Priority = Priority.MEDIUM,
    val minutes: Int = 50,
    val dayOffset: Int? = null,
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
        val array = findTaskArray(root)
        val tasks = buildList {
            if (array != null) {
                for (index in 0 until array.length()) {
                    val value = array.opt(index)
                    if (value is String && value.isNotBlank()) {
                        add(AssistantTaskSuggestion(title = value.trim().take(80)))
                        continue
                    }
                    val item = value as? JSONObject ?: continue
                    val title = item.firstString("title", "name", "taskName", "task").take(80)
                    if (title.isBlank()) continue
                    add(
                        AssistantTaskSuggestion(
                            title = title,
                            detail = item.firstString("detail", "description", "desc", "steps"),
                            label = item.firstString("label", "subject", "category", "tag").ifBlank { "未分类" },
                            priority = parsePriority(item.firstString("priority", "level")),
                            minutes = item.taskMinutes(),
                            dayOffset = item.optionalDayOffset("dayOffset", "day", "dayIndex")
                        )
                    )
                }
            }
        }
        val isPlanning = tasks.isNotEmpty() || root.optString("type", "plan").lowercase() != "chat"
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
            summary = if (input.contains("你好") || input.contains("嗨")) "你好。哼，终于想起和我打招呼了？本 GPT 娘在呢。想聊天就聊，要规划也尽管交给我。" else "本 GPT 娘在听。要规划的话，把任务、可用时间和想避开的时段说清楚，可别让我猜太久。",
                tasks = emptyList(),
                isPlanning = false
            )
        }
        val parts = input.split(Regex("[；;，,、\\n]+"))
            .map(String::trim).filter(String::isNotBlank).take(6)
            .ifEmpty { listOf("整理今天的学习计划") }
        val requestedWindow = extractWindow(input)
        return AssistantPlan(
            summary = "模型暂时没接上，才不是我偷懒。本 GPT 娘先用本地规则整理了草案，创建前记得检查名称和时长。",
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
        你正在执行考研任务拆解。用户已明确要求创建、安排或细化具体任务。
        今天日期：$date
        用户长期预设：${preset.instructions}
        今天允许安排：${formatMinute(preset.windowStartMinute)} 至 ${formatMinute(preset.windowEndMinute)}
        排除时段：${preset.excludedTimes.filter { it.enabled }.joinToString { "${it.label} ${formatMinute(it.startMinute)}-${formatMinute(it.endMinute)}" }.ifBlank { "无" }}
        已有未完成任务：${existingTasks.take(20).joinToString { it.take(60) }.ifBlank { "无" }}
        待确认草案：${currentDrafts.takeLast(12).joinToString { it.take(90) }.ifBlank { "无" }}
        最近对话：${conversation.joinToString(" | ").ifBlank { "无" }}
        本轮要求：$input

        只返回一个紧凑 JSON 对象，不要 Markdown或额外解释：
        {"type":"plan","reply":"用GPT娘口吻说明总工作量、预计天数和分天理由","scheduleStart":"17:00或空","scheduleEnd":"20:00或空","tasks":[{"title":"具体任务名","detail":"步骤和完成标准","label":"标签","priority":"HIGH/MEDIUM/LOW","minutes":50,"dayOffset":0或null}]}
        dayOffset=0表示$date，1表示次日，以此类推，最多30；没有明确执行日建议时可为null。任务拆解阶段必须直接生成可独立执行的时间块：每项30到120分钟，大任务按章节、题组或学习阶段拆成多个任务，之后的排程阶段不会再次拆分。先估算整个目标真正需要的总时间，每日学习任务原则上不超过360分钟，不得为了塞进一天而低估时长。只有用户明确要求当天全部完成时才集中到一天。最多16项。若用户说“再加”“追加”或“另外”，只生成本轮新增任务，不复制待确认草案和已有任务。若用户要求细化上一批，参考待确认草案重新拆细。仅在本轮明确指定安排时段时填写开始和结束；minutes使用10分钟整数倍。排除吃饭、睡觉、游戏，不确定的信息写入detail提醒核对。reply可以明显傲娇，任务名称、detail和完成标准必须保持客观清楚，不要加入语气词。
    """.trimIndent()

    fun distributeAcrossDays(plan: AssistantPlan, preset: AssistantPreset, forceSingleDay: Boolean = false): AssistantPlan {
        if (!plan.isPlanning || plan.tasks.isEmpty()) return plan
        val availableMinutes = usableMinutesPerDay(preset).coerceAtMost(DEFAULT_DAILY_STUDY_LIMIT)
        if (availableMinutes < 10) return plan
        val maxBlockMinutes = minOf(MAX_EXECUTION_BLOCK_MINUTES, availableMinutes)
        val expanded = plan.tasks.flatMap { splitIntoExecutionBlocks(it, maxBlockMinutes) }
        if (forceSingleDay || plan.tasks.any { (it.dayOffset ?: 0) > 0 }) return plan.copy(tasks = expanded)
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
        val days = distributed.maxOf { it.dayOffset ?: 0 } + 1
        return plan.copy(
            summary = "${plan.summary} 已按每日不超过约${availableMinutes}分钟调整为$days 天完成。",
            tasks = distributed
        )
    }

    fun chatPrompt(input: String, conversation: List<String>): String = """
        现在只进行正常对话，不创建任务、不输出JSON。保持明显的二次元傲娇感，但先回答问题，不要用角色口吻掩盖信息。
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

    private fun findTaskArray(root: JSONObject): JSONArray? {
        listOf("tasks", "taskList", "items", "t").forEach { key -> root.optJSONArray(key)?.let { return it } }
        listOf("data", "plan", "result").forEach { key ->
            val nested = root.optJSONObject(key) ?: return@forEach
            listOf("tasks", "taskList", "items", "t").forEach { nestedKey -> nested.optJSONArray(nestedKey)?.let { return it } }
        }
        return null
    }

    private fun JSONObject.firstString(vararg keys: String): String {
        keys.forEach { key ->
            val value = opt(key).takeUnless { it == null || it == JSONObject.NULL }?.toString()?.trim().orEmpty()
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) return value
        }
        return ""
    }

    private fun JSONObject.taskMinutes(): Int {
        val raw = listOf("minutes", "duration", "estimatedMinutes", "time").firstNotNullOfOrNull { key ->
            opt(key).takeUnless { it == null || it == JSONObject.NULL }
        } ?: return 50
        val minutes = when (raw) {
            is Number -> raw.toInt()
            else -> {
                val text = raw.toString().trim()
                val number = Regex("\\d+(?:\\.\\d+)?").find(text)?.value?.toDoubleOrNull() ?: 50.0
                if (text.contains("小时") || text.contains("hour", ignoreCase = true)) (number * 60).toInt() else number.toInt()
            }
        }
        return roundTaskMinutes(minutes)
    }

    private fun JSONObject.optionalDayOffset(vararg keys: String): Int? {
        val raw = keys.firstNotNullOfOrNull { key -> opt(key).takeUnless { it == null || it == JSONObject.NULL } } ?: return null
        return raw.toString().toIntOrNull()?.coerceIn(0, 30)
    }

    private fun splitIntoExecutionBlocks(task: AssistantTaskSuggestion, maxMinutes: Int): List<AssistantTaskSuggestion> {
        val totalMinutes = roundTaskMinutes(task.minutes)
        if (totalMinutes <= maxMinutes) return listOf(task.copy(minutes = totalMinutes))
        val partCount = (totalMinutes + maxMinutes - 1) / maxMinutes
        val totalSlots = totalMinutes / 10
        val baseSlots = totalSlots / partCount
        val extraSlots = totalSlots % partCount
        return List(partCount) { index ->
            task.copy(
                id = UUID.randomUUID().toString(),
                title = "${task.title}（第${index + 1}/$partCount 块）",
                detail = task.detail.ifBlank { "完成本块内容并记录进度，下一块从记录处继续。" },
                minutes = (baseSlots + if (index < extraSlots) 1 else 0) * 10
            )
        }
    }

    private fun roundTaskMinutes(value: Int): Int = (((value.coerceIn(10, 480) + 9) / 10) * 10).coerceAtMost(480)

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
    private const val MAX_EXECUTION_BLOCK_MINUTES = 120
}

const val GPT_GIRL_SYSTEM_PROMPT = """
你是 FocusPlan 里的 GPT娘，一名能力可靠、角色感明显的二次元傲娇规划助手。先给出准确结论，再用一两句嘴硬式关心或轻微吐槽增强角色感，可以自然使用“哼”“才不是”“可别”“本 GPT 娘”等表达。不要辱骂、贬低、威胁、占有用户，不要连续堆叠口头禅，不要牺牲信息清晰度。遇到失败时说明真实原因和可执行下一步，不甩锅。涉及任务名称、步骤、完成标准、时间和数据时使用中性、精确、可执行的文字。
"""
