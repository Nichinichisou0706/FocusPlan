package com.ming.focusplan.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PomodoroPlannerTest {
    @Test
    fun shortTaskUsesOnePomodoro() {
        assertEquals(listOf(25), PomodoroPlanner.plan(25).map { it.durationMinutes })
    }

    @Test
    fun breaksAreIncludedInsideTheTaskDuration() {
        assertEquals(listOf(20, 10, 20), PomodoroPlanner.plan(50).map { it.durationMinutes })
        assertEquals(listOf(30, 10, 30), PomodoroPlanner.plan(70).map { it.durationMinutes })
        assertEquals(listOf(20, 10, 20, 10, 30), PomodoroPlanner.plan(90).map { it.durationMinutes })
    }

    @Test
    fun tenMinuteBreaksAreInsertedBetweenFocusSegments() {
        val plan = PomodoroPlanner.plan(50)
        assertEquals(listOf(20, 10, 20), plan.map { it.durationMinutes })
        assertFalse(plan[0].isBreak)
        assertTrue(plan[1].isBreak)
        assertFalse(plan[2].isBreak)
    }

    @Test
    fun everyPresetExactlyFillsItsTaskBlock() {
        (1..240).forEach { minutes ->
            assertEquals(minutes, PomodoroPlanner.totalMinutes(PomodoroPlanner.plan(minutes)))
        }
    }

    @Test
    fun customPlanMustAlternateAndExactlyFillTheTask() {
        val valid = listOf(
            PomodoroSegment(20, false),
            PomodoroSegment(10, true),
            PomodoroSegment(20, false)
        )
        assertTrue(PomodoroPlanner.isValidCustomPlan(valid, 50))
        assertTrue(PomodoroPlanner.isValidCustomPlan(valid, 60))
        assertFalse(PomodoroPlanner.isValidCustomPlan(valid, 71))
        assertFalse(PomodoroPlanner.isValidCustomPlan(valid + PomodoroSegment(10, true), 60))
    }
}
