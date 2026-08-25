package com.ming.focusplan.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AssistantPlanningTest {
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

        assertTrue(distributed.tasks.any { it.dayOffset > 0 })
        assertEquals(1, distributed.tasks.maxOf { it.dayOffset })
        assertTrue(distributed.summary.contains("2 天"))
    }

    @Test
    fun explicitMultiDayAssignmentFromModelIsPreserved() {
        val plan = AssistantPlan(
            summary = "三天完成",
            tasks = listOf(
                AssistantTaskSuggestion(title = "第一天", dayOffset = 0),
                AssistantTaskSuggestion(title = "第三天", dayOffset = 2)
            )
        )

        assertEquals(plan, AssistantPlanParser.distributeAcrossDays(plan, AssistantPreset()))
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
}
