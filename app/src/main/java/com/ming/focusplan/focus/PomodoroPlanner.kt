package com.ming.focusplan.focus

import kotlin.math.abs

data class PomodoroSegment(
    val durationMinutes: Int,
    val isBreak: Boolean,
    val focusNumber: Int? = null
)

object PomodoroPlanner {
    fun plan(totalMinutes: Int): List<PomodoroSegment> {
        val minutes = totalMinutes.coerceAtLeast(1)
        val focus = focusDurationsWithin(minutes)
        return buildList {
            focus.forEachIndexed { index, duration ->
                add(PomodoroSegment(duration, isBreak = false, focusNumber = index + 1))
                if (index != focus.lastIndex) add(PomodoroSegment(10, isBreak = true))
            }
        }
    }

    fun totalMinutes(plan: List<PomodoroSegment>): Int = plan.sumOf { it.durationMinutes }

    fun isValidCustomPlan(plan: List<PomodoroSegment>, expectedMinutes: Int): Boolean {
        if (plan.isEmpty() || abs(totalMinutes(plan) - expectedMinutes) > 20 || plan.first().isBreak || plan.last().isBreak) return false
        return plan.zipWithNext().all { (left, right) -> left.isBreak != right.isBreak } &&
            plan.all { it.durationMinutes > 0 }
    }

    fun renumber(plan: List<PomodoroSegment>): List<PomodoroSegment> {
        var focusNumber = 0
        return plan.map { segment ->
            if (segment.isBreak) segment.copy(focusNumber = null)
            else segment.copy(focusNumber = ++focusNumber)
        }
    }

    private fun focusDurationsWithin(totalMinutes: Int): List<Int> {
        if (totalMinutes < 30) return listOf(totalMinutes)

        val count = ((totalMinutes + 10) / 30 downTo 2).firstOrNull { candidate ->
            val focusMinutes = totalMinutes - 10 * (candidate - 1)
            focusMinutes in (20 * candidate)..(30 * candidate)
        } ?: return listOf(totalMinutes)

        val focusMinutes = totalMinutes - 10 * (count - 1)
        val durations = MutableList(count) { 20 }
        var remaining = focusMinutes - durations.sum()
        for (index in durations.indices.reversed()) {
            if (remaining < 10) break
            durations[index] += 10
            remaining -= 10
        }
        var index = 0
        while (remaining > 0) {
            if (durations[index] < 30) {
                durations[index]++
                remaining--
            }
            index = (index + 1) % durations.size
        }
        return durations
    }
}
