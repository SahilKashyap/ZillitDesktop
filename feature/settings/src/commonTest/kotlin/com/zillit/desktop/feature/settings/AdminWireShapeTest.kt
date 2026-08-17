package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.admin.data.AdminUnitDto
import com.zillit.desktop.feature.settings.admin.data.CrewDto
import com.zillit.desktop.feature.settings.admin.data.DepartmentDto
import com.zillit.desktop.feature.settings.admin.data.ProductionRecordDto
import com.zillit.desktop.feature.settings.admin.data.SosContactDto
import com.zillit.desktop.feature.settings.admin.data.ToolAccessDto
import com.zillit.desktop.feature.settings.admin.data.ToolDto
import com.zillit.desktop.feature.settings.admin.domain.CrewStatus
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.SosEntryType
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire, pinned against payloads captured from the reference clients.
 *
 * Not a test of the parser's cleverness — a test that the *field names* are
 * right. Every one of these has a spelling this client could plausibly have
 * guessed wrong, and every wrong guess fails silently: an empty list, a switch
 * that reads false, a delete button on a row that cannot be deleted.
 */
class AdminWireShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a department nests its job titles and carries system_defined`() {
        val payload = """
            [
              {
                "_id": "dept-1",
                "department_name": "transportation_department_label",
                "system_defined": true,
                "priority": "3",
                "designations": [
                  { "_id": "role-1", "designation_name": "driver_label", "system_defined": true },
                  { "_id": "role-2", "designation_name": "Unit Driver" }
                ]
              }
            ]
        """.trimIndent()

        val department = json.decodeFromString(ListSerializer(DepartmentDto.serializer()), payload)
            .single()
            .toDomain()!!

        assertEquals("dept-1", department.id)
        assertEquals("transportation_department_label", department.name)
        assertEquals("3", department.priority)
        // Built in: the delete button must not appear.
        assertFalse(department.isDeletable)
        assertEquals(2, department.jobTitles.size)
        assertFalse(department.jobTitles[0].isDeletable)
        // Absent means the production made it, so it can be removed.
        assertTrue(department.jobTitles[1].isDeletable)
    }

    /**
     * Two spellings of the admin flag are live on this route.
     *
     * `is_admin` on the crew list, `admin_access` on the socket payload and
     * some project-user rows. Reading only one leaves an administrator's switch
     * showing off.
     */
    @Test
    fun `a crew member is an admin under either spelling`() {
        val byIsAdmin = json.decodeFromString(
            CrewDto.serializer(),
            """{ "user_id": "u1", "first_name": "Ada", "last_name": "Lovelace", "is_admin": true }""",
        ).toDomain()!!

        val byAdminAccess = json.decodeFromString(
            CrewDto.serializer(),
            """{ "user_id": "u2", "full_name": "Grace Hopper", "admin_access": true }""",
        ).toDomain()!!

        assertTrue(byIsAdmin.isAdmin)
        assertEquals("Ada Lovelace", byIsAdmin.fullName)
        assertTrue(byAdminAccess.isAdmin)
        assertEquals("Grace Hopper", byAdminAccess.fullName)
    }

    @Test
    fun `a removed crew member is not actionable`() {
        val removed = json.decodeFromString(
            CrewDto.serializer(),
            """{ "user_id": "u1", "full_name": "Ada", "status": "removed", "device_id": "d1" }""",
        ).toDomain()!!

        assertEquals(CrewStatus.Removed, removed.status)
        assertFalse(removed.isActive)
        assertFalse(removed.isActionable)
    }

    /**
     * A status this client has not heard of must not empty the crew list.
     *
     * The server's vocabulary grows; a strict enum would fail the whole decode.
     */
    @Test
    fun `an unfamiliar status decodes rather than failing`() {
        val row = json.decodeFromString(
            CrewDto.serializer(),
            """{ "user_id": "u1", "full_name": "Ada", "status": "invited" }""",
        ).toDomain()!!

        assertEquals(CrewStatus.Unknown, row.status)
    }

    /** Someone with no device cannot be enabled or disabled — the call needs one. */
    @Test
    fun `a crew member with no device is not actionable`() {
        val row = json.decodeFromString(
            CrewDto.serializer(),
            """{ "user_id": "u1", "full_name": "Ada", "status": "accepted" }""",
        ).toDomain()!!

        assertTrue(row.isActive)
        assertFalse(row.isActionable)
    }

    /**
     * `unit_name` is the tool's *name*.
     *
     * Nothing to do with production units — the tools list predates the units
     * service and kept the word. Reading `tool_name` alone gives every tool a
     * blank label.
     */
    @Test
    fun `a tool takes its name from unit_name`() {
        val tool = json.decodeFromString(
            ToolDto.serializer(),
            """
            { "identifier": "casting_main_tool", "unit_name": "casting_label",
              "enabled": true, "group_identifier": "group_ads" }
            """.trimIndent(),
        ).toDomain()!!

        assertEquals("casting_main_tool", tool.identifier)
        assertEquals("casting_label", tool.name)
        assertTrue(tool.enabled)
        assertEquals("group_ads", tool.groupIdentifier)
        assertTrue(tool.isGrouped)
        assertFalse(tool.isLocked)
    }

    @Test
    fun `the permission grid and info tools cannot be switched off`() {
        listOf("permission_grid_tool", "info_tool").forEach { identifier ->
            val tool = json.decodeFromString(
                ToolDto.serializer(),
                """{ "identifier": "$identifier", "unit_name": "x", "enabled": true }""",
            ).toDomain()!!

            assertTrue(tool.isLocked, "$identifier must be locked")
        }
    }

    /**
     * A tool on both the dashboard and the grid is two rows.
     *
     * They are separate rights written to separate routes, so collapsing them
     * into one would silently write dashboard access to the tools section.
     */
    @Test
    fun `an access row becomes one entry per section it appears in`() {
        val rows = json.decodeFromString(
            ToolAccessDto.serializer(),
            """
            {
              "unit_id": "unit-9", "identifier": "budget_tool", "unit_name": "main_budget_label",
              "view_access": true, "posting_access": false, "download_access": true,
              "viewing_updatable": false, "home": true, "tool": true
            }
            """.trimIndent(),
        ).toDomain()

        assertEquals(2, rows.size)
        assertEquals(setOf(RightsSection.Home, RightsSection.Tools), rows.map { it.section }.toSet())
        rows.forEach { row ->
            assertEquals("unit-9", row.unitId)
            assertTrue(row.canView)
            assertFalse(row.canPost)
            assertTrue(row.canDownload)
            // An explicit false is what locks it.
            assertTrue(row.viewLocked)
            assertFalse(row.postLocked)
        }
    }

    /** A row belonging to neither section has nowhere to render. */
    @Test
    fun `an access row in no section is dropped`() {
        val rows = json.decodeFromString(
            ToolAccessDto.serializer(),
            """{ "unit_id": "unit-1", "unit_name": "x", "home": false, "tool": false }""",
        ).toDomain()

        assertTrue(rows.isEmpty())
    }

    /** Without a unit id the write is unaddressable, so the row is dropped. */
    @Test
    fun `an access row with no unit id is dropped`() {
        val rows = json.decodeFromString(
            ToolAccessDto.serializer(),
            """{ "unit_name": "x", "tool": true, "view_access": true }""",
        ).toDomain()

        assertTrue(rows.isEmpty())
    }

    /**
     * The company block's custom fields are camelCase among snake_case
     * siblings — and iOS spells them the other way.
     */
    @Test
    fun `company details read either spelling of the custom fields`() {
        val camel = json.decodeFromString(
            ProductionRecordDto.serializer(),
            """
            { "company_name": "Zillit Films", "company_phone": "7700900000",
              "customFields": [ { "label": "VAT", "value": "GB123" } ] }
            """.trimIndent(),
        ).toCompanyDetails()

        val snake = json.decodeFromString(
            ProductionRecordDto.serializer(),
            """{ "company_name": "Zillit Films", "custom_fields": [ { "label": "VAT", "value": "GB123" } ] }""",
        ).toCompanyDetails()

        assertEquals("Zillit Films", camel.name)
        assertEquals("7700900000", camel.phone)
        assertEquals(listOf("VAT" to "GB123"), camel.customFields.map { it.label to it.value })
        assertEquals(camel.customFields, snake.customFields)
    }

    /** A field with no label has nothing to print beside its value. */
    @Test
    fun `an unlabelled custom field is dropped`() {
        val details = json.decodeFromString(
            ProductionRecordDto.serializer(),
            """{ "customFields": [ { "label": "", "value": "orphan" }, { "label": "VAT", "value": "GB1" } ] }""",
        ).toCompanyDetails()

        assertEquals(1, details.customFields.size)
        assertEquals("VAT", details.customFields.single().label)
    }

    /** The logo arrives as an attachment object, not a string. */
    @Test
    fun `the company logo url is read out of the attachment object`() {
        val details = json.decodeFromString(
            ProductionRecordDto.serializer(),
            """
            { "company_logo": { "media": "https://example.test/logo.png",
                                "thumbnail": "https://example.test/thumb.png",
                                "bucket": "b", "region": "r" } }
            """.trimIndent(),
        ).toCompanyDetails()

        assertEquals("https://example.test/logo.png", details.logoUrl)
        assertTrue(details.hasLogo)
    }

    @Test
    fun `no logo means no logo`() {
        val details = json.decodeFromString(ProductionRecordDto.serializer(), "{}").toCompanyDetails()
        assertNull(details.logoUrl)
        assertFalse(details.hasLogo)
    }

    /**
     * The SOS row's kind is `type`, and the crew recipient's name is nested
     * under `user` rather than on the row.
     */
    @Test
    fun `an sos crew recipient reads its name from the nested user`() {
        val recipient = json.decodeFromString(
            SosContactDto.serializer(),
            """
            { "_id": "sos-1", "type": "internal",
              "user": { "user_id": "u1", "full_name": "Ada Lovelace",
                        "designation_name": "medic_label", "phone": "7700900000" } }
            """.trimIndent(),
        ).toDomain()!!

        assertEquals(SosEntryType.Crew, recipient.entryType)
        assertEquals("Ada Lovelace", recipient.name)
        assertEquals("u1", recipient.userId)
        assertEquals("medic_label", recipient.designation)
    }

    @Test
    fun `an sos outsider reads contact_name and phone_number`() {
        val recipient = json.decodeFromString(
            SosContactDto.serializer(),
            """
            { "_id": "sos-2", "type": "external", "contact_name": "St Mary's",
              "relation": "hospital_label", "country_code": "+44", "phone_number": "2079460000" }
            """.trimIndent(),
        ).toDomain()!!

        assertEquals(SosEntryType.Outsider, recipient.entryType)
        assertEquals("St Mary's", recipient.name)
        assertEquals("hospital_label", recipient.relationship)
        assertEquals("+44 2079460000", recipient.dialled)
    }

    /**
     * A unit's lock arrives as the **string** `"false"` in `priority`.
     *
     * Read as a boolean it is simply absent, and a protected unit grows a
     * delete button that the server refuses.
     */
    @Test
    fun `a unit with priority false is locked`() {
        val locked = json.decodeFromString(
            AdminUnitDto.serializer(),
            """{ "_id": "unit-1", "unit_name": "main_unit_label", "priority": "false" }""",
        ).toDomain(UnitKind.Shooting)!!

        assertTrue(locked.locked)
        assertFalse(locked.isRemovable)
    }

    @Test
    fun `a unit reads visibility before enabled and defaults to on`() {
        val hidden = json.decodeFromString(
            AdminUnitDto.serializer(),
            """{ "_id": "unit-1", "unit_name": "Second Unit", "visibility": false, "enabled": true }""",
        ).toDomain(UnitKind.Home)!!

        val unstated = json.decodeFromString(
            AdminUnitDto.serializer(),
            """{ "_id": "unit-2", "unit_name": "Splinter" }""",
        ).toDomain(UnitKind.Home)!!

        assertFalse(hidden.enabled)
        // Absent means the production has never switched it off.
        assertTrue(unstated.enabled)
        assertTrue(unstated.isRemovable)
    }

    /** Every action on these pages is keyed on an id, so a row without one is unusable. */
    @Test
    fun `rows with no id are dropped rather than defaulted`() {
        assertNull(json.decodeFromString(DepartmentDto.serializer(), """{ "department_name": "x" }""").toDomain())
        assertNull(json.decodeFromString(CrewDto.serializer(), """{ "full_name": "x" }""").toDomain())
        assertNull(json.decodeFromString(SosContactDto.serializer(), """{ "type": "external" }""").toDomain())
        assertNull(
            json.decodeFromString(AdminUnitDto.serializer(), """{ "unit_name": "x" }""")
                .toDomain(UnitKind.Remote),
        )
        assertNull(json.decodeFromString(ToolDto.serializer(), """{ "unit_name": "x" }""").toDomain())
    }

    /** `delete_in_hours` is a string on this route, unlike every other number. */
    @Test
    fun `a scheduled deletion reads its delay whether string or number`() {
        val asString = json.decodeFromString(
            ProductionRecordDto.serializer(),
            """{ "mark_deleted": true, "delete_in_hours": "24" }""",
        )
        val asNumber = json.decodeFromString(
            ProductionRecordDto.serializer(),
            """{ "mark_deleted": true, "delete_in_hours": 48 }""",
        )

        assertEquals(24, asString.scheduledHours)
        assertEquals(48, asNumber.scheduledHours)
    }
}
