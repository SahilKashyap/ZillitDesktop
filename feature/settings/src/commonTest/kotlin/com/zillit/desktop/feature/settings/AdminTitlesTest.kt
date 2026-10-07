package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.ui.ProductionFacts
import com.zillit.desktop.feature.settings.ui.adminSettingsEntries
import kotlin.test.Test
import kotlin.test.assertEquals

/** The administration rows are named as the web's `AdminSetting.jsx` names its buttons. */
class AdminTitlesTest {

    private val titles = adminSettingsEntries(ProductionFacts()).flatMap { it.entries }.map { it.title }

    @Test
    fun `the rows carry the web's names`() {
        listOf(
            "Approve New User Request",
            "Approve User Profile",
            "Create/Update on Home Unit",
            "Create Additional Shooting Unit",
            "Create New Department",
            "Create New Designation",
            "Create Remote shooting unit",
            "Customization of Tools",
            "Manage Tool Groups",
            "Listing Order for Crew List",
            "Edit Project Name",
            "Pre-Approved Users",
            "Set/View SOS Receivers",
            "User Management",
            "Viewing & Posting Rights Grid",
            "Watermark Logo of Company",
            "Company Details",
            "Production Setup",
            "Project Set Up Notes",
            "Delete Project",
        ).forEach { name -> assertEquals(1, titles.count { it == name }, "no row is named \"$name\"") }
    }
}
