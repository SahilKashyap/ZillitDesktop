package com.zillit.desktop.feature.saportal

import com.zillit.desktop.feature.saportal.data.ProfileDto
import com.zillit.desktop.feature.saportal.data.QueryDto
import com.zillit.desktop.feature.saportal.data.SummaryDto
import com.zillit.desktop.feature.saportal.data.VoucherDetailDto
import com.zillit.desktop.feature.saportal.data.VoucherDto
import com.zillit.desktop.feature.saportal.domain.VoucherStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sa-portal service's shapes, as `services/v2/sa-portal.js` sends them.
 *
 * Pinned rather than trusted because this is a newer service whose documents
 * grow fields: everything is nullable with a default, so a shape that gains
 * a key must not blank a screen, and one that loses a key must not fail it.
 */
class SaWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    private inline fun <reified T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        body: String,
    ): T = json.decodeFromString(serializer, body)

    @Test
    fun `a voucher reads its day, money and both statuses`() {
        val voucher = decode(
            VoucherDto.serializer(),
            """
            {"id":"v1","code":"VCH-0042-20260826","shoot_date":1772755200000,
             "timezone":"UTC","call_time":"07:00","wrap_time":"19:30",
             "minutes_worked":690,"gross":184.5,"holiday":22.14,"currency":"GBP",
             "status":"pending","day_status":"submitted","category":"Featured",
             "role":"Pub regular","week_starting":1772668800000,
             "attendance_status":"present"}
            """.trimIndent(),
        ).toDomain()

        assertEquals("v1", voucher?.id)
        assertEquals(VoucherStatus.Pending, voucher?.status)
        assertEquals(184.5, voucher?.gross)
        assertEquals(690, voucher?.minutesWorked)
        assertEquals("Pub regular", voucher?.role)
    }

    /**
     * The two statuses are not interchangeable: the server accepts a
     * signature only while the *day* is submitted, whatever the voucher pill
     * says. Reading the pill instead would offer a Sign button the server
     * refuses.
     */
    @Test
    fun `signable follows the day status, not the voucher status`() {
        fun voucher(status: String, dayStatus: String) = decode(
            VoucherDto.serializer(),
            """{"id":"v1","status":"$status","day_status":"$dayStatus"}""",
        ).toDomain()

        assertTrue(voucher("pending", "submitted")?.signable == true)
        assertFalse(voucher("pending", "draft")?.signable == true)
        assertFalse(voucher("signed", "signed")?.signable == true)
    }

    @Test
    fun `an id-less voucher is dropped rather than drawn unopenable`() {
        assertNull(decode(VoucherDto.serializer(), """{"code":"VCH-1"}""").toDomain())
    }

    @Test
    fun `the id is read from either spelling`() {
        assertEquals("v1", decode(VoucherDto.serializer(), """{"_id":"v1"}""").toDomain()?.id)
        assertEquals("v2", decode(VoucherDto.serializer(), """{"id":"v2"}""").toDomain()?.id)
    }

    @Test
    fun `an unrecognised status is Unknown rather than pending`() {
        val voucher = decode(VoucherDto.serializer(), """{"id":"v1","status":"archived"}""").toDomain()

        assertEquals(VoucherStatus.Unknown, voucher?.status)
        assertFalse(voucher?.signable == true)
    }

    @Test
    fun `a thin voucher still reads`() {
        val voucher = decode(VoucherDto.serializer(), """{"id":"v1"}""").toDomain()

        assertEquals("", voucher?.code)
        assertEquals(0.0, voucher?.gross)
        assertNull(voucher?.shootDate)
    }

    // -- detail ---------------------------------------------------------------

    @Test
    fun `a detail carries its lines, meals and signature`() {
        val detail = decode(
            VoucherDetailDto.serializer(),
            """
            {"id":"v1","day_status":"signed","currency":"GBP",
             "day_type":"Shoot","meals":[{"label":"Lunch","from":"13:00","to":"13:30"}],
             "rates_ots":[{"label":"Basic day","amount":120.0},{"name":"OT x1.5","total":40.5}],
             "allowances":[{"description":"Travel","amount":24.0}],
             "notes":"Wet weather cover",
             "signature":{"typed_name":"Ada Lovelace","consent_accuracy":true,
                          "consent_esign":true,"signed_at":1772800000000}}
            """.trimIndent(),
        ).toDomain()

        assertEquals("Shoot", detail?.dayType)
        assertEquals(listOf("Lunch"), detail?.meals?.map { it.label })
        // `label`, `name` and `description` all name a line, in that order.
        assertEquals(listOf("Basic day", "OT x1.5"), detail?.ratesAndOvertime?.map { it.label })
        assertEquals(40.5, detail?.ratesAndOvertime?.last()?.amount)
        assertEquals(listOf("Travel"), detail?.allowances?.map { it.label })
        assertEquals("Ada Lovelace", detail?.signature?.typedName)
        assertTrue(detail?.signature?.complete == true)
    }

    /** An unsigned voucher has no signature object; an empty one would read as signed by nobody. */
    @Test
    fun `an unsigned voucher has no signature at all`() {
        val detail = decode(VoucherDetailDto.serializer(), """{"id":"v1"}""").toDomain()

        assertNull(detail?.signature)
    }

    @Test
    fun `one consent without the other is not a complete signature`() {
        val detail = decode(
            VoucherDetailDto.serializer(),
            """{"id":"v1","signature":{"consent_accuracy":true}}""",
        ).toDomain()

        assertFalse(detail?.signature?.complete == true)
    }

    // -- summary --------------------------------------------------------------

    @Test
    fun `the summary zips its two earnings arrays`() {
        val summary = decode(
            SummaryDto.serializer(),
            """
            {"vouchers":{"total":9,"pending":2,"signed":4,"paid":3},
             "ytd_gross":1640.5,"total_gross":4820.0,"holiday_accrued":198.4,
             "productions_count":2,
             "earnings":{"months":["Jun","Jul","Aug"],"gross":[420.0,610.5,610.0]},
             "action_voucher":{"id":"v1","day_status":"submitted"},
             "next_booking":{"id":"v2","shoot_date":1772841600000}}
            """.trimIndent(),
        ).toDomain()

        assertEquals(3, summary.earnings.size)
        assertEquals("Jul", summary.earnings[1].label)
        assertEquals(610.5, summary.earnings[1].gross)
        assertEquals(2, summary.vouchers.pending)
        assertEquals("v1", summary.actionVoucher?.id)
        assertEquals("v2", summary.nextBooking?.id)
    }

    /**
     * The chart is decoration. A ragged pair of arrays drops the tail rather
     * than throwing — taking the whole dashboard down over a short series
     * would be the worse failure.
     */
    @Test
    fun `a ragged earnings pair is truncated, not fatal`() {
        val summary = decode(
            SummaryDto.serializer(),
            """{"earnings":{"months":["Jun","Jul","Aug"],"gross":[420.0]}}""",
        ).toDomain()

        assertEquals(1, summary.earnings.size)
        assertEquals("Jun", summary.earnings.single().label)
    }

    @Test
    fun `an empty summary reads as zeroes rather than failing`() {
        val summary = decode(SummaryDto.serializer(), "{}").toDomain()

        assertEquals(0, summary.vouchers.total)
        assertEquals(0.0, summary.ytdGross)
        assertNull(summary.actionVoucher)
        assertTrue(summary.earnings.isEmpty())
    }

    // -- profile --------------------------------------------------------------

    @Test
    fun `a profile reads its bank and its checklist`() {
        val profile = decode(
            ProfileDto.serializer(),
            """
            {"_id":"a1","status":"verified","is_external":true,"is_minor":false,
             "artiste_ref":"SA-0042","category":"Featured","engagement_type":"Daily",
             "agency_name":"Central Casting","currency":"GBP",
             "bank":{"name":"Barclays","account_holder_name":"A Lovelace",
                     "account_number":"20481234","sort_code":"204891"},
             "account_status":[{"key":"bank","label":"Bank details","ok":true},
                               {"key":"id","label":"Photo ID","ok":false,"detail":"Not uploaded"}]}
            """.trimIndent(),
        ).toDomain()

        assertEquals("SA-0042", profile?.artisteRef)
        assertEquals("Barclays", profile?.bank?.name)
        assertEquals(listOf("Photo ID"), profile?.outstanding?.map { it.label })
        assertFalse(profile?.complete == true)
    }

    /** A check the server did not answer for is outstanding, never passed. */
    @Test
    fun `a check with no ok flag counts as outstanding`() {
        val profile = decode(
            ProfileDto.serializer(),
            """{"_id":"a1","account_status":[{"key":"id","label":"Photo ID"}]}""",
        ).toDomain()

        assertEquals(1, profile?.outstanding?.size)
    }

    @Test
    fun `a profile with every check done is complete`() {
        val profile = decode(
            ProfileDto.serializer(),
            """{"_id":"a1","account_status":[{"key":"bank","label":"Bank","ok":true}]}""",
        ).toDomain()

        assertTrue(profile?.complete == true)
    }

    /** No checklist is not the same as a complete one — nothing is known yet. */
    @Test
    fun `an empty checklist is not complete`() {
        assertFalse(decode(ProfileDto.serializer(), """{"_id":"a1"}""").toDomain()?.complete == true)
    }

    @Test
    fun `an id-less profile is refused`() {
        assertNull(decode(ProfileDto.serializer(), """{"status":"verified"}""").toDomain())
    }

    // -- queries --------------------------------------------------------------

    @Test
    fun `a query thread reads its messages and which side sent each`() {
        val query = decode(
            QueryDto.serializer(),
            """
            {"_id":"q1","topic":"hours","title":"Wrap time","status":"open",
             "voucher_id":"v1","updated":1772800000000,"last_message":"Thanks",
             "voucher":{"id":"v1","code":"VCH-0042-20260826"},
             "messages":[{"who_type":"artiste","text":"Wrap was 20:30","at":1772790000000},
                         {"who_type":"user","text":"Corrected","at":1772800000000,
                          "who":{"name":"Sam Grip","designation":"2nd AD"}}]}
            """.trimIndent(),
        ).toDomain()

        assertEquals("VCH-0042-20260826", query?.voucherCode)
        assertTrue(query?.messages?.first()?.fromArtiste == true)
        assertFalse(query?.messages?.last()?.fromArtiste == true)
        assertEquals("Sam Grip", query?.messages?.last()?.authorName)
        assertFalse(query?.resolved == true)
    }

    @Test
    fun `the voucher id falls back to the nested voucher`() {
        val query = decode(
            QueryDto.serializer(),
            """{"_id":"q1","voucher":{"id":"v9","code":"VCH-9"}}""",
        ).toDomain()

        assertEquals("v9", query?.voucherId)
    }

    @Test
    fun `a resolved thread says so`() {
        val query = decode(QueryDto.serializer(), """{"_id":"q1","status":"resolved"}""").toDomain()

        assertTrue(query?.resolved == true)
    }
}
