package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.feature.settings.approvals.KnownCrewMember
import com.zillit.desktop.feature.settings.approvals.PendingApproval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What an admin reads on an approval row.
 *
 * Department, role and unit all arrive as translation keys
 * (`transportation_department_label`), so an admin deciding who joins their
 * production was being shown identifiers rather than job titles.
 */
class ApprovalLabelsTest {

    @BeforeTest
    fun install() {
        Labels.install(
            MutableStateFlow(
                LabelDictionary.Empty.with(
                    LabelKind.Labels,
                    mapOf(
                        "transportation_department_label" to "Transportation",
                        "driver_label" to "Driver",
                        "main_unit_label" to "Main Unit",
                        "direction_label" to "Direction",
                    ),
                ),
            ),
        )
    }

    @AfterTest
    fun uninstall() = Labels.reset()

    private fun request(
        department: String? = "transportation_department_label",
        designation: String? = "driver_label",
        unit: String? = "main_unit_label",
    ) = PendingApproval(
        id = "r1",
        userId = "u1",
        fullName = "Ada Lovelace",
        departmentName = department,
        designationName = designation,
        unitName = unit,
    )

    @Test
    fun `the role line reads as job titles, not keys`() {
        assertEquals("Transportation · Driver · Main Unit", request().roleLine)
    }

    @Test
    fun `a key with no entry still reads as words`() {
        assertEquals("Second Unit", request(department = null, designation = null, unit = "second_unit_label").roleLine)
    }

    @Test
    fun `searching matches what the admin can see`() {
        // The row says "Driver"; typing "Driver" has to find it. Against the
        // raw key it never did.
        assertEquals(true, request().matches("Driver"))
        assertEquals(true, request().matches("Transportation"))
    }

    @Test
    fun `a profile change shows both sides translated`() {
        // Same name and role as the crew list already holds, so only the
        // department is moving.
        val changes = request(department = "transportation_department_label")
            .changesAgainst(
                KnownCrewMember(
                    fullName = "Ada Lovelace",
                    department = "direction_label",
                    designation = "driver_label",
                ),
            )

        assertEquals(1, changes.size, "expected only the department to have moved, got $changes")
        assertEquals("Department", changes.first().label)
        assertEquals("Direction", changes.first().from)
        assertEquals("Transportation", changes.first().to)
    }

    @Test
    fun `an unchanged field is compared before translation, not after`() {
        // Comparing translated forms would call a real move no move whenever
        // two keys happen to read alike — and "no change" silently drops the
        // row from what the admin is asked to agree to.
        val unchanged = request(department = "direction_label")
            .changesAgainst(KnownCrewMember(fullName = "Ada Lovelace", department = "direction_label"))

        assertEquals(emptyList(), unchanged.filter { it.label == "Department" })
    }
}
