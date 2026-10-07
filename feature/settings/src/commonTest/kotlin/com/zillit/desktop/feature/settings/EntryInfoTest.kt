package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.ui.InfoGuide
import com.zillit.desktop.feature.settings.ui.ProductionFacts
import com.zillit.desktop.feature.settings.ui.SettingsDestination
import com.zillit.desktop.feature.settings.ui.adminSettingsEntries
import com.zillit.desktop.feature.settings.ui.settingsEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ⓘ behind the rows, against the web's `TooltipInfo` calls. */
class EntryInfoTest {

    private fun admin(production: ProductionFacts = ProductionFacts()) =
        adminSettingsEntries(production).flatMap { it.entries }.associateBy { it.destination }

    private fun profile(isAdmin: Boolean) =
        settingsEntries(isAdmin).flatMap { it.entries }.associateBy { it.destination }

    @Test
    fun `every administration row carries the web's info`() {
        val without = admin().values.filter { it.info == null }

        assertEquals(emptyList(), without.map { it.title })
    }

    @Test
    fun `the info text is the web's`() {
        val rows = admin()

        assertEquals(
            "Admin selects the Tools that can be used on the project.",
            rows.getValue(SettingsDestination.ToolAvailability).info?.message,
        )
        assertEquals(
            "Admin can set up pre-approval for a user to join a project.",
            rows.getValue(SettingsDestination.PreApprovedCrew).info?.message?.trim(),
        )
    }

    @Test
    fun `stopping a deletion carries no info, as on the web`() {
        val stop = admin(ProductionFacts(markedForDeletion = true)).getValue(SettingsDestination.DeleteProduction)

        assertNull(stop.info)
        assertNotNull(admin().getValue(SettingsDestination.DeleteProduction).info)
    }

    @Test
    fun `the profile rows that exist on the web carry its info`() {
        val rows = profile(isAdmin = false)

        assertEquals(
            "Code to be shared with others to join the project.",
            rows.getValue(SettingsDestination.InviteCrew).info?.message,
        )
        assertEquals(InfoGuide.EditProfile, rows.getValue(SettingsDestination.EditProfile).info?.guide)
        assertNotNull(rows.getValue(SettingsDestination.RecoveryEmail).info)
        assertNotNull(rows.getValue(SettingsDestination.LeaveProduction).info)
    }

    @Test
    fun `rows with no web counterpart carry none`() {
        val rows = profile(isAdmin = false)

        assertNull(rows.getValue(SettingsDestination.LinkedDevices).info)
        assertNull(rows.getValue(SettingsDestination.Help).info)
    }

    @Test
    fun `an admin is told what the web tells an admin`() {
        val user = profile(isAdmin = false)
        val admin = profile(isAdmin = true)

        assertTrue(
            admin.getValue(SettingsDestination.LeaveProduction).info!!.message.contains("Admin can only leave"),
        )
        assertTrue(!user.getValue(SettingsDestination.LeaveProduction).info!!.message.contains("Admin can only leave"))
        assertTrue(
            admin.getValue(SettingsDestination.RecoveryEmail).info!!.message !=
                user.getValue(SettingsDestination.RecoveryEmail).info!!.message,
        )
        assertEquals(InfoGuide.EditProfileAdmin, admin.getValue(SettingsDestination.EditProfile).info?.guide)
    }

    @Test
    fun `More says for=admin only to an admin, and always the project type`() {
        assertEquals(
            "https://documentation.zillit.com/?for=admin&project_type=film#user-management",
            InfoGuide.UserManagement.docsUrl("film"),
        )
        assertEquals(
            "https://documentation.zillit.com/?project_type=default#invite-users",
            InfoGuide.InviteUser.docsUrl("", forAdmin = false),
        )
    }

    @Test
    fun `a row with no clip of its own plays the web's generic one`() {
        assertEquals(InfoGuide.STATIC_VIDEO, InfoGuide.ProductionSetup.videoUrl)
        assertTrue(InfoGuide.LeaveProject.videoUrl.endsWith("How%20to%20leave%20project%20Web.mp4"))
    }
}
