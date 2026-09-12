package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.purchaseorder.data.CoaRowDto
import com.zillit.desktop.feature.purchaseorder.data.PoSettingsDto
import com.zillit.desktop.feature.purchaseorder.data.leaves
import com.zillit.desktop.feature.purchaseorder.data.poRefreshFor
import com.zillit.desktop.feature.purchaseorder.data.toJson
import com.zillit.desktop.feature.purchaseorder.domain.AssetFilters
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoDescriptionFormat
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Settings tab's wire shapes and rules — the web's `POSettings.jsx`
 * mappers, checked against what the service actually sends.
 */
class PoSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the settings document decodes with its asset rule and its rules`() {
        val bundle = json.decodeFromString(
            PoSettingsDto.serializer(),
            """
            {"description_format":"ITEM_DDMON","auto_split_rentals":false,"default_split_type":"daily",
             "po_number_prefix":"qw","terms_attachment":{},
             "asset_filters":{"price":{"low":"100","high":null},"exp_type":["*"],"tags":["Camera"]},
             "assignment_rules":[{"id":"r1","departments":"[\"d1\",\"d2\"]","vendors":[],
               "nominal_codes":["2400"],"amount_min":500.0,"target_user_id":"u1","is_active":true,"priority":2}]}
            """.trimIndent(),
        ).toBundle()

        val settings = bundle.settings
        assertEquals(PoDescriptionFormat.ItemDayMonth, settings.descriptionFormat)
        assertFalse(settings.autoSplitRentals)
        assertEquals("QW", settings.numberPrefix, "normalised on the way in")
        assertNull(settings.termsDocument, "an empty object is the server's null")
        assertEquals("100", settings.assetFilters.priceLow)
        assertEquals("", settings.assetFilters.priceHigh)
        assertTrue(settings.assetFilters.expTypes.isEmpty(), "the every-type sentinel reads as no constraint")
        assertEquals(listOf("Camera"), settings.assetFilters.tags)

        val rule = bundle.rules.single()
        assertEquals(listOf("d1", "d2"), rule.departments, "a list encoded into a string still reads")
        assertEquals(listOf("2400"), rule.nominalCodes)
        assertEquals("500", rule.amountMin, "a whole minimum shows without its .0")
        assertEquals("u1", rule.assignTo)
        assertEquals(2, rule.priority)
        assertTrue(rule.persisted)
    }

    @Test
    fun `an empty asset rule goes as null, a partial one collapses what is unset`() {
        assertEquals(JsonNull, AssetFilters().toJson())

        val partial = AssetFilters(priceLow = "1,500", expTypes = listOf("Purchase")).toJson().jsonObject
        assertEquals(1_500.0, partial.getValue("price").jsonObject.getValue("low").jsonPrimitive.content.toDouble())
        assertEquals(JsonNull, partial.getValue("price").jsonObject.getValue("high"))
        assertEquals("Purchase", partial.getValue("exp_type").jsonArray.single().jsonPrimitive.content)
        assertEquals(JsonNull, partial.getValue("tags"))
    }

    @Test
    fun `the asset rule refuses bounds the wrong way round and spells itself out`() {
        assertEquals("Price low must not exceed price high.", AssetFilters(priceLow = "200", priceHigh = "100").error)
        assertEquals("Price low must be zero or more.", AssetFilters(priceLow = "-1").error)
        assertNull(AssetFilters(priceLow = "100", priceHigh = "200").error)

        assertEquals("Every line item on a posted or closed PO — no constraint set.", AssetFilters().summary("£"))
        assertEquals(
            "Lines on a posted or closed PO matching total £100–£2,000.50, and Consumables, " +
                "and tagged Camera or Grip.",
            AssetFilters("100", "2000.5", listOf("Consumption"), listOf("Camera", "Grip")).summary("£"),
        )
        assertEquals(
            "Lines on a posted or closed PO matching total ≥ £50.",
            AssetFilters(priceLow = "50").summary("£"),
        )
    }

    @Test
    fun `a rule summarises its conditions and its body is the web's`() {
        val rule = PoAssignmentRule(
            id = "r1",
            departments = listOf("d1", "d2"),
            vendors = listOf("v1"),
            amountMin = "500",
            assignTo = "u1",
            priority = 1,
        )
        assertEquals("2 depts | 1 vendor | ≥ £500", rule.summary("£"))
        assertEquals("No condition set", PoAssignmentRule(id = "r2").summary("£"))

        val body = JsonObject(rule.toJson())
        assertEquals(2, body.getValue("departments").jsonArray.size)
        assertEquals("500.0", body.getValue("amount_min").jsonPrimitive.content)
        assertEquals("u1", body.getValue("target_user_id").jsonPrimitive.content)
        assertEquals(JsonNull, JsonObject(PoAssignmentRule(id = "r3").toJson()).getValue("amount_min"))
    }

    /** The web's `canAccessAnyPO_accountant`: the two senior designations, and not admin. */
    @Test
    fun `the Settings tab is a senior accountant's`() {
        val senior = PoViewer("u1", "department_accounts", "designation_financial_controller_accounts")
        val junior = PoViewer("u2", "department_accounts", "designation_assistant_accountant")
        val admin = PoViewer("u3", "department_art", null, isProjectAdmin = true)
        assertTrue(PoDestination.Settings.visibleTo(senior))
        assertFalse(PoDestination.Settings.visibleTo(junior))
        assertFalse(PoDestination.Settings.visibleTo(admin))
        assertEquals(PoDestination.Settings, PoDestination.forRoute("/film-tools/purchase-order/settings"))
    }

    @Test
    fun `settings frames refresh the tab, and only this module's rules`() {
        assertEquals(PoRefresh.Settings, poRefreshFor(SocketEventName("po_settings:updated"), null))
        assertEquals(PoRefresh.Settings, poRefreshFor(SocketEventName("assignment_rule:created"), "purchase_orders"))
        assertNull(poRefreshFor(SocketEventName("assignment_rule:updated"), "invoices"))
        assertEquals(PoRefresh.Orders, poRefreshFor(SocketEventName("po:created"), null))
    }

    /** The web's `computeLeafRows`: typed charts offer categories and sub-categories; untyped ones, every row. */
    @Test
    fun `the chart's postable leaves are what a rule can name`() {
        val typed = listOf(
            CoaRowDto(code = "2000", name = "Camera", lineType = "section"),
            CoaRowDto(code = "2400", name = "Equipment Hire", lineType = "category"),
            CoaRowDto(code = "2410", name = "Lenses", lineType = "sub_category"),
            CoaRowDto(code = "2499", name = "Gone", lineType = "category", isActive = false),
        )
        assertEquals(listOf("2400", "2410"), typed.leaves().map { it.code })
        assertEquals("2400 — Equipment Hire", typed.leaves().first().label)

        val untyped = listOf(CoaRowDto(code = "1"), CoaRowDto(code = "2"))
        assertEquals(2, untyped.leaves().size)
    }
}
