package com.zillit.desktop.feature.settings

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.CompanyDetails
import com.zillit.desktop.feature.settings.admin.domain.CompanyField
import com.zillit.desktop.feature.settings.admin.domain.CrewMember
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.DeletionSchedule
import com.zillit.desktop.feature.settings.admin.domain.Department
import com.zillit.desktop.feature.settings.admin.domain.JobTitle
import com.zillit.desktop.feature.settings.admin.domain.NewSosRecipient
import com.zillit.desktop.feature.settings.admin.domain.PreApprovedCrew
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.domain.SosRecipient
import com.zillit.desktop.feature.settings.admin.domain.ToolGroup
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminForm
import com.zillit.desktop.feature.settings.admin.ui.AdminScreen
import com.zillit.desktop.feature.settings.admin.ui.AdminSelection
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState
import com.zillit.desktop.feature.settings.admin.ui.NameKind
import com.zillit.desktop.feature.settings.ui.ProductionFacts
import kotlin.test.Test

/**
 * Composes every administration page for real.
 *
 * ## Why render rather than screenshot
 *
 * Screenshot verification needs macOS screen-recording permission, which CI
 * does not have. Composing the actual page exercises the same layout code and
 * catches the two failures unit tests cannot: a page that throws while
 * composing, and a nested-scroll arrangement that crashes on an unbounded
 * constraint. Both are how a page ships looking fine in review and blank in
 * use — and this feature has several two-pane pages with lazy lists inside a
 * scrolling frame, which is exactly the shape that fails.
 *
 * Assertions use `assertExists` rather than `assertIsDisplayed`: the test
 * window is small, and anything below the first screenful is composed but not
 * displayed.
 */
@OptIn(ExperimentalTestApi::class)
class AdminScreenRenderTest {

    private val film = ProductionFacts(name = "Feature One")

    private val departments = listOf(
        Department(
            id = "dept-1",
            name = "Transportation",
            systemDefined = true,
            jobTitles = listOf(
                JobTitle(id = "role-1", name = "Driver", systemDefined = true),
                JobTitle(id = "role-2", name = "Unit Driver"),
            ),
        ),
        Department(id = "dept-2", name = "Art", jobTitles = listOf(JobTitle("role-3", "Standby Art"))),
    )

    private val crew = listOf(
        CrewMember(
            userId = "user-1",
            fullName = "Ada Lovelace",
            email = "ada@zillit.com",
            department = "Transportation",
            designation = "Driver",
            deviceId = "device-1",
            isAdmin = true,
        ),
        CrewMember(
            userId = "user-2",
            fullName = "Grace Hopper",
            department = "Art",
            deviceId = "device-2",
            status = CrewStatus.Removed,
        ),
        // No device: the enable switch is withheld rather than disabled.
        CrewMember(userId = "user-3", fullName = "Katherine Johnson"),
    )

    private val rights = listOf(
        ToolRights(
            toolIdentifier = "budget_tool",
            toolName = "Main budget",
            unitId = "unit-1",
            section = RightsSection.Tools,
            canView = true,
            canDownload = true,
            postLocked = true,
        ),
        ToolRights(
            toolIdentifier = "callsheet_tool",
            toolName = "Call sheet",
            unitId = "unit-2",
            section = RightsSection.Home,
            canView = true,
        ),
    )

    private val tools = listOf(
        ProductionTool("permission_grid_tool", "Permission grid", enabled = true, isLocked = true),
        ProductionTool("casting_main_tool", "Casting", enabled = true, groupIdentifier = "group_ads"),
        // In the custom group, so that group is deletable in principle and
        // blocked in practice — which is the case worth rendering.
        ProductionTool("continuity_tool", "Continuity", enabled = true, groupIdentifier = "group_custom"),
        ProductionTool("budget_tool", "Budget", enabled = false),
    )

    private val groups = listOf(
        ToolGroup(id = "g1", identifier = "group_ads", name = "ADs", systemDefined = true, order = 1),
        ToolGroup(id = "g2", identifier = "group_custom", name = "Second unit", order = 2),
    )

    private fun state(
        destination: AdminDestination,
        form: AdminForm? = null,
        confirming: AdminConfirmation? = null,
    ) = AdminUiState(
        destination = destination,
        hasLoaded = true,
        departments = departments,
        crew = crew,
        preApproved = listOf(
            PreApprovedCrew(
                id = "pre-1",
                fullName = "Margaret Hamilton",
                email = "margaret@zillit.com",
                departmentName = "Art",
                designationName = "Standby Art",
            ),
        ),
        tools = tools,
        toolGroups = groups,
        sos = listOf(
            SosRecipient(
                id = "sos-1",
                name = "Ada Lovelace",
                entryType = SosEntryType.Crew,
                userId = "user-1",
            ),
            SosRecipient(
                id = "sos-2",
                name = "St Mary's",
                entryType = SosEntryType.Outsider,
                phone = "2079460000",
                countryCode = "+44",
                relationship = "Hospital",
            ),
        ),
        units = listOf(
            AdminUnit("unit-1", "Main unit", UnitKind.Home, locked = true),
            AdminUnit("unit-2", "Second unit", UnitKind.Home, enabled = false),
        ),
        company = CompanyDetails(
            name = "Zillit Films",
            address = "1 Wardour Street",
            email = "office@zillit.com",
            customFields = listOf(CompanyField("VAT", "GB123")),
            logoUrl = "https://example.test/logo.png",
        ),
        watermarkUrl = "https://example.test/watermark.png",
        productionName = "Feature One",
        form = form,
        confirming = confirming,
        selection = AdminSelection(
            departmentId = "dept-1",
            userId = "user-3",
            rights = rights,
        ),
    )

    private fun render(
        destination: AdminDestination,
        state: AdminUiState = state(destination),
        production: ProductionFacts = film,
        assert: (suspend ComposeUiTest.() -> Unit)? = null,
    ) = runComposeUiTest {
        setContent {
            ZillitTheme {
                AdminScreen(
                    destination = destination,
                    state = state,
                    onEvent = {},
                    onBack = {},
                    production = production,
                )
            }
        }
        assert?.invoke(this)
    }

    /**
     * Every page, composed.
     *
     * The loop is the point: a page added to the enum is covered the moment it
     * exists, rather than when someone remembers to add a test for it.
     */
    @Test
    fun `every page composes`() {
        AdminDestination.entries.forEach { destination -> render(destination) }
    }

    @Test
    fun `every page composes on a production that has nothing loaded yet`() {
        AdminDestination.entries.forEach { destination ->
            render(destination, state = AdminUiState(destination = destination, isLoading = true))
        }
    }

    /** The empty states are a separate composition path from the populated ones. */
    @Test
    fun `every page composes when its list came back empty`() {
        AdminDestination.entries.forEach { destination ->
            render(destination, state = AdminUiState(destination = destination, hasLoaded = true))
        }
    }

    @Test
    fun `departments name the built-in ones and offer a delete on the rest`() {
        render(AdminDestination.Departments) {
            onNodeWithText("Transportation").assertExists()
            onNodeWithText("Art").assertExists()
            onNodeWithText("Built in").assertExists()
        }
    }

    @Test
    fun `job titles list the selected department's roles`() {
        render(AdminDestination.JobTitles) {
            onNodeWithText("Driver").assertExists()
            onNodeWithText("Unit Driver").assertExists()
        }
    }

    @Test
    fun `the crew page marks admins and people who are off the production`() {
        render(AdminDestination.Crew) {
            onNodeWithText("Ada Lovelace").assertExists()
            onNodeWithText("Admin").assertExists()
            onNodeWithText("Off the project").assertExists()
        }
    }

    /** An admin's rights are stated rather than shown as switches that refuse. */
    @Test
    fun `an administrator's rights row explains itself instead of offering switches`() {
        val adminSelected = state(AdminDestination.Rights)
            .copy(selection = AdminSelection(userId = "user-1"))

        render(AdminDestination.Rights, state = adminSelected) {
            onNodeWithText(
                "Ada Lovelace is an administrator and can reach everything. " +
                    "Take their admin rights away on the crew page to set rights individually.",
            ).assertExists()
        }
    }

    @Test
    fun `the rights page shows both sections for the person picked`() {
        render(AdminDestination.Rights) {
            onNodeWithText("Main budget").assertExists()
            onNodeWithText("Call sheet").assertExists()
        }
    }

    @Test
    fun `the tools page marks the ones that cannot be switched off`() {
        render(AdminDestination.ToolAvailability) {
            onNodeWithText("Always on").assertExists()
            onNodeWithText("Casting").assertExists()
        }
    }

    /** A group with tools in it says why it cannot be deleted. */
    @Test
    fun `a group holding tools says to move them first`() {
        render(AdminDestination.ToolGroups) {
            onNodeWithText("Move its tools first").assertExists()
        }
    }

    @Test
    fun `an empty sos list says the production has nobody to alert`() {
        val none = state(AdminDestination.Sos).copy(sos = emptyList())
        render(AdminDestination.Sos, state = none) {
            onNodeWithText(
                "Nobody is alerted on this project. An SOS raised here would reach no one.",
            ).assertExists()
        }
    }

    @Test
    fun `a scheduled deletion offers a way to call it off`() {
        val scheduled = state(AdminDestination.DeleteProduction)
            .copy(deletion = DeletionSchedule(isScheduled = true, hours = 24))

        render(AdminDestination.DeleteProduction, state = scheduled) {
            onNodeWithText("This project is scheduled for deletion in 24 hours.").assertExists()
            onNodeWithText("Call it off").assertExists()
        }
    }

    @Test
    fun `remote units say why they cannot be renamed`() {
        render(AdminDestination.RemoteUnits) {
            onNodeWithText(
                "Remote units can be added but not renamed or removed. " +
                    "That is true on every Zillit app.",
            ).assertExists()
        }
    }

    /**
     * A page the production does not have.
     *
     * Reachable by a stale deep link, and it must say so rather than render an
     * empty list that reads as a failed load.
     */
    @Test
    fun `a page this production does not have says so`() {
        render(
            AdminDestination.ShootingUnits,
            state = state(AdminDestination.ShootingUnits),
            production = ProductionFacts(name = "Conference", isOtherType = true),
        ) {
            onNodeWithText(
                "Create Additional Shooting Unit is not part of this project",
            ).assertExists()
        }
    }

    /**
     * The dashboard is not a shooting unit, and a corporate production has one.
     *
     * The pair of assertions is the correction itself: `home/unit` renders on a
     * production that shoots nothing, while the shooting units above do not.
     */
    @Test
    fun `a corporate production still gets its dashboard sections`() {
        render(
            AdminDestination.HomeUnits,
            state = state(AdminDestination.HomeUnits),
            production = ProductionFacts(name = "Conference", isOtherType = true),
        ) {
            onNodeWithText("Create/Update Home Units").assertExists()
        }
    }

    // -- dialogs -----------------------------------------------------------

    @Test
    fun `every form composes`() {
        val forms = listOf(
            AdminDestination.Departments to AdminForm.Name(NameKind.Department, "Cam"),
            AdminDestination.ToolGroups to AdminForm.Name(NameKind.ToolGroup, "Splinter", targetId = "g2"),
            AdminDestination.PreApproved to AdminForm.PreApproval(
                firstName = "Margaret",
                lastName = "Hamilton",
                departmentId = "dept-1",
            ),
            AdminDestination.Sos to AdminForm.Sos(NewSosRecipient(SosEntryType.Outsider)),
            AdminDestination.Sos to AdminForm.Sos(NewSosRecipient(SosEntryType.Crew)),
            AdminDestination.CompanyDetails to AdminForm.Company(
                CompanyDetails(name = "Zillit Films", customFields = listOf(CompanyField("VAT", "GB1"))),
            ),
            AdminDestination.ProductionName to AdminForm.ProductionName("Feature One"),
        )

        forms.forEach { (destination, form) ->
            render(destination, state = state(destination, form = form))
        }
    }

    /**
     * The button says what it does.
     *
     * Both dialogs are the same composable, and renaming through a button
     * labelled "Add" reads as creating a second thing — on a page that offers
     * both, that is a real hesitation. Caught by renaming a tool group in the
     * running app.
     */
    @Test
    fun `the name dialog labels its button for what it is about to do`() {
        // Rendered over an empty group list: every group row carries its own
        // "Rename" button, so with rows behind it the assertion matches three
        // nodes and says nothing about the dialog.
        fun bare(form: AdminForm) =
            state(AdminDestination.ToolGroups, form = form).copy(toolGroups = emptyList())

        render(
            AdminDestination.ToolGroups,
            state = bare(AdminForm.Name(NameKind.ToolGroup, "Second unit")),
        ) {
            onNodeWithText("New tool group").assertExists()
            onNodeWithText("Add").assertExists()
        }

        render(
            AdminDestination.ToolGroups,
            state = bare(AdminForm.Name(NameKind.ToolGroup, "Second unit", targetId = "g2")),
        ) {
            onNodeWithText("Rename tool group").assertExists()
            onNodeWithText("Rename").assertExists()
        }
    }

    @Test
    fun `a form that was rejected shows why`() {
        val rejected = AdminForm.Name(
            kind = NameKind.Department,
            value = "Ca",
            error = "A department name needs at least three characters.",
        )

        render(AdminDestination.Departments, state = state(AdminDestination.Departments, form = rejected)) {
            onNodeWithText("A department name needs at least three characters.").assertExists()
        }
    }

    @Test
    fun `every confirmation composes and names what it removes`() {
        val confirmations = listOf(
            AdminConfirmation.RemoveDepartment("dept-2", "Art"),
            AdminConfirmation.RemoveJobTitle("dept-1", "role-2", "Unit Driver"),
            AdminConfirmation.RemoveToolGroup("g2", "Second unit"),
            AdminConfirmation.RemoveUnit(UnitKind.Home, "unit-2", "Second unit"),
            AdminConfirmation.RemoveSos("sos-2", "St Mary's"),
            AdminConfirmation.RemoveFromCrew("user-2", "device-2", "Grace Hopper"),
            AdminConfirmation.GrantAdmin("user-3", "Katherine Johnson"),
            AdminConfirmation.ClearWatermark(),
            AdminConfirmation.ClearCompanyLogo(),
            AdminConfirmation.DeleteProduction(48, "Feature One"),
        )

        confirmations.forEach { confirmation ->
            render(
                AdminDestination.Departments,
                state = state(AdminDestination.Departments, confirming = confirmation),
            ) {
                onNodeWithText(confirmation.title).assertExists()
            }
        }
    }

    /** The dangerous one names the production and the delay, not "this item". */
    @Test
    fun `the deletion confirmation names the production`() {
        val confirming = AdminConfirmation.DeleteProduction(48, "Feature One")
        render(
            AdminDestination.DeleteProduction,
            state = state(AdminDestination.DeleteProduction, confirming = confirming),
        ) {
            onNodeWithText("Delete this project in 48 hours?").assertExists()
            onNodeWithText(confirming.message).assertExists()
        }
    }

    // -- the strips --------------------------------------------------------

    @Test
    fun `an outcome and an error both compose`() {
        val reported = state(AdminDestination.Departments).copy(
            outcome = "“Camera” added.",
            error = "That department is in use.",
        )

        render(AdminDestination.Departments, state = reported) {
            onNodeWithText("“Camera” added.").assertExists()
            onNodeWithText("That department is in use.").assertExists()
        }
    }

    /**
     * A page's header says the same thing as the tab that opened it.
     *
     * Caught live: the tab read "User Management" while the page under it still
     * said "Crew and admins", because the two were separate hardcoded strings.
     * Every page now reads [AdminDestination.title], and this walks the whole
     * enum so the next one added cannot quietly reintroduce the split.
     */
    @Test
    fun `every admin page is headed by the name on its tab`() {
        val production = ProductionFacts(name = "SG Document Distribution")
        val skipped = setOf(
            // Named for the production it belongs to, not for the enum entry.
            AdminDestination.CrewOrder,
        )

        AdminDestination.entries
            .filter { it.availableTo(production) && it !in skipped }
            .forEach { page ->
                render(page, state = state(page), production = production) {
                    onAllNodesWithText(page.title).onFirst().assertExists()
                }
            }
    }

}
