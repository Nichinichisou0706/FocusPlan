package com.ming.focusplan.assistant

import com.ming.focusplan.data.Priority
import com.ming.focusplan.data.ScheduleBlockEntity
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
        val high = proposal.blocks.filter { it.taskKey == "high" }.minBy { it.startMinute }
        val low = proposal.blocks.filter { it.taskKey == "low" }.minBy { it.startMinute }

        assertTrue(high.startMinute < low.startMinute)
        assertTrue(proposal.buffers.isEmpty())
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun localPlannerSplitsTaskAcrossAvailableGaps() {
        val request = request(
            tasks = listOf(task("large", Priority.HIGH, 300)),
            preset = AssistantPreset(
                windowStartMinute = 6 * 60,
                windowEndMinute = 13 * 60,
                excludedTimes = listOf(ExcludedTime("固定事项", 8 * 60, 9 * 60))
            )
        )

        val proposal = AgentSchedulePlanner.localPlan(request)
        val blocks = proposal.blocks.filter { it.taskKey == "large" }
        assertEquals(300, blocks.sumOf { it.minutes })
        assertTrue(blocks.all { it.minutes in 30..90 })
        assertTrue(proposal.unscheduled.isEmpty())
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun preferredDayInPastFallsForwardToPlanningHorizon() {
        val request = request(
            tasks = listOf(task("old", Priority.MEDIUM, 50).copy(preferredDay = startDay.minusDays(2)))
        )

        val proposal = AgentSchedulePlanner.localPlan(request)

        assertTrue(proposal.blocks.all { it.dayOffset == 0 })
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
        assertEquals(50, proposal.blocks.sumOf { it.minutes })
        assertEquals(6 * 60, proposal.blocks.minBy { it.startMinute }.startMinute)
        assertTrue(proposal.blocks.all { it.partCount == 1 })
        assertTrue(proposal.buffers.isEmpty())
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun modelOrderParserAcceptsCommonObjectAliasesAndTaskTitles() {
        val request = request(
            listOf(
                task("one", Priority.HIGH, 40).copy(title = "线性代数第一讲"),
                task("two", Priority.HIGH, 40).copy(title = "线性代数第二讲")
            )
        )

        val proposal = AgentSchedulePlanner.resolve(
            """说明文字\n```json\n{"order":[{"title":"线性代数第二讲","dayOffset":0},{"name":"线性代数第一讲","day":0}]}\n```\n""",
            request
        )

        assertTrue(!proposal.usedLocalFallback)
        assertEquals(listOf("one", "two"), proposal.blocks.sortedBy { it.startMinute }.map { it.taskKey })
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun modelOrderParserAcceptsTopLevelArrayAndPrefixStrippedKeys() {
        val request = request(listOf(task("task:one", Priority.HIGH, 40), task("task:two", Priority.HIGH, 40)))

        val proposal = AgentSchedulePlanner.resolve(
            "前缀说明 [{\"key\":\"one\"},{\"key\":\"two\"}] 后缀",
            request
        )

        assertTrue(!proposal.usedLocalFallback)
        assertEquals(listOf("task:one", "task:two"), proposal.blocks.sortedBy { it.startMinute }.map { it.taskKey })
    }

    @Test
    fun schedulingPromptUsesCompactProtocol() {
        val prompt = AgentSchedulePlanner.prompt(
            request(listOf(task("one", Priority.HIGH, 50))).copy(
                existingBlocks = listOf(
                    com.ming.focusplan.data.ScheduleBlockEntity(
                        title = "已有线性代数安排",
                        startAt = startDay.atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        endAt = startDay.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    )
                )
            )
        )

        assertTrue(prompt.contains("T=[key,title,detail,priority(H/M/L),minutes,preferredDay或null]"))
        assertTrue(prompt.contains("\"o\":[[\"key\",day或null]]"))
        assertTrue(prompt.contains("不决定具体时间"))
        assertTrue(prompt.contains("30/40分钟"))
        assertTrue(prompt.contains("已有线性代数安排"))
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
    fun numberedLessonsStayInNaturalOrderEvenWhenModelReversesThem() {
        val request = request(
            listOf(
                task("lesson-one", Priority.MEDIUM, 40).copy(title = "线性代数第一讲"),
                task("lesson-two", Priority.MEDIUM, 40).copy(title = "线性代数第二讲"),
                task("lesson-five", Priority.MEDIUM, 40).copy(title = "线性代数第五讲")
            )
        )

        val proposal = AgentSchedulePlanner.resolve(
            """{"r":"顺序安排","o":[["lesson-five",0],["lesson-one",0],["lesson-two",0]]}""",
            request
        )

        assertEquals(
            listOf("lesson-one", "lesson-two", "lesson-five"),
            proposal.blocks.sortedBy { it.startMinute }.map { it.taskKey }
        )
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun arabicAndChineseLessonNumbersAreBothRecognized() {
        val request = request(
            listOf(
                task("lesson-three", Priority.HIGH, 30).copy(title = "第三讲"),
                task("lesson-two", Priority.HIGH, 30).copy(title = "第2讲")
            )
        )

        val proposal = AgentSchedulePlanner.resolve(
            """{"r":"顺序安排","o":[["lesson-three",0],["lesson-two",0]]}""",
            request
        )

        assertEquals(listOf("lesson-two", "lesson-three"), proposal.blocks.sortedBy { it.startMinute }.map { it.taskKey })
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
        assertEquals(listOf(120), proposal.blocks.filter { it.taskKey == "large" }.map { it.minutes })
        assertEquals(listOf(50), proposal.blocks.filter { it.taskKey == "small" }.map { it.minutes })
        assertEquals(null, AgentSchedulePlanner.validateProposal(proposal, request))
    }

    @Test
    fun assistantOrderAndRecommendedSlotWinWithinPriorityTier() {
        val request = request(
            listOf(
                task("first", Priority.HIGH, 60).copy(assistantOrder = 0),
                task("second", Priority.HIGH, 60).copy(assistantOrder = 1, preferredStartMinute = 9 * 60)
            )
        )
        val proposal = AgentSchedulePlanner.localPlan(request)
        assertEquals("first", proposal.blocks.minBy { it.startMinute }.taskKey)
        assertTrue(proposal.blocks.first { it.taskKey == "second" }.startMinute >= 7 * 60)
    }

    @Test
    fun wholeTaskIsNotSplitWhenGapCanHoldIt() {
        val request = request(listOf(task("chapter", Priority.HIGH, 90)))
        val blocks = AgentSchedulePlanner.localPlan(request).blocks.filter { it.taskKey == "chapter" }
        assertEquals(1, blocks.size)
        assertEquals(90, blocks.single().minutes)
        assertEquals(1, blocks.single().partCount)
    }

    @Test
    fun localPlannerUsesSmallestSufficientGapToReduceWaste() {
        val zone = java.time.ZoneId.systemDefault()
        val request = request(listOf(task("new", Priority.MEDIUM, 60))).copy(
            preset = AssistantPreset(windowStartMinute = 6 * 60, windowEndMinute = 12 * 60),
            existingBlocks = listOf(
                com.ming.focusplan.data.ScheduleBlockEntity(
                    title = "已有任务",
                    startAt = startDay.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
                    endAt = startDay.atTime(10, 20).atZone(zone).toInstant().toEpochMilli()
                )
            )
        )

        val blocks = AgentSchedulePlanner.localPlan(request).blocks

        assertEquals(10 * 60 + 20, blocks.minBy { it.startMinute }.startMinute)
        assertEquals(60, blocks.sumOf { it.minutes })
        assertEquals(null, AgentSchedulePlanner.validateProposal(AgentSchedulePlanner.localPlan(request), request))
    }

    @Test
    fun taskCanFillAllAvailableTimeWithoutMandatoryBuffer() {
        val request = request(
            tasks = listOf(task("full", Priority.HIGH, 240)),
            preset = AssistantPreset(windowStartMinute = 6 * 60, windowEndMinute = 10 * 60)
        )

        val proposal = AgentSchedulePlanner.localPlan(request)

        assertEquals(240, proposal.blocks.sumOf { it.minutes })
        assertTrue(proposal.blocks.all { it.minutes in 30..90 })
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

        val block = AgentSchedulePlanner.localPlan(request).blocks.minBy { it.startMinute }

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

        val block = AgentSchedulePlanner.localPlan(request).blocks.minBy { it.startMinute }

        assertEquals(1, block.dayOffset)
        assertEquals(10 * 60, block.startMinute)
    }

    @Test
    fun availabilityReportsEmptyRunsAfterExistingTaskAndExclusions() {
        val zone = java.time.ZoneId.systemDefault()
        val preset = AssistantPreset(
            windowStartMinute = 9 * 60,
            windowEndMinute = 22 * 60,
            excludedTimes = listOf(
                ExcludedTime("午休", 11 * 60, 15 * 60 + 30),
                ExcludedTime("晚饭", 17 * 60, 18 * 60)
            )
        )
        val existing = ScheduleBlockEntity(
            title = "已有任务",
            startAt = startDay.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            endAt = startDay.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        )
        val runs = AgentSchedulePlanner.availableRuns(
            SchedulingRequest(
                tasks = emptyList(),
                existingBlocks = listOf(existing),
                preset = preset,
                startDay = startDay,
                dayCount = 1,
                earliestMinuteToday = 9 * 60
            )
        )

        assertEquals(listOf(15 * 60 + 30 to 90, 18 * 60 to 240), runs.map { it.startMinute to it.minutes })
    }

    @Test
    fun preferredDayIsSoftWhenThatDayHasNoCapacity() {
        val zone = java.time.ZoneId.systemDefault()
        val preset = AssistantPreset(windowStartMinute = 9 * 60, windowEndMinute = 11 * 60)
        val tomorrow = startDay.plusDays(1)
        val existing = ScheduleBlockEntity(
            title = "占满建议日",
            startAt = tomorrow.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            endAt = tomorrow.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        )
        val request = SchedulingRequest(
            tasks = listOf(task("preferred", Priority.HIGH, 60).copy(preferredDay = tomorrow)),
            existingBlocks = listOf(existing),
            preset = preset,
            startDay = startDay,
            dayCount = 2,
            earliestMinuteToday = 9 * 60
        )

        val block = AgentSchedulePlanner.localPlan(request).blocks.single()
        assertEquals(0, block.dayOffset)
        assertEquals(9 * 60, block.startMinute)
    }

    @Test
    fun crossMidnightExistingBlockOccupiesNextPlanningDayMorning() {
        val zone = java.time.ZoneId.systemDefault()
        val existing = ScheduleBlockEntity(
            title = "跨午夜固定安排",
            startAt = startDay.atTime(23, 0).atZone(zone).toInstant().toEpochMilli(),
            endAt = startDay.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        )
        val request = SchedulingRequest(
            tasks = listOf(task("morning", Priority.HIGH, 60)),
            existingBlocks = listOf(existing),
            preset = AssistantPreset(windowStartMinute = 6 * 60, windowEndMinute = 10 * 60),
            startDay = startDay,
            dayCount = 2,
            earliestMinuteToday = 6 * 60
        )

        val block = AgentSchedulePlanner.localPlan(request).blocks.single()
        assertEquals(1, block.dayOffset)
        assertEquals(7 * 60, block.startMinute)
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
