package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.feature.talentdeals.domain.TalentDealsViewer
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsDestination
import com.zillit.desktop.feature.talentdeals.ui.TalentRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rights come from the `talent_deals_tool` row on Deal Memo's rule
 * (`useTalentDealsRights` → `resolveDealMemoRights`): view reads the register
 * and a contract; posting adds Add Contract, Edit and remove; download shows
 * the file viewer's Download; an accounts-department user always posts and
 * downloads; a pending member never posts. Ports `useDealMemoRights.test.js`.
 */
class TalentDealsAccessTest {

    private fun viewer(
        post: Boolean = false,
        view: Boolean = false,
        download: Boolean = false,
        department: String? = "department_art",
        status: String? = "accepted",
        loaded: Boolean = true,
    ) = TalentDealsViewer(
        userId = "u1",
        departmentIdentifier = department,
        hasPostingAccess = post,
        hasViewAccess = view,
        hasDownloadAccess = download,
        memberStatus = status,
        rightsLoaded = loaded,
    )

    @Test
    fun `view_access reads and nothing else`() {
        val v = viewer(view = true)
        assertTrue(v.canView)
        assertFalse(v.canPost)
        assertFalse(v.canDownload)
    }

    @Test
    fun `posting implies viewing, and adds the posting acts`() {
        val v = viewer(post = true)
        assertTrue(v.canView)
        assertTrue(v.canPost)
    }

    @Test
    fun `download_access alone shows Download`() {
        assertTrue(viewer(view = true, download = true).canDownload)
        assertFalse(viewer(view = true).canDownload)
    }

    @Test
    fun `an accounts-department user always posts and downloads`() {
        val v = viewer(department = "department_accounts")
        assertTrue(v.canView)
        assertTrue(v.canPost)
        assertTrue(v.canDownload)
        assertTrue(viewer(department = "accounts").canPost)
        assertTrue(viewer(department = "Department_ACCOUNTS").canPost)
    }

    @Test
    fun `a pending member never posts, whatever their department or grid says`() {
        val v = viewer(post = true, department = "department_accounts", status = "pending")
        assertFalse(v.canPost)
        // They still VIEW — without it the tool would turn them away at the door.
        assertTrue(v.canView)
        assertTrue(v.isPending)
    }

    @Test
    fun `only an explicit pending restricts - an unknown standing does not`() {
        // The member list lands after the tool opens; "not known yet" must not flash
        // a read-only register at every accountant.
        assertTrue(viewer(department = "department_accounts", status = null).canPost)
        assertTrue(viewer(post = true, status = "accepted").canPost)
        assertFalse(viewer(post = true, status = " Pending ").canPost)
    }

    @Test
    fun `no row and no department is no access`() {
        val v = viewer()
        assertFalse(v.canView)
        assertFalse(v.canPost)
        assertFalse(v.canDownload)
    }

    @Test
    fun `an accountant never gets in without the row for a view only - entry is view_access OR accounts`() {
        // The accountant rule upgrades a viewer to a poster; the module's own
        // entry gate (the tile) still needs view_access, which `canView` mirrors.
        assertTrue(viewer(department = "department_accounts").canView)
    }

    // -- the destination gate ----------------------------------------------------------

    @Test
    fun `the register and a contract need only view`() {
        val v = viewer(view = true)
        assertTrue(TalentDealsDestination.Register.visibleTo(v))
        assertTrue(TalentDealsDestination.View.visibleTo(v))
    }

    @Test
    fun `Add and Edit need posting`() {
        val readOnly = viewer(view = true)
        assertFalse(TalentDealsDestination.New.visibleTo(readOnly))
        assertFalse(TalentDealsDestination.Edit.visibleTo(readOnly))
        val poster = viewer(post = true)
        assertTrue(TalentDealsDestination.New.visibleTo(poster))
        assertTrue(TalentDealsDestination.Edit.visibleTo(poster))
    }

    @Test
    fun `nobody without view reaches any face`() {
        TalentDealsDestination.entries.forEach { assertFalse(it.visibleTo(viewer())) }
    }

    @Test
    fun `a pending member reaches the register but not the form`() {
        val pending = viewer(post = true, status = "pending")
        assertTrue(TalentDealsDestination.Register.visibleTo(pending))
        assertFalse(TalentDealsDestination.New.visibleTo(pending))
    }

    @Test
    fun `routes name their face`() {
        assertEquals(TalentDealsDestination.Register, TalentRoute.Register.destination)
        assertEquals(TalentDealsDestination.View, TalentRoute.View("c1").destination)
        assertEquals(TalentDealsDestination.New, TalentRoute.New("Cast").destination)
        assertEquals(TalentDealsDestination.Edit, TalentRoute.Edit("c1").destination)
        assertEquals(listOf(false, false, true, true), TalentDealsDestination.entries.map { it.requiresPost })
    }
}
