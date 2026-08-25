package com.ming.focusplan.planning

import com.ming.focusplan.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalTime

data class DraftBlock(val taskId: Long, val title: String, val startAt: Long, val endAt: Long, val priority: Priority)

/** Deterministic local scheduler. AI may propose tasks; this class owns conflict-free placement. */
object Planner {
    fun plan(tasks: List<TaskEntity>, day: LocalDate = LocalDate.now()): List<DraftBlock> {
        val zone = ZoneId.systemDefault()
        var cursor = day.atTime(8, 0).atZone(zone)
        val finish = day.atTime(23, 0).atZone(zone)
        val result = mutableListOf<DraftBlock>()
        tasks.filterNot { it.completed }.sortedByDescending { it.priority }.forEach { task ->
            if (cursor.plusMinutes(task.estimatedMinutes.toLong()) > finish) return@forEach
            val end = cursor.plusMinutes(task.estimatedMinutes.toLong())
            result += DraftBlock(task.id, task.title, cursor.toEpochSecond() * 1000, end.toEpochSecond() * 1000, Priority.fromRank(task.priority))
            cursor = end.plusMinutes(10)
        }
        return result
    }

    fun planAvailable(
        tasks: List<TaskEntity>,
        existing: List<ScheduleBlockEntity>,
        day: LocalDate = LocalDate.now()
    ): List<DraftBlock> {
        val zone = ZoneId.systemDefault()
        val windowStart = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val windowEnd = day.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()
        val occupied = existing.map { it.startAt to it.endAt }.toMutableList()
        val result = mutableListOf<DraftBlock>()

        tasks.filterNot { it.completed }
            .sortedWith(compareByDescending<TaskEntity> { it.priority }.thenBy { it.createdAt })
            .forEach { task ->
                val duration = task.estimatedMinutes.coerceAtLeast(10) * 60_000L
                var cursor = windowStart
                while (cursor + duration <= windowEnd) {
                    val end = cursor + duration
                    val conflict = occupied
                        .filter { (startAt, endAt) -> cursor < endAt && end > startAt }
                        .minByOrNull { it.first }
                    if (conflict == null) {
                        result += DraftBlock(task.id, task.title, cursor, end, Priority.fromRank(task.priority))
                        occupied += cursor to end
                        break
                    }
                    cursor = roundUpToTenMinutes(conflict.second)
                }
            }
        return result
    }

    fun planAvailableInWindow(
        tasks: List<TaskEntity>,
        existing: List<ScheduleBlockEntity>,
        day: LocalDate,
        windowStartMinute: Int,
        windowEndMinute: Int,
        excludedMinutes: List<Pair<Int, Int>> = emptyList(),
        earliestMinute: Int = windowStartMinute
    ): List<DraftBlock> {
        val windowStart = atMinute(day, windowStartMinute)
        val windowEnd = atMinute(day, windowEndMinute)
        if (windowEnd <= windowStart) return emptyList()
        val earliest = atMinute(day, earliestMinute.coerceAtLeast(windowStartMinute)).coerceAtMost(windowEnd)
        val occupied = existing.map { it.startAt to it.endAt }.toMutableList()
        excludedMinutes.filter { it.second > it.first }.forEach { range ->
            occupied += atMinute(day, range.first) to atMinute(day, range.second)
        }
        val result = mutableListOf<DraftBlock>()

        tasks.filterNot { it.completed }
            .sortedWith(compareByDescending<TaskEntity> { it.priority }.thenBy { it.createdAt })
            .forEach { task ->
                val duration = task.estimatedMinutes.coerceAtLeast(10) * 60_000L
                var cursor = roundUpToTenMinutes(earliest)
                while (cursor + duration <= windowEnd) {
                    val end = cursor + duration
                    val conflict = occupied.filter { (startAt, endAt) -> cursor < endAt && end > startAt }.minByOrNull { it.first }
                    if (conflict == null) {
                        result += DraftBlock(task.id, task.title, cursor, end, Priority.fromRank(task.priority))
                        occupied += cursor to end
                        break
                    }
                    cursor = roundUpToTenMinutes(conflict.second.coerceAtLeast(windowStart))
                }
            }
        return result
    }

    private fun roundUpToTenMinutes(time: Long): Long {
        val step = 10 * 60_000L
        return ((time + step - 1) / step) * step
    }

    private fun atMinute(day: LocalDate, minuteOfDay: Int): Long {
        val dayOffset = Math.floorDiv(minuteOfDay, 24 * 60)
        val clockMinute = Math.floorMod(minuteOfDay, 24 * 60)
        return day.plusDays(dayOffset.toLong()).atTime(LocalTime.of(clockMinute / 60, clockMinute % 60))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun label(time: Long): String = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), ZoneId.systemDefault())
        .toLocalTime().toString().take(5)
}
