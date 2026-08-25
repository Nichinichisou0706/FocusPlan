package com.ming.focusplan.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskLabelTest {
    @Test
    fun blankLabelBecomesUncategorized() {
        assertEquals("未分类", normalizeTaskLabel("   "))
    }

    @Test
    fun customLabelIsTrimmedAndPreserved() {
        assertEquals("线性代数", normalizeTaskLabel("  线性代数  "))
    }
}
