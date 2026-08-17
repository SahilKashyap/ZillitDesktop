package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.decodeAccounts
import com.zillit.desktop.feature.accounthub.data.decodeApprovalConfigs
import com.zillit.desktop.feature.accounthub.data.decodeBankAccounts
import com.zillit.desktop.feature.accounthub.data.decodeCompanies
import com.zillit.desktop.feature.accounthub.data.decodeCurrencies
import com.zillit.desktop.feature.accounthub.data.decodeDayTypes
import com.zillit.desktop.feature.accounthub.data.decodeSchedule
import com.zillit.desktop.feature.accounthub.data.decodeTaxTypes
import com.zillit.desktop.feature.accounthub.data.decodeVendors
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the shapes the account hub actually sends.
 *
 * ## Why these are built from response bodies, not from the DTOs
 *
 * A test that constructs a DTO and asserts it round-trips passes just as
 * happily when the field is read under the wrong key — it agrees with itself.
 * Every bug this class exists to catch has the same signature: the payload
 * decodes without error and the column renders blank. Eight of them shipped
 * that way in Drive and Document Distribution before the same tests went in
 * there.
 *
 * The bodies below are the shapes documented by the web's own API clients,
 * transcribed field for field.
 */
class AccountHubWireShapeTest {

    /**
     * The slice envelope.
     *
     * Reads are wrapped under `value`; writes are not. Reading `data.companies`
     * instead of `data.value` fails **silently** — an empty list and no error —
     * which is exactly how the allowances slice was lost once already.
     */
    @Test
    fun `a settings slice is read from under value, not from the slice name`() {
        val companies = decodeCompanies(
            """
            {"value":[
              {"id":"co-1","name":"Zillit Films Ltd","country":"United Kingdom",
               "country_code":"GB","bank_ids":["bank-1","bank-2"],"tax_credits":["uk-avec"]}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, companies.size)
        assertEquals("Zillit Films Ltd", companies.first().name)
        assertEquals(listOf("bank-1", "bank-2"), companies.first().bankIds)
        assertEquals("GB", companies.first().countryCode)
    }

    @Test
    fun `a slice that has never been saved decodes as empty rather than failing`() {
        assertTrue(decodeCompanies("""{"value":null}""").isEmpty())
        assertTrue(decodeCompanies("""{}""").isEmpty())
    }

    /**
     * A bank's currency is a nested object, not a code.
     *
     * Flattened on read because a company's currency is derived from the banks
     * it links, and a second lookup per company to resolve it would make that
     * derivation cost a round trip.
     */
    @Test
    fun `a bank account carries its currency as an object`() {
        val banks = decodeBankAccounts(
            """
            [{"id":"bank-1","name":"Barclays","account_holder_name":"Zillit Films Ltd",
              "entity_id":"co-1","entity_type":"production","account_number":"20481234",
              "sort_code":"204891","swift_code":"BARCGB22","iban_number":"GB29NWBK60161331926819",
              "nominal_code":"1200","currency":{"code":"GBP","name":"Pound","symbol":"£"}}]
            """.trimIndent(),
        )

        val bank = banks.single()
        assertEquals("GBP", bank.currencyCode)
        assertEquals("£", bank.currencySymbol)
        assertEquals("co-1", bank.entityId)
        assertEquals("204891", bank.sortCode)
    }

    /**
     * The currency slice has been persisted in three shapes.
     *
     * Current, and two older ones still sitting on live projects. Failing to
     * read the old ones would show an accountant an empty currency list for a
     * production that plainly has currencies.
     */
    @Test
    fun `the current currency shape decodes with rates and a default`() {
        val settings = decodeCurrencies(
            """
            {"value":{"currencies":[
              {"code":"GBP","name":"Pound Sterling","symbol":"£","exr":1},
              {"code":"USD","name":"US Dollar","symbol":"$","exr":1.27}
            ],"default":"GBP"}}
            """.trimIndent(),
        )

        assertEquals(listOf("GBP", "USD"), settings.currencies.map { it.code })
        assertEquals("GBP", settings.defaultCode)
        assertEquals(1.27, settings.currencies.last().rate)
    }

    @Test
    fun `the legacy codes shape still decodes`() {
        val settings = decodeCurrencies("""{"value":{"codes":["GBP","EUR"],"default":"EUR"}}""")

        assertEquals(listOf("GBP", "EUR"), settings.currencies.map { it.code })
        assertEquals("EUR", settings.defaultCode)
    }

    @Test
    fun `the oldest flat-array shape still decodes, with no default`() {
        val settings = decodeCurrencies("""{"value":["GBP","USD"]}""")

        assertEquals(listOf("GBP", "USD"), settings.currencies.map { it.code })
        // No default was ever recorded in this shape. Inventing one would
        // pre-fill transactions with a currency nobody chose.
        assertNull(settings.defaultCode)
    }

    /**
     * Schedule dates are epoch millis now and were `YYYY-MM-DD` before.
     *
     * Both live on the same project, since a schedule saved before the change
     * is not rewritten until somebody edits it.
     */
    @Test
    fun `schedule dates decode from epoch millis and from calendar dates alike`() {
        val schedule = decodeSchedule(
            """
            {"value":{"start_date":1754000000000,"end_date":"2026-12-31",
              "prep":{"start_date":"2026-08-01","end_date":null},
              "shoot":{"start_date":1756000000000,"end_date":1758000000000},
              "wrap":{}}}
            """.trimIndent(),
        )

        assertEquals(1_754_000_000_000, schedule.startDate)
        // Both at UTC midnight: 2026-12-31 and 2026-08-01.
        assertEquals(1_798_675_200_000, schedule.endDate)
        assertEquals(1_785_542_400_000, schedule.prep.startDate)
        assertNull(schedule.prep.endDate)
        assertFalse(schedule.wrap.isSet)
    }

    /** A numeric string is an epoch, not a year. */
    @Test
    fun `a stringified epoch is read as an epoch`() {
        val schedule = decodeSchedule("""{"value":{"start_date":"1754000000000"}}""")

        assertEquals(1_754_000_000_000, schedule.startDate)
    }

    /**
     * The chart's hierarchy is a breadcrumb, not a parent pointer.
     *
     * Each row carries the id of every ancestor plus a self-reference at its own
     * level, so the immediate parent depends on the row's own line type.
     */
    @Test
    fun `a chart row resolves its parent from the breadcrumb column for its level`() {
        val rows = decodeAccounts(
            """
            [
             {"id":"h1","code":"1000","name":"Production","line_type":"header",
              "cost_type":"expense","head_id":"h1","is_active":true},
             {"id":"s1","code":"1100","name":"Crew","line_type":"section",
              "cost_type":"expense","head_id":"h1","sec_id":"s1"},
             {"id":"c1","code":"1110","name":"Camera","line_type":"category",
              "cost_type":"expense","head_id":"h1","sec_id":"s1","cat_id":"c1"}
            ]
            """.trimIndent(),
        )

        val (header, section, category) = rows
        assertNull(header.parentId)
        assertEquals("h1", section.parentId)
        assertEquals("s1", category.parentId)
        assertEquals(CoaLineType.Category, category.lineType)
    }

    /**
     * Both chart flags are opt-out.
     *
     * A row written before `posting_box` existed carries neither field and must
     * read as usable. Defaulting the other way makes every legacy code
     * unselectable on every line-item picker in the platform at once.
     */
    @Test
    fun `a chart row with neither flag reads as active and postable`() {
        val row = decodeAccounts("""[{"id":"a","code":"1","name":"Legacy","line_type":"category"}]""")
            .single()

        assertTrue(row.isActive)
        assertTrue(row.isPosting)
    }

    @Test
    fun `an explicit false on either flag is honoured`() {
        val row = decodeAccounts(
            """[{"id":"a","code":"1","name":"Closed","line_type":"category",
                 "is_active":false,"posting_box":false}]""",
        ).single()

        assertFalse(row.isActive)
        assertFalse(row.isPosting)
    }

    /**
     * Verification comes from `status`, and there is no boolean on the wire.
     *
     * Reading a `verified` field straight off the row is how the web's register
     * once showed every vendor as unverified at the same time.
     */
    @Test
    fun `a vendor is verified by its status string`() {
        val rows = decodeVendors(
            """
            [{"id":"v1","name":"Panavision","email":"hire@panavision.example",
              "contact_person":"Ada","status":"VERIFIED"},
             {"id":"v2","name":"Local Caterers","status":"PENDING"}]
            """.trimIndent(),
        )

        assertTrue(rows.first().verified)
        assertFalse(rows.last().verified)
    }

    /** Some routes send phone and address as JSON encoded into a string. */
    @Test
    fun `a vendor's phone and address survive being objects or strings`() {
        val rows = decodeVendors(
            """
            [{"_id":"v3","name":"Grip Co","phone":{"country_code":"+44","number":"7700900123"},
              "address":"12 Wardour St, London","contactPerson":"Grace",
              "currency":{"code":"GBP"}}]
            """.trimIndent(),
        )

        val vendor = rows.single()
        // Structured, not flattened: the service types both fields and refuses a
        // string on write, so a read that loses the shape makes the row
        // un-editable.
        assertEquals("+44", vendor.phone?.countryCode)
        assertEquals("7700900123", vendor.phone?.number)
        // Free text keeps its whole self on line 1 rather than being split on a
        // comma — guessing where a city starts is how addresses get mangled.
        assertEquals("12 Wardour St, London", vendor.address.line1)
        assertEquals("Grace", vendor.contactPerson)
        assertEquals("GBP", vendor.currencyCode)
        // `_id` rather than `id` — both are in use across these routes.
        assertEquals("v3", vendor.id)
    }

    /**
     * Levels come back in `order`, which need not match array position.
     *
     * A config saved after a level was removed can arrive with the two
     * disagreeing, and a chain that runs in array order then routes wrongly.
     */
    @Test
    fun `an approval chain is ordered by its level number, not by array position`() {
        val configs = decodeApprovalConfigs(
            """
            [{"id":"cfg-1","module":"purchase_orders","scope":"all","tiers":[
               {"order":2,"rules":[{"type":"user","user_ids":["u2"]}]},
               {"order":1,"rules":[{"type":"user","user_ids":["u1","u3"]}]}
             ]}]
            """.trimIndent(),
        )

        val tiers = configs.single().tiers
        assertEquals(listOf(1, 2), tiers.map { it.order })
        assertEquals(2, tiers.first().approverCount)
    }

    /**
     * A meal break of null and one of zero are different facts.
     *
     * Null is "unspecified" and zero is an explicit "no formal break"; the
     * penalty engine acts on the second. Defaulting null to zero invents a
     * finding against a production.
     */
    @Test
    fun `a day type keeps an absent meal break absent`() {
        val rows = decodeDayTypes(
            """
            {"value":[
              {"day_type":"shoot","label":"Shoot day","work_min":600,"meal_break_min":60},
              {"day_type":"travel","label":"Travel","work_min":480},
              {"day_type":"continuous","label":"Continuous","work_min":540,"meal_break_min":0}
            ]}
            """.trimIndent(),
        )

        assertEquals(60, rows[0].mealBreakMinutes)
        assertNull(rows[1].mealBreakMinutes)
        assertEquals(0, rows[2].mealBreakMinutes)
    }

    /**
     * Reclaimable is opt-in, unlike the chart's flags.
     *
     * Absence means false here: defaulting the other way would let input tax be
     * claimed that is not reclaimable, which is a filing error rather than a
     * cosmetic one.
     */
    @Test
    fun `a tax rate is reclaimable only when it says so`() {
        val rows = decodeTaxTypes(
            """
            {"value":[
              {"type":"vat","identifier":"GB_standard","label":"Standard 20%","value":"20",
               "is_recoverable":true,"nominal":"2200"},
              {"type":"vat","identifier":"GB_zero","label":"Zero rated","value":"0"},
              {"type":"custom","identifier":"custom_1","label":"Location levy","value":"5"}
            ]}
            """.trimIndent(),
        )

        assertTrue(rows[0].isRecoverable)
        assertFalse(rows[1].isRecoverable)
        assertEquals("20", rows[0].value)
        // The country key is parsed out for display; the identifier itself is
        // never shown.
        assertEquals("GB", rows[0].countryCode)
        assertNotNull(rows[2].identifier)
        assertTrue(rows[2].isCustom)
    }

    /**
     * A saved rate comes back as a **number**, not the string it went out as.
     *
     * Observed on dev 2026-08-12: sending `"value":"20"` echoes `"value":20`.
     * The field is declared `String?`, so a strict reader would fail the whole
     * row — and the row is a tax rate, which every posting surface offers.
     */
    @Test
    fun `a tax rate decodes whether its value is quoted or not`() {
        val rows = decodeTaxTypes(
            """
            {"value":[
              {"type":"custom","identifier":"custom_1","label":"Standard VAT","value":20,
               "nominal":"","is_recoverable":true}
            ]}
            """.trimIndent(),
        )

        assertEquals("20", rows.single().value)
        assertTrue(rows.single().isRecoverable)
    }

    /**
     * A vendor's address is an object in, an object out.
     *
     * The service refuses a string outright — `"address" must be of type
     * object` (dev, 2026-08-12) — so a flattened read would have made the value
     * unwritable even where it decoded.
     */
    @Test
    fun `a vendor address decodes from an object`() {
        val vendor = decodeVendors(
            """
            [{"id":"v1","name":"Grip Co","vat_number":"GB123456789",
              "address":{"line1":"12 Wardour St","city":"London",
                         "postal_code":"W1D 6QF","country":"United Kingdom"},
              "phone":{"country_code":"+44","number":"7700900123"}}]
            """.trimIndent(),
        ).single()

        assertEquals("12 Wardour St", vendor.address.line1)
        assertEquals("W1D 6QF", vendor.address.postalCode)
        assertEquals("+44", vendor.phone?.countryCode)
        assertEquals("7700900123", vendor.phone?.number)
        // `vat_number`, not `tax_number` — the obvious name is the wrong one.
        assertEquals("GB123456789", vendor.vatNumber)
    }

    /** Some routes send the same object JSON-encoded into a string. */
    @Test
    fun `a vendor address survives arriving as an encoded string`() {
        val vendor = decodeVendors(
            """[{"id":"v2","name":"Grip Co","address":"{\"line1\":\"12 Wardour St\",\"city\":\"London\"}"}]""",
        ).single()

        assertEquals("12 Wardour St", vendor.address.line1)
        assertEquals("London", vendor.address.city)
    }

    /** Free text on an older row becomes the first line rather than being dropped. */
    @Test
    fun `a vendor address that is plain text is kept`() {
        val vendor = decodeVendors("""[{"id":"v3","name":"Grip Co","address":"Somewhere in Soho"}]""")
            .single()

        assertEquals("Somewhere in Soho", vendor.address.line1)
        assertEquals("Somewhere in Soho", vendor.address.oneLine)
    }

    /**
     * An absent phone is null, not an empty pair.
     *
     * `{ country_code, number: "" }` is truthy, so every downstream guard
     * renders a bare dial code — the web documents that exact trap.
     */
    @Test
    fun `a vendor with no phone has no phone`() {
        val absent = decodeVendors("""[{"id":"v4","name":"Grip Co"}]""").single()
        val blank = decodeVendors(
            """[{"id":"v5","name":"X","phone":{"country_code":"+44","number":""}}]""",
        ).single()

        assertNull(absent.phone)
        assertNull(blank.phone)
    }
}
