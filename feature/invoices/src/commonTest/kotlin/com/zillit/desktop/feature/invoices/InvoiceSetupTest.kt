package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.data.alertsBody
import com.zillit.desktop.feature.invoices.data.parseLinkedPos
import com.zillit.desktop.feature.invoices.data.parsePoSuggestions
import com.zillit.desktop.feature.invoices.data.parseSetup
import com.zillit.desktop.feature.invoices.data.runAuthBody
import com.zillit.desktop.feature.invoices.data.teamBody
import com.zillit.desktop.feature.invoices.domain.InvoiceAlert
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceSetup
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow
import com.zillit.desktop.feature.invoices.domain.RunAuthLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Settings page's wire, and the two decisions the page makes on its own. */
class InvoiceSetupTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(text: String) = parseSetup(json.parseToJsonElement(text))

    /** The document's three list fields, read straight. */
    @Test
    fun `the settings document decodes`() {
        val bundle = parse(
            """
            {
              "team_members": [
                {"user_id": "u1", "posting_limit": 5000, "run_access": true, "override_access": false,
                 "is_senior": false},
                {"user_id": "u2", "posting_limit": null, "is_senior": true}
              ],
              "alerts": ["invoice_overdue", "daily_ap_summary"],
              "run_authorization": [
                {"tier": 1, "user": ["u1", "u2"]},
                {"tier": 2, "user": ["u3"]}
              ],
              "assignment_rules": [
                {"id": "r1", "departments": ["d1"], "vendors": [], "nominal_codes": ["2400"],
                 "amount_min": "500", "target_user_id": "u1", "is_active": true, "priority": 0}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(2, bundle.setup.teamMembers.size)
        assertEquals(5000.0, bundle.setup.teamMembers.first().postingLimit)
        // A senior has no ceiling whatever the stored limit says.
        assertTrue(bundle.setup.teamMembers[1].isUnlimited)
        assertTrue(bundle.setup.isOn(InvoiceAlert.InvoiceOverdue))
        assertFalse(bundle.setup.isOn(InvoiceAlert.DuplicateDetection))
        assertEquals(listOf("u1", "u2"), bundle.setup.runAuthorisation.first().userIds)
        assertEquals(1, bundle.rules.size)
        assertEquals("500", bundle.rules.first().amountMin)
        assertTrue(bundle.rules.first().persisted)
    }

    /**
     * The hub hands these three back as JSON encoded into a string when the
     * driver leaves a jsonb column unparsed; the web guards every read the
     * same way.
     */
    @Test
    fun `a list stored as a string still decodes`() {
        val bundle = parse(
            """
            {
              "team_members": "[{\"user_id\":\"u9\",\"posting_limit\":\"unlimited\"}]",
              "alerts": "[\"over_po_flagging\"]",
              "run_authorization": "[{\"tier\":1,\"user\":[\"u9\"]}]"
            }
            """.trimIndent(),
        )
        assertEquals("u9", bundle.setup.teamMembers.single().userId)
        // "unlimited" is the string form of no ceiling.
        assertNull(bundle.setup.teamMembers.single().postingLimit)
        assertTrue(bundle.setup.isOn(InvoiceAlert.OverPoFlagging))
        assertEquals(listOf("u9"), bundle.setup.runAuthorisation.single().userIds)
    }

    /** A senior's rights are sent as granted, and an unlimited limit as null — never "unlimited". */
    @Test
    fun `the team body sends what the server stores`() {
        val body = teamBody(
            listOf(
                InvoiceTeamRow("u1", postingLimit = null, isSenior = true),
                InvoiceTeamRow("u2", postingLimit = 0.0),
            ),
        )
        val rows = body["team_members"]!!.jsonArray
        val senior = rows[0] as JsonObject
        assertEquals(JsonNull, senior["posting_limit"])
        assertTrue(senior["run_access"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(senior["override_access"]!!.jsonPrimitive.content.toBoolean())
        // Zero is "submit only", which is not the same as no ceiling.
        assertEquals("0.0", (rows[1] as JsonObject)["posting_limit"]!!.jsonPrimitive.content)
    }

    /**
     * The web sends an untouched member back exactly as stored — extra keys, a
     * string "unlimited" — and an edited one with its stored keys kept and the
     * five this page edits written over them (`SettingsPage.jsx:524-526`).
     */
    @Test
    fun `a team save keeps what the page does not edit`() {
        val bundle = parse(
            """
            {"team_members":[
               {"user_id":"u1","posting_limit":"unlimited","run_access":false,"note":"keep me","level":3},
               {"user_id":"u2","posting_limit":500,"run_access":false,"override_access":false,"is_senior":false,"x":1}
            ]}
            """.trimIndent(),
        )
        val (untouched, stored) = bundle.setup.teamMembers
        val edited = stored.copy(postingLimit = 750.0, runAccess = true)
        val rows = teamBody(listOf(untouched, edited))["team_members"]!!.jsonArray.map { it as JsonObject }

        assertEquals("unlimited", rows[0]["posting_limit"]!!.jsonPrimitive.content, "sent back exactly as stored")
        assertEquals("keep me", rows[0]["note"]!!.jsonPrimitive.content)
        assertFalse("is_senior" in rows[0], "no key the stored member did not have")

        assertEquals("750.0", rows[1]["posting_limit"]!!.jsonPrimitive.content)
        assertTrue(rows[1]["run_access"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("1", rows[1]["x"]!!.jsonPrimitive.content, "an edited member keeps its other keys")
    }

    @Test
    fun `the alert and run-auth bodies keep their shapes`() {
        val alerts = alertsBody(setOf("invoice_overdue"))
        assertEquals(listOf("invoice_overdue"), alerts["alerts"]!!.jsonArray.map { it.jsonPrimitive.content })
        val chain = runAuthBody(listOf(RunAuthLevel(1, listOf("u1"))))
        val level = chain["run_authorization"]!!.jsonArray.single() as JsonObject
        assertEquals("1", level["tier"]!!.jsonPrimitive.content)
        assertEquals(listOf("u1"), level["user"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /** Inserting and removing a level renumbers the chain, as the web's splice does. */
    @Test
    fun `the sign-off chain renumbers itself`() {
        val start = InvoiceSetup(runAuthorisation = listOf(RunAuthLevel(1, listOf("a")), RunAuthLevel(2, listOf("b"))))
        val inserted = start.insertingLevel(1)
        assertEquals(listOf(1, 2, 3), inserted.runAuthorisation.map { it.tier })
        assertEquals(listOf("a"), inserted.runAuthorisation[0].userIds)
        assertTrue(inserted.runAuthorisation[1].userIds.isEmpty())
        assertEquals(listOf("b"), inserted.runAuthorisation[2].userIds)

        val removed = inserted.removingLevel(1)
        assertEquals(listOf(1, 2), removed.runAuthorisation.map { it.tier })
        assertTrue(removed.runAuthorisation.first().userIds.isEmpty())
    }

    /** Senior forces the other three on, and toggling it off leaves them as set. */
    @Test
    fun `senior grants the full set`() {
        val plain = InvoiceTeamRow("u1", postingLimit = 100.0)
        val senior = plain.asSenior(true)
        assertTrue(senior.runAccess)
        assertTrue(senior.overrideAccess)
        assertNull(senior.postingLimit)
        val back = senior.asSenior(false)
        assertFalse(back.isSenior)
        assertTrue(back.runAccess, "the rights stay as set so they can be dialled back by hand")
    }

    /** An unknown alert key the server holds is not dropped by a save. */
    @Test
    fun `an unknown alert survives a round trip`() {
        val setup = InvoiceSetup(alerts = setOf("invoice_overdue", "something_new"))
        assertEquals(setOf("something_new"), setup.unknownAlerts)
        val after = setup.toggle(InvoiceAlert.InvoiceOverdue)
        assertTrue("something_new" in after.alerts)
        assertFalse(after.isOn(InvoiceAlert.InvoiceOverdue))
    }

    @Test
    fun `a rule summarises its conditions the way the web does`() {
        val rule = InvoiceAssignmentRule(
            id = "r1",
            departments = listOf("d1", "d2"),
            vendors = listOf("v1"),
            amountMin = "500",
            assignTo = "u1",
        )
        assertEquals("2 depts | 1 vendor | ≥ £500.00", rule.summary("GBP"))
        assertEquals("No condition set", InvoiceAssignmentRule("r2").summary("GBP"))
    }

    // -- PO matching ---------------------------------------------------------

    @Test
    fun `po suggestions decode into their two groups`() {
        val suggestions = parsePoSuggestions(
            json.parseToJsonElement(
                """
                {
                  "vendor_pos": [
                    {"po_id": "p1", "po_number": "PO-0042", "gross_amount": 1200.5,
                     "vendor_name": "Panavision", "score": 0.9, "confidence": "high"}
                  ],
                  "user_pos": [{"po_id": "p2", "po_number": "PO-0043"}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(2, suggestions.total)
        assertEquals("PO-0042", suggestions.vendorPos.single().label)
        assertEquals(0.9, suggestions.vendorPos.single().score)
        assertFalse(suggestions.isEmpty)
    }

    @Test
    fun `linked orders decode with their lines`() {
        val orders = parseLinkedPos(
            json.parseToJsonElement(
                """
                {"data": [
                  {"po_id": "p1", "po_number": "PO-0042", "gross_total": 11000,
                   "line_items": [{"description": "Camera body", "quantity": 2, "total": 5500}]}
                ]}
                """.trimIndent(),
            ),
        )
        val order = orders.single()
        assertEquals("PO-0042", order.label)
        assertEquals(11_000.0, order.grossTotal)
        assertEquals("Camera body", order.lines.single().description)
    }
}
