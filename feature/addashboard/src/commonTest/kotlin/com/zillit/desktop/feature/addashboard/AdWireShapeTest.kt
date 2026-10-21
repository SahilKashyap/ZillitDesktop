package com.zillit.desktop.feature.addashboard

import com.zillit.desktop.feature.addashboard.data.ArtisteDto
import com.zillit.desktop.feature.addashboard.data.PoQueueDto
import com.zillit.desktop.feature.addashboard.data.RateConfigDto
import com.zillit.desktop.feature.addashboard.data.ReportDto
import com.zillit.desktop.feature.addashboard.data.SaDayDto
import com.zillit.desktop.feature.addashboard.data.ShootDayDto
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.RateKind
import com.zillit.desktop.feature.addashboard.domain.ReportFills
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shapes the `ad-dashboard` service really sends, pinned against captured
 * payloads.
 *
 * Built from the web's own wrappers and the service's responses, because the
 * traps here are all silent: a number sent as a string, a flag sent as a
 * quoted word, and an object-of-lists read as a list all decode to something
 * empty rather than to an error.
 */
class AdWireShapeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // -- the register ----------------------------------------------------------

    @Test
    fun `an artiste decodes with its nested blocks`() {
        val dto = json.decodeFromString(
            ArtisteDto.serializer(),
            """
            {
              "_id": "a1",
              "is_external": true,
              "ref_number": 42,
              "category": "stand_in",
              "engagement_type": "agency",
              "status": "verified",
              "agency_name": "Scene One Extras",
              "agency_id": "v1",
              "is_minor": "false",
              "personal_details": {
                "first_name": "Ada",
                "last_name": "Lovelace",
                "email": "ada@example.com",
                "phone": 447700900000
              },
              "union_details": { "union": "PACT/Equity Agreement 2022" },
              "bank": { "iban_number": "GB33BUKB20201555555555" },
              "deal": {
                "day_types": [{ "day_type": "SWD", "work_min": "600" }],
                "allowances": [{ "id": "costume", "name": "Costume", "amount": "10.00" }]
              }
            }
            """.trimIndent(),
        )

        val artiste = dto.toDomain { null }!!
        assertEquals("Ada Lovelace", artiste.name)
        assertEquals("42", artiste.refNumber, "a numeric reference still reads as its text")
        assertEquals(ArtisteCategory.StandIn, artiste.category)
        assertEquals(EngagementType.Agency, artiste.engagement)
        assertEquals("v1", artiste.agencyId, "the id is what a purchase order is raised against")
        assertEquals("PACT/Equity Agreement 2022", artiste.union)
        assertEquals("447700900000", artiste.phone)
        // A quoted "false" is false, not "a non-empty string is true".
        assertFalse(artiste.isMinor)
        // An IBAN on its own satisfies the bank requirement.
        assertTrue(artiste.bank.hasNumber)
        assertEquals(600, artiste.deal.dayTypes.single().workMinutes)
        assertEquals(10.0, artiste.deal.allowances.single().rateAmount)
    }

    /**
     * An internal artiste's name comes off the project user.
     *
     * It is not on the artiste record at all, which is why the mapper takes a
     * resolver rather than reading a field.
     */
    @Test
    fun `an internal artiste is named by the crew list`() {
        val dto = json.decodeFromString(
            ArtisteDto.serializer(),
            """{ "_id": "a2", "user_id": "u9", "is_external": false }""",
        )

        assertEquals("Ravi Menon", dto.toDomain { if (it == "u9") "Ravi Menon" else null }!!.name)
    }

    // -- the day ---------------------------------------------------------------

    /**
     * Times are wall-clock **epochs**, rendered in UTC.
     *
     * A 07:30 call is stored as that moment on the shoot day and shows 07:30
     * to every viewer; rendering through the record's own `timezone` field
     * would show the AD's zone to the artiste.
     */
    @Test
    fun `an artiste-day decodes its times as UTC wall-clock`() {
        val dto = json.decodeFromString(
            SaDayDto.serializer(),
            """
            {
              "_id": "s1",
              "shoot_date": 1772755200000,
              "call_time": 1772782200000,
              "wrap_time": 1772827800000,
              "attendance_status": "present",
              "status": "submitted",
              "gross_amount": "142.5",
              "currency": "GBP",
              "day_type": "SWD",
              "allowances": [{ "id": "costume", "name": "Costume", "amount": 10 }],
              "additional_fees": [
                { "identifier": "custom", "name": "Night bus", "amount": 7.5 },
                { "identifier": "pct", "name": "Agency fee", "rate_type": "pct", "rate_pct": 15 }
              ],
              "artiste_id": { "_id": "a1", "is_external": true, "personal_details": { "first_name": "Ada" } }
            }
            """.trimIndent(),
        )

        val row = dto.toDomain { null }!!
        assertEquals("07:30", row.callTime)
        assertEquals("20:10", row.wrapTime)
        assertEquals("Ada", row.artisteName)
        assertEquals(142.5, row.gross)
        // The engine's gross plus the day's FLAT manual lines; a percentage
        // line carries no cash and must not be added.
        assertEquals(160.0, row.payTotal)
        assertEquals("Night bus", row.customFee?.label)
        assertTrue(row.isPresent)
        assertTrue(row.isSubmitted)
    }

    /** A call time alone means present, however the attendance field reads. */
    @Test
    fun `a call time alone makes a row present`() {
        val dto = json.decodeFromString(
            SaDayDto.serializer(),
            """{ "_id": "s2", "call_time": 1772782200000 }""",
        )

        assertTrue(dto.toDomain { null }!!.isPresent)
    }

    @Test
    fun `a day header decodes its meals, its orders and its signature`() {
        val dto = json.decodeFromString(
            ShootDayDto.serializer(),
            """
            {
              "_id": "d1",
              "shoot_date": 1772755200000,
              "day_number": "12",
              "status": "submitted",
              "call_time": 1772782200000,
              "scene_schedule": ["38", "39"],
              "meals": [{ "order": 1, "type": "Lunch", "start_time": 1772802000000, "end_time": 1772803800000 }],
              "purchase_orders": [
                { "po_id": "po1", "agency": "Scene One Extras", "agency_id": "v1", "currency": "GBP", "amount": "198" }
              ],
              "po_reference": "PO-D12-20260306",
              "sign_status": "e-sign",
              "signature": { "typed_name": "A. Director", "signed_at": 1772830000000 }
            }
            """.trimIndent(),
        )

        val day = dto.toDomain()!!
        assertEquals(12, day.dayNumber)
        assertEquals("07:30", day.callTime)
        assertEquals("38, 39", day.sceneSchedule)
        assertEquals("13:00", day.meals.single().startTime)
        assertEquals("13:30", day.meals.single().endTime)
        assertEquals(198.0, day.purchaseOrders.single().amount)
        assertEquals("A. Director", day.signature.typedName)
        // Submitted locks the day for everyone, the AD included.
        assertFalse(day.editable)
    }

    /**
     * The queue is an **object of lists**.
     *
     * Read as an array it would come back empty with no error, because a list
     * reader rewrites a non-array body to `[]`.
     */
    @Test
    fun `the PO queue decodes its three lists`() {
        val dto = json.decodeFromString(
            PoQueueDto.serializer(),
            """
            {
              "agency_pos": [
                { "po_id": "po1", "po_reference": "PO-D12-1", "agency": "Scene One", "amount": 198,
                  "currency": "GBP", "status": "PENDING", "day_number": 12 }
              ],
              "payroll": [
                { "label": "Week 12", "period": "02–08 Mar", "amount": "1420", "currency": "GBP",
                  "status": "queued", "artiste_count": "9" }
              ],
              "pending": [{ "artiste_id": "a3", "name": "Sam Reid", "reason": "RTW unresolved", "status": "blocked" }]
            }
            """.trimIndent(),
        )

        val queue = dto.toDomain()
        assertEquals("PO-D12-1", queue.agencyPos.single().poReference)
        assertTrue(queue.agencyPos.single().awaiting)
        assertEquals(9, queue.payroll.single().artisteCount)
        assertTrue(queue.pending.single().blocked)
        assertFalse(queue.isEmpty)
    }

    // -- rates -----------------------------------------------------------------

    /**
     * A pay rule travels nested, with the editor's fields one level down
     * inside `source` and the rule's **template** riding in `source.id`.
     *
     * That is what makes a saved rule reopen as the same rule rather than as a
     * generic one.
     */
    @Test
    fun `a pay rule round-trips its template and its trigger`() {
        val dto = json.decodeFromString(
            RateConfigDto.serializer(),
            """
            {
              "non_union": {
                "day_rates": [{ "category": "General SA", "amount": 142 }],
                "day_types": [{ "day_type": "SWD", "work_min": 600, "meal_break_min": 60 }],
                "overtimes": [
                  {
                    "row_id": "r1",
                    "nominal_code": "7200",
                    "source": {
                      "id": "ot", "label": "OT after 10", "rate_type": "multiplier",
                      "rate_amount": 1.5, "basis": "hour", "max": 500,
                      "triggers": [{ "after": 600, "increment": 15 }]
                    }
                  }
                ],
                "penalties": [
                  {
                    "row_id": "r2",
                    "source": {
                      "id": "broken_turnaround", "label": "Broken turnaround", "rate_type": "flat",
                      "rate_amount": 50, "basis": "event", "triggers": [{ "less": 600 }]
                    }
                  }
                ],
                "allowances": [{ "id": "costume", "name": "Costume", "amount": 10, "nominal_code": "7210" }],
                "distribution": [{ "kind": "user", "ref": "u1", "name": "A. Director", "email": "ad@example.com" }]
              },
              "union_agreements": [{ "id": "ag1", "label": "PACT/Equity 2022" }],
              "vendors": [{ "_id": "v1", "name": "Scene One Extras", "status": "VERIFIED" }]
            }
            """.trimIndent(),
        )

        val config = dto.toDomain()
        assertEquals("142", config.nonUnion.dayRates["General SA"])
        assertEquals(600, config.nonUnion.dayTypes.single().workMinutes)

        val overtime = config.nonUnion.overtimes.single()
        assertEquals("ot", overtime.templateId)
        assertEquals(RateKind.Multiplier, overtime.rateKind)
        assertEquals("1.5", overtime.amount)
        // 600 minutes read back as the 10 hours the field was typed in.
        assertEquals("10", overtime.formValue)
        assertEquals("15", overtime.increments)
        assertEquals("500", overtime.cap)
        assertEquals("7200", overtime.nominalCode)

        // The turnaround's threshold is `less`, not `after`.
        assertEquals("10", config.nonUnion.penalties.single().formValue)

        assertEquals("costume", config.nonUnion.allowances.single().identifier)
        assertEquals("ad@example.com", config.nonUnion.distribution.single().email)
        assertEquals("PACT/Equity 2022", config.unionAgreements.single().label)
        // A row with no boolean says it is verified with its status instead.
        assertTrue(config.vendors.single().verified)
    }

    // -- reports ---------------------------------------------------------------

    /**
     * A report's values travel as a flat array and are held as a map keyed
     * `cellId.rowId.col`.
     *
     * The named header fields are **not** in there: they live on `meta.header`,
     * because Picture Title and Company are constant for the whole shoot.
     */
    @Test
    fun `a report decodes its layout, its fills and its header fields`() {
        val dto = json.decodeFromString(
            ReportDto.serializer(),
            """
            {
              "_id": "r1",
              "name": "Wrap report",
              "status": "posted",
              "report_type": "wrap_report",
              "shoot_date": 1772755200000,
              "day_number": 12,
              "pageRows": [
                {
                  "id": "pr1",
                  "cells": [
                    {
                      "id": "cell1", "type": "section", "title": "Day",
                      "columns": [{ "id": "c1", "label": "Field" }, { "id": "c2", "label": "Value" }],
                      "rows": [
                        { "id": "row1", "cells": ["Crew Call", ""], "field": "call_time" },
                        { "id": "row2", "cells": ["Picture Title", ""], "hkey": "picture_title" }
                      ]
                    },
                    {
                      "id": "cell2", "type": "table", "title": "Cast",
                      "columns": [{ "id": "c3", "label": "Cast" }, { "id": "c4", "type": "sign", "label": "Signature" }],
                      "rows": [{ "id": "row3", "cells": ["Ada Lovelace", ""] }]
                    }
                  ]
                }
              ],
              "fills": [{ "cell_id": "cell1", "row_id": "row1", "col": 1, "value": "07:30" }],
              "meta": { "header": { "picture_title": "The Feature" } },
              "signatures": [
                { "person_id": "Ada Lovelace", "cell_id": "cell2", "row_id": "row3", "status": "signed" }
              ]
            }
            """.trimIndent(),
        )

        val report = dto.toDomain(ReportKind.WrapReport)!!
        assertEquals(ReportKind.WrapReport, report.kind)
        assertTrue(report.published)
        assertEquals("07:30", report.fills[ReportFills.key("cell1", "row1", 1)])
        assertEquals("The Feature", report.header["picture_title"])
        // The signature column is found by its type, and by its label on a
        // report frozen before that marker existed.
        assertEquals(1, report.cells.last().signatureColumn)
        // Its one cast row is signed, so nothing is outstanding.
        assertTrue(report.unsignedCast.isEmpty())
    }

    /**
     * A row's own `report_type` wins, and the endpoint's kind stands in when
     * it has none.
     *
     * Untyped legacy rows belong to the AD Report, which is the only kind that
     * asks for them.
     */
    @Test
    fun `an untyped report takes the endpoint's kind`() {
        val dto = json.decodeFromString(ReportDto.serializer(), """{ "_id": "r9", "status": "draft" }""")

        assertEquals(ReportKind.AdReport, dto.toDomain(ReportKind.AdReport)!!.kind)
        assertEquals(ReportKind.WrapReport, dto.toDomain(ReportKind.WrapReport)!!.kind)
    }

    /** A record with no id is not a record; it is dropped rather than guessed at. */
    @Test
    fun `a row with no id is dropped`() {
        assertNull(json.decodeFromString(ArtisteDto.serializer(), "{}").toDomain { null })
        assertNull(json.decodeFromString(SaDayDto.serializer(), "{}").toDomain { null })
        assertNull(json.decodeFromString(ShootDayDto.serializer(), "{}").toDomain())
    }
}
