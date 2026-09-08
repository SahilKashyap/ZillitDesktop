@file:Suppress("MaxLineLength") // Wire fixtures read best unbroken.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.data.DealDto
import com.zillit.desktop.feature.dealmemo.data.DealHistoryDto
import com.zillit.desktop.feature.dealmemo.data.createBody
import com.zillit.desktop.feature.dealmemo.data.newClientDealId
import com.zillit.desktop.feature.dealmemo.data.updateBody
import com.zillit.desktop.feature.dealmemo.domain.AmendmentAck
import com.zillit.desktop.feature.dealmemo.domain.DealRates
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.NewDeal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deal-memo wire as the backend actually stores and serves it — the web's
 * `toDealMemoPayload.js` step-wise shape on the way out, and the fields the
 * list/detail pages read on the way back. The first desktop create sent a flat
 * body (`user_id`, `weekly_rate`, …): the server answered success, spread it
 * into a document no read path recognises, and both listings stayed empty.
 * These tests pin the corrected shape.
 */
class DealWireShapeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val deal = NewDeal(
        userId = "user-77",
        crewName = "Ada Lovelace",
        departmentId = "department_camera",
        designation = "designation_gaffer_lighting_electrical",
        currency = "GBP",
        rates = DealRates(
            weeklyRate = 2_400.0,
            dailyRate = 400.0,
            hourlyRate = 40.0,
            overtimeRate = 60.0,
            standardHours = 10.0,
            daysPerWeek = 6.0,
            boxRental = 150.0,
        ),
        startDate = 1_754_000_000_000,
        endDate = 1_756_000_000_000,
        unionId = "bectu",
        agreementId = "pact-bectu-mmp",
        nominalCode = "4100",
        notes = "Six-day week.",
    )

    @Test
    fun `the client-minted id is ObjectId-shaped, never a uuid`() {
        // toDealMemoPayload.js:7-19 — 24 hex chars or the create 422s in the
        // ObjectId cast.
        val id = newClientDealId()
        assertTrue(Regex("^[0-9a-f]{24}$").matches(id), "got '$id'")
        assertNotEquals(id, newClientDealId(), "ids must be random")
    }

    @Test
    fun `the create body is the web's step-wise payload, byte for byte`() {
        assertEquals(
            Json.parseToJsonElement(expectedCreateBody),
            deal.createBody("0123456789abcdef01234567"),
        )
    }

    /**
     * What the wire must carry — `toDealMemoPayload.js:723-1098` with the
     * desktop's fields mapped in and the untouched-wizard defaults elsewhere.
     */
    private val expectedCreateBody = """
            {
              "_id": "0123456789abcdef01234567",
              "user_id": "user-77",
              "is_external": false,
              "company_id": null,
              "department_id": null,
              "designation_id": null,
              "deal_reference": null,
              "status": "draft",
              "territory_union": {
                "prod_entity": "", "prod_type": "", "territory_code": "",
                "agreement_identifier": "pact-bectu-mmp",
                "union_identifier": "bectu",
                "branch_identifier": "bectu",
                "band": "", "special_dept": false,
                "special_dept_extra_hours": {"hrs": 0, "multiplier": 1.0, "basis": null, "note": null},
                "territory": null, "union": null, "union_band": ""
              },
              "crew_details": {
                "crew_name": "Ada Lovelace",
                "full_legal_name": null, "preferred_name": null,
                "screen_credit_designation": null, "passport_attachment": [],
                "department_id": null, "designation_id": null,
                "department_identifier": "department_camera",
                "designation_identifier": "designation_gaffer_lighting_electrical",
                "custom_designation": null,
                "reports_to": "HOD", "call_sheet_tier": "HOD",
                "crew_type": null, "experience_yrs": null, "emp_status": "",
                "agency_id": null, "agency_name": null,
                "dob": null, "insurance_no": null, "tax_code": null,
                "right_to_work": null, "home_address": null,
                "emergency_contact": null, "emergency_contact_name": null, "emergency_contact_number": null,
                "emergency_details": {"name": "", "country_code": "", "phone_number": "", "email": "", "address": ""},
                "representative_details": {"name": "", "country_code": "", "phone_number": "", "email": "", "address": ""},
                "email": null, "mobile": null, "gender": null, "unit": null,
                "mandatory_fields": []
              },
              "deal": {
                "type": "weekly",
                "start_date": 1754000000000,
                "end_date": 1756000000000,
                "deal_completion_due": null, "deal_memo_date": null,
                "billing_basis": "week",
                "prep": {"start_date": null, "end_date": null},
                "shoot": {"start_date": null, "end_date": null},
                "wrap": {"start_date": null, "end_date": null},
                "notice_period": "", "notice_reminder": "",
                "additional_notes": "Six-day week."
              },
              "credit_conditions": {
                "work_location": "studio", "travel_zone": "30mile",
                "distant_loc_applied": false, "custom_conditions": []
              },
              "rates": {
                "contract_currency": "GBP", "pay_currency": "GBP",
                "daily": {"rate": 400.0, "hrs": 10.0},
                "weekly": {"rate": 2400.0, "hrs": 0},
                "hr_rate": 40.0,
                "phase_rates": {"on": false, "prep_rate": null, "shoot_rate": null, "wrap_rate": null},
                "picture_fee": null, "buyout_rate": null, "buyout_covers": null,
                "buyout_daily_rate": null, "dga_production_fee": null,
                "travel_day_full": true, "rest_day_double": false,
                "nominal_code": "4100"
              },
              "holiday_pay": {"type": null, "amount": null, "treatment": null, "nominal_code": ""},
              "rules_customized": false,
              "overtimes": [], "premiums": [], "turnarounds": [], "fringes": [], "extra_fees": [],
              "day_types": [], "custom_days": [],
              "allowances": [],
              "rentals": [{
                "id": "box_rental", "name": "Box Rental", "rate_type": "flat",
                "amount": 150.0, "rate_pct": null, "currency": "GBP", "basis": "week",
                "enable": true, "required": false, "nominal_code": "",
                "pay_frequency": "weekly", "applies_to": "full_production",
                "cap_type": null, "cap_amount": null
              }],
              "penalties": [],
              "nominal_coding": {
                "primary_code": "1400", "budget_line": "Below-the-Line Labour",
                "department_id": null, "hetv_class": "G", "uk_spend": "Yes",
                "tax_credit_rate": 25, "tax_credits": []
              },
              "compliance_onboarding": {
                "checks": [],
                "onboard": [
                  {"id": "starter", "label": "P45 or Starter Checklist", "required": true, "done": false},
                  {"id": "rtw-doc", "label": "Right to Work documentation", "required": true, "done": false},
                  {"id": "bank", "label": "Bank account details (BACS)", "required": true, "done": false},
                  {"id": "sds", "label": "IR35 Status Determination Statement", "required": true, "done": false},
                  {"id": "nda", "label": "NDA signed and returned", "required": false, "done": false},
                  {"id": "hs", "label": "H&S induction acknowledged", "required": false, "done": false},
                  {"id": "covid", "label": "COVID-19 policy acknowledged", "required": false, "done": false},
                  {"id": "privacy", "label": "Privacy notice acknowledged", "required": false, "done": false}
                ]
              },
              "additional_documents": {
                "docs": [],
                "doc_settings": {"u_sign": true, "crew_counter_sig": true, "senior_sign_off": false, "copy_prod_office": true}
              },
              "long_form_contract": null,
              "payroll": {
                "bureau": "", "first_pay_period": null, "pay_frequency": "weekly",
                "auto_sync": true, "notify_payroll": true, "include_pdf": false
              },
              "bank": {
                "name": "", "account_holder_name": "", "account_number": "",
                "sort_code": "", "iban_number": "", "swift_code": "", "nominal_code": "",
                "currency": null, "additional_details": []
              }
            }
    """.trimIndent()

    @Test
    fun `a PATCH is the same snapshot without status or the minted id`() {
        // DMCreatePage.jsx:1503-1506 — status is deleted before every PATCH so
        // a save cannot clobber an awaiting_approval deal back to draft; _id is
        // stamped on CREATE payloads only (jsx:1462).
        val body = deal.updateBody()
        assertFalse("status" in body)
        assertFalse("_id" in body)
        assertEquals(deal.createBody("0123456789abcdef01234567").size - 2, body.size)
    }

    @Test
    fun `a stored deal reads back from the nested blocks`() {
        val dto = json.decodeFromString(
            DealDto.serializer(),
            """
            {
              "_id": "68a1b2c3d4e5f60718293a4b",
              "user_id": "user-77",
              "status": "awaiting_approval",
              "deal_reference": "DM-0042",
              "created_at": 1754000000000,
              "amendment_ack": {"status": "pending"},
              "crew_details": {
                "crew_name": "Ada Lovelace",
                "full_legal_name": "Augusta Ada King",
                "department_identifier": "department_camera",
                "designation_identifier": "designation_gaffer_lighting_electrical",
                "custom_designation": null,
                "email": "ada@example.com"
              },
              "territory_union": {"union_identifier": "bectu", "agreement_identifier": "pact-bectu-mmp"},
              "deal": {"start_date": 1754000000000, "end_date": null, "additional_notes": "Six-day week."},
              "rates": {
                "contract_currency": "GBP",
                "daily": {"rate": 400, "hrs": 10},
                "weekly": {"rate": 2400, "hrs": 50},
                "hr_rate": 40,
                "nominal_code": "4100"
              },
              "rentals": [{"id": "box_rental", "name": "Box Rental", "amount": 150, "enable": true}]
            }
            """.trimIndent(),
        )
        val stored = dto.toDomain()!!

        assertEquals("68a1b2c3d4e5f60718293a4b", stored.id)
        assertEquals("user-77", stored.userId)
        assertEquals("Ada Lovelace", stored.crewName, "crew_details.crew_name — DMDealsPage.jsx:362")
        assertEquals("ada@example.com", stored.email)
        assertEquals("department_camera", stored.departmentId, "identifier is authoritative — dealCrew.js:61")
        assertEquals("Camera", stored.departmentName, "the web's identifier humanise fallback — utils.js:60")
        assertEquals("Gaffer Lighting Electrical", stored.designation)
        assertEquals("designation_gaffer_lighting_electrical", stored.designationIdentifier)
        assertEquals(DealStatus.AwaitingApproval, stored.status)
        assertEquals("GBP", stored.currency)
        assertEquals(2_400.0, stored.rates.weeklyRate, "rates.weekly.rate — DMDealPreviewPage.jsx:1522")
        assertEquals(400.0, stored.rates.dailyRate, "rates.daily.rate — DMDealsPage.jsx:493")
        assertEquals(40.0, stored.rates.hourlyRate, "rates.hr_rate — DMDealPreviewPage.jsx:2954")
        assertEquals(10.0, stored.rates.standardHours, "rates.daily.hrs — fromDealMemoPayload js:1386")
        assertEquals(150.0, stored.rates.boxRental, "the deal-authored rentals row")
        assertEquals(1_754_000_000_000, stored.startDate, "deal.start_date — DMDealsPage.jsx:491")
        assertNull(stored.endDate)
        assertEquals("bectu", stored.unionName)
        assertEquals("pact-bectu-mmp", stored.agreementName)
        assertEquals("4100", stored.nominalCode)
        assertEquals("Six-day week.", stored.notes)
        assertEquals(1_754_000_000_000, stored.createdAt)
        assertEquals(AmendmentAck.Pending, stored.amendmentAck)
        assertTrue(stored.awaitingReacknowledgement, "amendment_ack pending — DMDealPreviewPage.jsx:1433")
        assertFalse(stored.acknowledged)
    }

    @Test
    fun `a custom designation beats the identifier, as every list renders it`() {
        // dealCrew.js:47,65 — custom_designation first.
        val dto = json.decodeFromString(
            DealDto.serializer(),
            """{"_id": "68a1b2c3d4e5f60718293a4b", "crew_details": {"crew_name": "Ada", "custom_designation": "Chief Rigger", "designation_identifier": "designation_gaffer"}}""",
        )
        assertEquals("Chief Rigger", dto.toDomain()!!.designation)
    }

    @Test
    fun `a designation drops the department it repeats`() {
        // ZL-21094 — `designation_action_prop_buyer_art_department` sits inside
        // `department_art_department`, and the tail is the department again.
        val dto = json.decodeFromString(
            DealDto.serializer(),
            """{"_id": "68a1b2c3d4e5f60718293a4b", "crew_details": {"crew_name": "Ada", "designation_identifier": "designation_action_prop_buyer_art_department", "department_identifier": "department_art_department"}}""",
        )
        assertEquals("Action Prop Buyer", dto.toDomain()!!.designation)
    }

    @Test
    fun `a designation ending in a word its department does not own is left whole`() {
        val dto = json.decodeFromString(
            DealDto.serializer(),
            """{"_id": "68a1b2c3d4e5f60718293a4b", "crew_details": {"crew_name": "Ada", "designation_identifier": "designation_art_director", "department_identifier": "department_camera"}}""",
        )
        assertEquals("Art Director", dto.toDomain()!!.designation)
    }

    @Test
    fun `the passport key is a list, not the object it used to be`() {
        // ZL-20959 — the backend widened the same key; the old single object is
        // dropped on arrival. `[]` is also a clear, which is why nothing
        // re-sends this body to re-notify a crew member (see `chase`).
        val crew = deal.updateBody()["crew_details"]!!.jsonObject
        assertEquals(JsonArray(emptyList()), crew["passport_attachment"])
    }

    @Test
    fun `the old flat spelling still reads, as a fallback`() {
        val dto = json.decodeFromString(
            DealDto.serializer(),
            """
            {"id": "deal-1", "user_id": "u1", "crew_name": "Grace", "status": "active",
             "currency": "GBP", "weekly_rate": "2000", "daily_rate": "350",
             "start_date": "1754000000000", "nominal_code": "7000"}
            """.trimIndent(),
        )
        val stored = dto.toDomain()!!
        assertEquals("Grace", stored.crewName)
        assertEquals(2_000.0, stored.rates.weeklyRate)
        assertEquals(350.0, stored.rates.dailyRate)
        assertEquals(1_754_000_000_000, stored.startDate)
        assertEquals("7000", stored.nominalCode)
        assertEquals(DealStatus.Active, stored.status)
        assertEquals(AmendmentAck.None, stored.amendmentAck)
    }

    @Test
    fun `history rows carry action_by and action_at`() {
        // deal-memo.js:117-119 and HistoryPanel.jsx:169,224-239.
        val row = json.decodeFromString(
            DealHistoryDto.serializer(),
            """{"action": "submitted", "action_by": "user-9", "action_at": 1754000000000, "note": "sent up"}""",
        ).toDomain()
        assertEquals("submitted", row.action)
        assertEquals("user-9", row.userId)
        assertEquals(1_754_000_000_000, row.at)
        assertEquals("sent up", row.note)
    }
}
