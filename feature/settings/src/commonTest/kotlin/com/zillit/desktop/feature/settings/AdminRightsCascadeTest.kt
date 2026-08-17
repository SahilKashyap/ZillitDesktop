package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.admin.domain.AccessType
import com.zillit.desktop.feature.settings.admin.domain.RightsChange
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.cascadeFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What one checkbox really sends.
 *
 * The server enforces none of this: Android chains the calls by hand out of
 * each success handler, and a failure part-way leaves a production with rights
 * nobody granted. The rule is computed here instead, so it can be asserted on
 * without a network — which is the only way anyone will ever check it.
 */
class AdminRightsCascadeTest {

    private fun rights(
        view: Boolean = false,
        post: Boolean = false,
        download: Boolean = false,
    ) = ToolRights(
        toolIdentifier = "main_budget_tool",
        toolName = "main_budget_label",
        unitId = "unit-1",
        section = RightsSection.Tools,
        canView = view,
        canPost = post,
        canDownload = download,
    )

    private fun change(access: AccessType, enable: Boolean) = RightsChange(
        userId = "user-1",
        unitId = "unit-1",
        section = RightsSection.Tools,
        access = access,
        enable = enable,
    )

    @Test
    fun `granting view is one call`() {
        val calls = change(AccessType.View, true).cascadeFrom(rights())
        assertEquals(1, calls.size)
        assertEquals(AccessType.View, calls.single().access)
        assertTrue(calls.single().enable)
    }

    @Test
    fun `revoking view takes posting and downloading with it`() {
        val calls = change(AccessType.View, false)
            .cascadeFrom(rights(view = true, post = true, download = true))

        assertEquals(
            listOf(AccessType.Download, AccessType.Post, AccessType.View),
            calls.map { it.access },
        )
        assertTrue(calls.none { it.enable })
    }

    /**
     * The order is the safety.
     *
     * Removing the dependants first means an interrupted run has taken a right
     * away rather than left posting rights on a tool the person can no longer
     * see.
     */
    @Test
    fun `view is revoked last`() {
        val calls = change(AccessType.View, false)
            .cascadeFrom(rights(view = true, post = true, download = true))

        assertEquals(AccessType.View, calls.last().access)
    }

    @Test
    fun `revoking view sends nothing for rights that were not granted`() {
        val calls = change(AccessType.View, false).cascadeFrom(rights(view = true, post = true))

        assertEquals(listOf(AccessType.Post, AccessType.View), calls.map { it.access })
    }

    @Test
    fun `granting download grants view first`() {
        val calls = change(AccessType.Download, true).cascadeFrom(rights())

        assertEquals(listOf(AccessType.View, AccessType.Download), calls.map { it.access })
        assertTrue(calls.all { it.enable })
    }

    @Test
    fun `granting download when view is already on is one call`() {
        val calls = change(AccessType.Download, true).cascadeFrom(rights(view = true))
        assertEquals(1, calls.size)
    }

    @Test
    fun `granting posting grants view first`() {
        val calls = change(AccessType.Post, true).cascadeFrom(rights())
        assertEquals(listOf(AccessType.View, AccessType.Post), calls.map { it.access })
    }

    /** Nothing depends on posting, so revoking it stands alone. */
    @Test
    fun `revoking posting is one call`() {
        val calls = change(AccessType.Post, false)
            .cascadeFrom(rights(view = true, post = true, download = true))

        assertEquals(1, calls.size)
        assertEquals(AccessType.Post, calls.single().access)
    }

    @Test
    fun `every call in a cascade keeps the section and the unit`() {
        val calls = change(AccessType.View, false)
            .cascadeFrom(rights(view = true, post = true, download = true))

        assertTrue(calls.all { it.unitId == "unit-1" })
        assertTrue(calls.all { it.section == RightsSection.Tools })
        assertTrue(calls.all { it.userId == "user-1" })
    }
}
