package com.zillit.desktop.feature.budget

import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.ui.label
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a budget calls itself.
 *
 * A live department budget arrived with no department name and was drawn as
 * "Main budget" — the fallback keyed off blankness rather than type, and told
 * the reader the opposite of the truth.
 */
class BudgetLabelTest {

    @Test
    fun `a nameless department budget is not called the main one`() {
        val document = BudgetDocument(id = "b1", type = BudgetType.Department, departmentId = "d1")

        assertEquals("Department budget", document.label())
    }

    @Test
    fun `the main budget says so`() {
        assertEquals("Main budget", BudgetDocument(id = "b2", type = BudgetType.Main).label())
    }

    @Test
    fun `a named department wears its own name`() {
        val document = BudgetDocument(id = "b3", type = BudgetType.Department, departmentName = "Camera")

        assertEquals("Camera", document.label())
    }
}
