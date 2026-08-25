package com.ming.focusplan.planning

import com.ming.focusplan.data.Priority
import com.ming.focusplan.data.ScheduleBlockEntity
import com.ming.focusplan.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlannerTest {
    @Test
    fun highPriorityTasksAreScheduledFirstWithBreaks() {
        val tasks = listOf(
            TaskEntity(id = 1, title = "英语", priority = Priority.LOW.rank, estimatedMinutes = 30),
            TaskEntity(id = 2, title = "数学", priority = Priority.HIGH.rank, estimatedMinutes = 50)
        )

        val result = Planner.plan(tasks, LocalDate.of(2026, 8, 24))

        assertEquals(listOf("数学", "英语"), result.map { it.title })
        assertEquals(10, (result[1].startAt - result[0].endAt) / 60_000)
    }

    @Test
    fun completedAndOverflowTasksAreNotScheduled() {
        val tasks = listOf(
            TaskEntity(id = 1, title = "完成项", completed = true),
            TaskEntity(id = 2, title = "超长任务", estimatedMinutes = 16 * 60)
        )
        assertTrue(Planner.plan(tasks, LocalDate.of(2026, 8, 24)).isEmpty())
    }

    @Test
    fun availablePlannerKeepsExistingBlocksAndUsesOvernightWindow() {
        val day = LocalDate.of(2026, 8, 24)
        val zone = java.time.ZoneId.systemDefault()
        val six = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val existing = listOf(ScheduleBlockEntity(title = "已有安排", startAt = six, endAt = six + 50 * 60_000L))
        val tasks = listOf(TaskEntity(id = 7, title = "新增任务", estimatedMinutes = 30))

        val result = Planner.planAvailable(tasks, existing, day)

        assertEquals(six + 50 * 60_000L, result.single().startAt)
        assertEquals("新增任务", result.single().title)
    }

    @Test
    fun assistantPlannerHonorsWindowExclusionsAndExistingBlocks() {
        val day = LocalDate.of(2026, 8, 25)
        val zone = java.time.ZoneId.systemDefault()
        fun at(hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val tasks = listOf(
            TaskEntity(id = 11, title = "高优先", priority = Priority.HIGH.rank, estimatedMinutes = 50),
            TaskEntity(id = 12, title = "普通", priority = Priority.MEDIUM.rank, estimatedMinutes = 30)
        )
        val existing = listOf(ScheduleBlockEntity(title = "已有", startAt = at(10), endAt = at(11)))

        val result = Planner.planAvailableInWindow(
            tasks = tasks,
            existing = existing,
            day = day,
            windowStartMinute = 9 * 60,
            windowEndMinute = 16 * 60,
            excludedMinutes = listOf(13 * 60 to 15 * 60),
            earliestMinute = 9 * 60
        )

        assertEquals(at(9), result[0].startAt)
        assertEquals(at(11), result[1].startAt)
        assertTrue(result.none { it.startAt < at(15) && it.endAt > at(13) })
    }
}
