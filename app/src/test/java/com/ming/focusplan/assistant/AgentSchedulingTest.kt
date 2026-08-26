package com.ming.focusplan.assistant

import com.ming.focusplan.data.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AgentSchedulingTest {
    private val startDay = LocalDate.of(2026, 8, 26)

    @Test
    fun localPlannerKeepsPriorityOrderWithoutReservingBuffer() {
        val request = request(
            tasks = listOf(
                task("low", Priority.LOW, 60),
                task("high", Priority.HIGH, 60)
            )
        )

        val proposal = AgentSchedulePlanner.localPlan(request)
        val high = proposal.blocks.single { it.taskKey == "high" }
        val low = proposal.blocks.single { it.taskKey == "low" }

        assertTrue(high.startMinute < low.startMinute)
        assertTrue(proposal.buffers.isEmpty())
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun localPlannerDoesNotSplitTaskWhenNoWholeGapExists() {
        val request = request(
            tasks = listOf(task("large", Priority.HIGH, 300)),
            preset = AssistantPreset(
                windowStartMinute = 6 * 60,
                windowEndMinute = 13 * 60,
                excludedTimes = listOf(ExcludedTime("固定事项", 8 * 60, 9 * 60))
            )
        )

        val proposal = AgentSchedulePlanner.localPlan(request)
        assertTrue(proposal.blocks.none { it.taskKey == "large" })
        assertEquals("large", proposal.unscheduled.single().taskKey)
        assertTrue(proposal.unscheduled.single().reason.contains("不会拆分"))
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun preferredDayInPastFallsForwardToPlanningHorizon() {
        val request = request(
            tasks = listOf(task("old", Priority.MEDIUM, 50).copy(preferredDay = startDay.minusDays(2)))
        )

        val proposal = AgentSchedulePlanner.localPlan(request)

        assertEquals(0, proposal.blocks.single().dayOffset)
    }

    @Test
    fun validatorRejectsOverlappingTaskBlocks() {
        val tasks = listOf(task("a", Priority.HIGH, 60), task("b", Priority.MEDIUM, 60))
        val request = request(tasks)
        val proposal = AgentScheduleProposal(
            reply = "冲突测试",
            blocks = listOf(
                ProposedScheduleBlock("a", 0, 8 * 60, 60),
                ProposedScheduleBlock("b", 0, 8 * 60 + 30, 60)
            ),
            buffers = listOf(ProposedBuffer(0, 10 * 60, 60)),
            unscheduled = emptyList()
        )

        assertNotNull(AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun invalidModelProposalFallsBackToValidatedLocalPlan() {
        val request = request(listOf(task("one", Priority.HIGH, 50)))

        val proposal = AgentSchedulePlanner.resolve("not json", request)

        assertTrue(proposal.usedLocalFallback)
        assertNotNull(proposal.validationNote)
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun oldModelBlocksAreUsedOnlyAsOrderAndNeverAsSplits() {
        val request = request(listOf(task("one", Priority.HIGH, 50)))
        val raw = """{"r":"安排好了","b":[["one",0,480,20],["one",0,500,30]],"f":[[0,500,60]],"u":[]}"""

        val proposal = AgentSchedulePlanner.resolve(raw, request)

        assertTrue(!proposal.usedLocalFallback)
        assertEquals(1, proposal.blocks.size)
        assertEquals(50, proposal.blocks.single().minutes)
        assertEquals(6 * 60, proposal.blocks.single().startMinute)
        assertEquals(1, proposal.blocks.single().partCount)
        assertTrue(proposal.buffers.isEmpty())
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun schedulingPromptUsesCompactProtocol() {
        val prompt = AgentSchedulePlanner.prompt(request(listOf(task("one", Priority.HIGH, 50))))

        assertTrue(prompt.contains("T=[key,title,detail,priority(H/M/L),minutes,preferredDay或null]"))
        assertTrue(prompt.contains("\"o\":[[\"key\",day或null]]"))
        assertTrue(prompt.contains("不决定具体时间，不拆分任务"))
        assertTrue(!prompt.contains("\"b\":[["))
        assertTrue(!prompt.contains("\"f\""))
        assertTrue(!prompt.contains("\"taskKey\""))
    }

    @Test
    fun modelOrderControlsSamePriorityButExactTimesStayDeterministic() {
        val request = request(listOf(task("first", Priority.HIGH, 50), task("second", Priority.HIGH, 50)))

        val proposal = AgentSchedulePlanner.resolve(
            """{"r":"顺序安排","o":[["second",0],["first",0]]}""",
            request
        )

        assertTrue(!proposal.usedLocalFallback)
        assertEquals("second", proposal.blocks.sortedBy { it.startMinute }.first().taskKey)
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun overlappingLegacyModelTimesCannotCreateScheduleConflict() {
        val request = request(listOf(task("a", Priority.HIGH, 60), task("b", Priority.HIGH, 60)))

        val proposal = AgentSchedulePlanner.resolve(
            """{"r":"旧格式","b":[["a",0,480,60],["b",0,480,60]],"u":[]}""",
            request
        )

        assertTrue(!proposal.usedLocalFallback)
        assertEquals(listOf(6 * 60, 7 * 60), proposal.blocks.sortedBy { it.startMinute }.map { it.startMinute })
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun largerTaskUsesWholeGapBeforeSmallerTaskAtSamePriority() {
        val request = request(
            tasks = listOf(task("small", Priority.HIGH, 50), task("large", Priority.HIGH, 120)),
            preset = AssistantPreset(
                windowStartMinute = 6 * 60,
                windowEndMinute = 9 * 60 + 50,
                excludedTimes = listOf(ExcludedTime("固定事项", 8 * 60, 9 * 60))
            )
        )

        val proposal = AgentSchedulePlanner.localPlan(request)

        assertTrue(proposal.unscheduled.isEmpty())
        assertEquals(1, proposal.blocks.count { it.taskKey == "large" })
        assertEquals(1, proposal.blocks.count { it.taskKey == "small" })
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun taskCanFillAllAvailableTimeWithoutMandatoryBuffer() {
        val request = request(
            tasks = listOf(task("full", Priority.HIGH, 240)),
            preset = AssistantPreset(windowStartMinute = 6 * 60, windowEndMinute = 10 * 60)
        )

        val proposal = AgentSchedulePlanner.localPlan(request)

        assertEquals(240, proposal.blocks.single().minutes)
        assertTrue(proposal.unscheduled.isEmpty())
        assertTrue(proposal.buffers.isEmpty())
    }

    @Test
    fun futureDaysRespectRecurringPresetExclusions() {
        val preset = AssistantPreset(
            windowStartMinute = 8 * 60,
            windowEndMinute = 12 * 60,
            excludedTimes = listOf(ExcludedTime("晨间固定安排", 8 * 60, 9 * 60))
        )
        val request = SchedulingRequest(
            tasks = listOf(task("future", Priority.HIGH, 120).copy(preferredDay = startDay.plusDays(1))),
            existingBlocks = emptyList(),
            preset = preset,
            startDay = startDay,
            dayCount = 3,
            earliestMinuteToday = 8 * 60
        )

        val block = AgentSchedulePlanner.localPlan(request).blocks.single()

        assertEquals(1, block.dayOffset)
        assertEquals(9 * 60, block.startMinute)
        assertEquals(null, AgentSchedulePlanner.validateProposal(AgentSchedulePlanner.localPlan(request), request))
    }

    @Test
    fun overlappingPresetPeriodsAreUnionedOnFutureDays() {
        val preset = AssistantPreset(
            windowStartMinute = 6 * 60,
            windowEndMinute = 12 * 60,
            excludedTimes = listOf(
                ExcludedTime("固定安排一", 7 * 60, 9 * 60),
                ExcludedTime("固定安排二", 8 * 60, 10 * 60)
            )
        )
        val request = SchedulingRequest(
            tasks = listOf(task("future", Priority.HIGH, 120).copy(preferredDay = startDay.plusDays(1))),
            existingBlocks = emptyList(),
            preset = preset,
            startDay = startDay,
            dayCount = 3,
            earliestMinuteToday = 6 * 60
        )

        val block = AgentSchedulePlanner.localPlan(request).blocks.single()

        assertEquals(1, block.dayOffset)
        assertEquals(10 * 60, block.startMinute)
    }

    @Test
    fun schedulingPromptIncludesLongTermPreferenceAsSoftContext() {
        val preset = AssistantPreset(
            instructions = "上午优先数学，晚上安排背诵",
            windowStartMinute = 6 * 60,
            windowEndMinute = 18 * 60
        )

        val prompt = AgentSchedulePlanner.prompt(request(listOf(task("one", Priority.HIGH, 50)), preset))

        assertTrue(prompt.contains("PREF=\"上午优先数学，晚上安排背诵\""))
        assertTrue(prompt.contains("不能覆盖优先级、时间范围、排除时段和已有安排"))
        assertTrue(prompt.contains("每天重复生效"))
    }

    private fun request(
        tasks: List<SchedulingTask>,
        preset: AssistantPreset = AssistantPreset(windowStartMinute = 6 * 60, windowEndMinute = 18 * 60)
    ) = SchedulingRequest(
        tasks = tasks,
        existingBlocks = emptyList(),
        preset = preset,
        startDay = startDay,
        dayCount = 1,
        earliestMinuteToday = 6 * 60
    )

    private fun task(key: String, priority: Priority, minutes: Int) = SchedulingTask(
        key = key,
        title = key,
        detail = "",
        label = "未分类",
        priority = priority,
        minutes = minutes,
        createdAt = if (key == "high") 2 else 1
    )
}
