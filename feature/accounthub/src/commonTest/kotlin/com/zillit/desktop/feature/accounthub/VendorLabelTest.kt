package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.VendorsState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Department names as a person reads them.
 *
 * Found live, not by a test: the crew directory answers translation keys, so
 * the vendor register's department column read `direction_label` and
 * `assistant_directors_label`. Every fixture here used real words, which is
 * why nothing caught it.
 */
class VendorLabelTest {

    @Test
    fun `a department key reads as words, and search finds it by those words`() {
        val state = AccountHubUiState(
            departmentList = listOf(
                HubDepartment(id = "d-dir", name = "direction_label"),
                HubDepartment(id = "d-hmu", name = "Hair & Make-up"),
            ),
            vendors = VendorsState(
                rows = listOf(
                    Vendor(id = "v-1", name = "Grip Co", departmentId = "d-dir"),
                    Vendor(id = "v-2", name = "Wig Works", departmentId = "d-hmu"),
                ),
                search = "direction",
            ),
        )

        assertEquals("Direction", state.departmentName("d-dir"))
        assertEquals("Hair & Make-up", state.departmentName("d-hmu"), "a name that is already words is left alone")
        assertEquals(listOf("v-1"), state.visibleVendors.map { it.id })
    }

    @Test
    fun `a department nobody named is blank, never its id`() {
        val state = AccountHubUiState(setup = SetupState(departments = mapOf("d-1" to "6a2beb2f23a3156e75c3e85e")))

        assertEquals("", state.departmentName("d-1"))
        assertEquals("", state.departmentName("d-missing"))
        assertEquals("", state.departmentName(null))
    }
}
