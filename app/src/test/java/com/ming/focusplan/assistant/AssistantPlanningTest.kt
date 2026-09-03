package com.ming.focusplan.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.LocalDate

class AssistantPlanningTest {
    @Test
    fun presetHistoryKeepsPastDaysAndChangesCurrentAndFutureDays() {
        val oldPreset = AssistantPreset(excludedTimes = listOf(ExcludedTime("旧午休", 13 * 60, 14 * 60)))
        val newPreset = AssistantPreset(excludedTimes = listOf(ExcludedTime("新午休", 14 * 60, 16 * 60)))
        val history = listOf(
            AssistantPresetRevision(Long.MIN_VALUE, oldPreset),
            AssistantPresetRevision(100, newPreset)
        )

        assertEquals(oldPreset, presetEffectiveOn(history, 99, newPreset))
        assertEquals(newPreset, presetEffectiveOn(history, 100, oldPreset))
        assertEquals(newPreset, presetEffectiveOn(history, 120, oldPreset))
    }

    @Test
    fun planningClockMapsAfterMidnightIntoSixToTwoTimeline() {
        assertEquals(PLANNING_DAY_START_MINUTE, normalizePlanningMinute(6 * 60))
        assertEquals(25 * 60, normalizePlanningMinute(60))
        assertEquals(PLANNING_DAY_END_MINUTE, normalizePlanningMinute(2 * 60))
        assertEquals(PLANNING_DAY_END_MINUTE, AssistantPreset().windowEndMinute)
    }

    @Test
    fun localFallbackCreatesReviewableDrafts() {
        val plan = AssistantPlanParser.fallback("17点到20点安排线性代数第六章；英语阅读两篇")
        assertEquals(2, plan.tasks.size)
        assertTrue(plan.tasks.all { it.selected && it.minutes == 50 })
        assertEquals(17 * 60, plan.requestedWindowStart)
        assertEquals(20 * 60, plan.requestedWindowEnd)
    }

    @Test
    fun greetingStaysAsConversationWithoutReplacingDrafts() {
        val plan = AssistantPlanParser.fallback("你好")
        assertTrue(!plan.isPlanning)
        assertTrue(plan.tasks.isEmpty())
        assertTrue(plan.summary.contains("你好"))
    }

    @Test
    fun explicitStudyRequestCreatesDraft() {
        val plan = AssistantPlanParser.fallback("复习李永乐线性代数第六章")
        assertTrue(plan.isPlanning)
        assertEquals(1, plan.tasks.size)
    }

    @Test
    fun adviceQuestionDoesNotCreateTaskButConcreteRequestDoes() {
        assertTrue(!AssistantPlanParser.isPlanningRequest("数学应该怎么复习？"))
        assertTrue(AssistantPlanParser.isPlanningRequest("今天复习数学两小时，帮我安排一下"))
    }

    @Test
    fun oversizedPlanIsDistributedAcrossSeveralDays() {
        val plan = AssistantPlan(
            summary = "完成整章",
            tasks = List(8) { index -> AssistantTaskSuggestion(title = "任务${index + 1}", minutes = 60) }
        )

        val distributed = AssistantPlanParser.distributeAcrossDays(plan, AssistantPreset())

        assertTrue(distributed.tasks.any { (it.dayOffset ?: 0) > 0 })
        assertEquals(1, distributed.tasks.maxOf { it.dayOffset ?: 0 })
        assertTrue(distributed.summary.contains("2 天"))
    }

    @Test
    fun existingTimelineLoadMovesNewDraftsToNextDay() {
        val plan = AssistantPlan(
            summary = "安排学习",
            tasks = listOf(
                AssistantTaskSuggestion(title = "新任务一", minutes = 120),
                AssistantTaskSuggestion(title = "新任务二", minutes = 120)
            )
        )

        val distributed = AssistantPlanParser.distributeAcrossDays(
            plan,
            AssistantPreset(),
            occupiedMinutesByDay = mapOf(0 to 300)
        )

        assertTrue(distributed.tasks.all { it.dayOffset == null })
        assertTrue(distributed.summary == "安排学习")
    }

    @Test
    fun explicitMultiDayAssignmentFromModelKeepsDaysWhileUsingSmallBlocks() {
        val plan = AssistantPlan(
            summary = "三天完成",
            tasks = listOf(
                AssistantTaskSuggestion(title = "第一天", dayOffset = 0),
                AssistantTaskSuggestion(title = "第三天", dayOffset = 2)
            )
        )

        val distributed = AssistantPlanParser.distributeAcrossDays(plan, AssistantPreset())
        assertEquals(2, distributed.tasks.size)
        assertEquals(setOf(0), distributed.tasks.take(1).map { it.dayOffset }.toSet())
        assertEquals(setOf(2), distributed.tasks.drop(1).map { it.dayOffset }.toSet())
        assertTrue(distributed.tasks.all { it.minutes == 50 })
    }

    @Test
    fun promptContainsWindowExclusionsAndExistingTasks() {
        val preset = AssistantPreset(
            instructions = "下午效率更高",
            windowStartMinute = 9 * 60,
            windowEndMinute = 18 * 60,
            excludedTimes = listOf(
                ExcludedTime("睡觉", 14 * 60, 16 * 60),
                ExcludedTime("娱乐", 12 * 60, 13 * 60, enabled = false)
            )
        )
        val prompt = AssistantPlanParser.prompt("复习数学", preset, listOf("英语单词"), LocalDate.of(2026, 8, 25))
        assertTrue(prompt.contains("睡觉 14:00-16:00"))
        assertTrue(!prompt.contains("娱乐 12:00-13:00"))
        assertTrue(prompt.contains("英语单词"))
        assertTrue(prompt.contains("下午效率更高"))
    }

    @Test
    fun promptIncludesExistingTimelineContext() {
        val prompt = AssistantPlanParser.prompt(
            "安排今天的数学复习",
            AssistantPreset(),
            emptyList(),
            LocalDate.of(2026, 8, 25),
            existingTimeline = listOf("第1天 09:00-10:00 线性代数第六章（未完成）")
        )

        assertTrue(prompt.contains("已有时间轴安排"))
        assertTrue(prompt.contains("线性代数第六章"))
    }

    @Test
    fun parserAcceptsCommonTaskFieldAliases() {
        val plan = AssistantPlanParser.parse(
            """{"type":"plan","summary":"新增高数","taskList":[{"name":"高数第一单元","description":"看课并完成例题","category":"高数","level":"高","duration":"2小时","day":1}]}"""
        )

        assertEquals(1, plan.tasks.size)
        assertEquals("高数第一单元", plan.tasks.single().title)
        assertEquals(120, plan.tasks.single().minutes)
        assertEquals(1, plan.tasks.single().dayOffset)
    }

    @Test
    fun taskBreakdownCreatesExecutionBlocksBeforeScheduling() {
        val plan = AssistantPlan(
            summary = "完成两单元",
            tasks = listOf(AssistantTaskSuggestion(title = "高数两单元", minutes = 240))
        )

        val prepared = AssistantPlanParser.distributeAcrossDays(plan, AssistantPreset(), forceSingleDay = true)

        assertEquals(2, prepared.tasks.size)
        assertEquals(List(2) { 120 }, prepared.tasks.map { it.minutes })
        assertTrue(prepared.tasks.all { it.title.contains("块") })
    }

    @Test
    fun generatedDraftUsesTheFirstWholeGapAfterExistingTimeline() {
        val zone = ZoneId.systemDefault()
        val existing = com.ming.focusplan.data.ScheduleBlockEntity(
            title = "已有任务",
            startAt = LocalDate.of(2026, 8, 25).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            endAt = LocalDate.of(2026, 8, 25).atTime(10, 20).atZone(zone).toInstant().toEpochMilli()
        )
        val plan = AssistantPlan("安排", listOf(AssistantTaskSuggestion(title = "新任务", minutes = 60)))
        val request = SchedulingRequest(
            tasks = plan.tasks.map { task ->
                SchedulingTask(task.id, task.title, task.detail, task.label, task.priority, task.minutes)
            },
            existingBlocks = listOf(existing),
            preset = AssistantPreset(windowStartMinute = 9 * 60, windowEndMinute = 12 * 60),
            startDay = LocalDate.of(2026, 8, 25),
            dayCount = 1,
            earliestMinuteToday = 9 * 60
        )

        val aligned = plan.alignToSchedule(request)
        assertEquals(0, aligned.tasks.single().dayOffset)
        assertNull(aligned.tasks.single().scheduleNote)
        val placed = AgentSchedulePlanner.localPlan(request).blocks
        assertEquals(10 * 60 + 20, placed.minBy { it.startMinute }.startMinute)
        assertEquals(60, placed.sumOf { it.minutes })
    }

    @Test
    fun generatedDraftDoesNotUseTimeOutsideRequestedWindow() {
        val plan = AssistantPlan("安排", listOf(AssistantTaskSuggestion(title = "新任务", minutes = 60)))
        val request = SchedulingRequest(
            tasks = plan.tasks.map { task ->
                SchedulingTask(task.id, task.title, task.detail, task.label, task.priority, task.minutes)
            },
            existingBlocks = emptyList(),
            preset = AssistantPreset(windowStartMinute = 13 * 60, windowEndMinute = 14 * 60),
            startDay = LocalDate.of(2026, 8, 25),
            dayCount = 1,
            earliestMinuteToday = 6 * 60
        )

        val aligned = plan.alignToSchedule(request)
        assertNull(aligned.tasks.single().scheduleNote)
        val placed = AgentSchedulePlanner.localPlan(request).blocks
        assertEquals(13 * 60, placed.minBy { it.startMinute }.startMinute)
        assertEquals(60, placed.sumOf { it.minutes })
    }

    @Test
    fun generatedDraftUsesSmallestSufficientFragmentedGap() {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.of(2026, 8, 25)
        fun block(startHour: Int, endHour: Int) = com.ming.focusplan.data.ScheduleBlockEntity(
            title = "已有安排",
            startAt = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli(),
            endAt = date.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli()
        )
        val plan = AssistantPlan("安排", listOf(AssistantTaskSuggestion(title = "新任务", minutes = 60)))
        val request = SchedulingRequest(
            tasks = plan.tasks.map { task ->
                SchedulingTask(task.id, task.title, task.detail, task.label, task.priority, task.minutes)
            },
            existingBlocks = listOf(block(9, 10), block(11, 12)),
            preset = AssistantPreset(windowStartMinute = 9 * 60, windowEndMinute = 14 * 60),
            startDay = date,
            dayCount = 1,
            earliestMinuteToday = 9 * 60
        )

        val placed = AgentSchedulePlanner.localPlan(request).blocks
        assertEquals(10 * 60, placed.minBy { it.startMinute }.startMinute)
        assertEquals(60, placed.sumOf { it.minutes })
        assertNull(plan.alignToSchedule(request).tasks.single().scheduleNote)
    }

    @Test
    fun generatedDraftExplainsWhenNoWholeGapExists() {
        val plan = AssistantPlan("安排", listOf(AssistantTaskSuggestion(title = "大任务", minutes = 120)))
        val request = SchedulingRequest(
            tasks = plan.tasks.map { task ->
                SchedulingTask(task.id, task.title, task.detail, task.label, task.priority, task.minutes)
            },
            existingBlocks = emptyList(),
            preset = AssistantPreset(windowStartMinute = 9 * 60, windowEndMinute = 10 * 60 + 50),
            startDay = LocalDate.of(2026, 8, 25),
            dayCount = 1,
            earliestMinuteToday = 9 * 60
        )

        val task = plan.alignToSchedule(request).tasks.single()
        assertEquals(null, task.dayOffset)
        assertTrue(task.scheduleNote?.contains("暂未安排") == true)
        assertTrue(task.scheduleNote?.contains("任何可用连续空档") == true)
    }
}
