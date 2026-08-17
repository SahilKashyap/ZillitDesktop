package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.Companies
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.VendorAddress
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.SortCode
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.validationError
import com.zillit.desktop.feature.accounthub.domain.ProductionSchedule
import com.zillit.desktop.feature.accounthub.domain.ProjectBudget
import com.zillit.desktop.feature.accounthub.domain.SchedulePhase
import com.zillit.desktop.feature.accounthub.ui.BudgetForm
import com.zillit.desktop.feature.accounthub.ui.DateRangeText
import com.zillit.desktop.feature.accounthub.ui.ScheduleForm
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules Production Setup enforces before anything reaches the server. */
class SetupRulesTest {

    /**
     * A bank belongs to at most one company.
     *
     * Linking without subtracting leaves it owned twice, and the two owners
     * then disagree about which currency the production banks in.
     */
    @Test
    fun `linking a bank takes it from whoever had it`() {
        val companies = listOf(
            Company(id = "a", name = "A", bankIds = listOf("bank-1", "bank-2")),
            Company(id = "b", name = "B"),
        )

        val linked = Companies.linking(companies, companyId = "b", bankIds = listOf("bank-1"))

        assertEquals(listOf("bank-2"), linked.first { it.id == "a" }.bankIds)
        assertEquals(listOf("bank-1"), linked.first { it.id == "b" }.bankIds)
    }

    @Test
    fun `a company's currencies come from its banks, in link order`() {
        val banks = listOf(
            BankAccount(id = "b1", currencyCode = "GBP"),
            BankAccount(id = "b2", currencyCode = "EUR"),
            BankAccount(id = "b3", currencyCode = "GBP"),
        )
        val company = Company(id = "a", bankIds = listOf("b2", "b1", "b3"))

        // Distinct, and not collapsed to one: a co-production can straddle
        // currencies legitimately, so the client lists rather than judges.
        assertEquals(listOf("EUR", "GBP"), Companies.currencyCodes(company, banks))
    }

    /**
     * The holder is re-derived from the live companies.
     *
     * The stored snapshot goes stale the moment its company is deleted, and a
     * card that keeps naming a deleted entity is the bug this prevents.
     */
    @Test
    fun `a bank linked to a live company shows that company's current name`() {
        val bank = BankAccount(id = "b1", accountHolderName = "Old Name Ltd", entityId = "co-1")
        val companies = listOf(Company(id = "co-1", name = "Renamed Films Ltd"))

        assertEquals("Renamed Films Ltd", bank.holderName(companies, companiesLoading = false))
    }

    @Test
    fun `a bank whose company was deleted shows nothing rather than the stale name`() {
        val bank = BankAccount(id = "b1", accountHolderName = "Deleted Ltd", entityId = "gone")

        assertEquals("", bank.holderName(emptyList(), companiesLoading = false))
    }

    /** While companies load, the snapshot stands in — so the list does not blank out. */
    @Test
    fun `a bank keeps its snapshot while companies are still loading`() {
        val bank = BankAccount(id = "b1", accountHolderName = "Zillit Films", entityId = "co-1")

        assertEquals("Zillit Films", bank.holderName(emptyList(), companiesLoading = true))
    }

    @Test
    fun `a bank never linked to a company keeps its free-typed holder`() {
        val bank = BankAccount(id = "b1", accountHolderName = "Typed By Hand")

        assertEquals("Typed By Hand", bank.holderName(emptyList(), companiesLoading = false))
    }

    @Test
    fun `sort codes mask as they are typed and store digits only`() {
        assertEquals("", SortCode.formatted(""))
        assertEquals("20", SortCode.formatted("20"))
        assertEquals("20-4", SortCode.formatted("204"))
        assertEquals("20-48-91", SortCode.formatted("204891"))
        // A legacy row persisted with hyphens renders identically to a new one.
        assertEquals("20-48-91", SortCode.formatted("20-48-91"))
        assertEquals("204891", SortCode.digits("20-48-91"))
        // Capped at six: anything beyond was a paste error or non-UK data.
        assertEquals("204891", SortCode.digits("2048912345"))
    }

    /** Dropping the default currency clears the default rather than orphaning it. */
    @Test
    fun `removing the default currency clears the default`() {
        val settings = CurrencySettings(
            currencies = listOf(ProjectCurrency("GBP"), ProjectCurrency("USD")),
            defaultCode = "GBP",
        )

        val without = settings.without("GBP")

        assertEquals(listOf("USD"), without.currencies.map { it.code })
        assertNull(without.defaultCode)
    }

    @Test
    fun `removing a non-default currency leaves the default alone`() {
        val settings = CurrencySettings(
            currencies = listOf(ProjectCurrency("GBP"), ProjectCurrency("USD")),
            defaultCode = "GBP",
        )

        assertEquals("GBP", settings.without("USD").defaultCode)
    }

    /**
     * A currency with no rate must never go out with `exr: null`.
     *
     * Found against dev on 2026-08-12: the service answers `status: 1` and
     * stores **nothing** — `{"currencies":[],"default":null}` — so the save
     * reported success and the currency vanished. The catalogue this app picks
     * from carries no rate, so every first currency a production added would
     * have been lost.
     */
    @Test
    fun `no currency is sent without a rate`() {
        val settings = CurrencySettings(
            currencies = listOf(ProjectCurrency("GBP"), ProjectCurrency("USD")),
            defaultCode = "GBP",
        )

        val wire = settings.forWire()

        assertTrue(wire.currencies.all { it.rate != null })
    }

    /** The default is the base every other rate is quoted against. */
    @Test
    fun `the default currency's rate is pinned to one`() {
        val settings = CurrencySettings(
            currencies = listOf(ProjectCurrency("GBP", rate = 0.79), ProjectCurrency("USD")),
            defaultCode = "GBP",
        )

        val wire = settings.forWire()

        assertEquals(1.0, wire.currencies.first { it.code == "GBP" }.rate)
    }

    /** A rate somebody set on a non-default currency is left alone. */
    @Test
    fun `a known rate on a non-default currency survives`() {
        val settings = CurrencySettings(
            currencies = listOf(ProjectCurrency("GBP"), ProjectCurrency("USD", rate = 1.27)),
            defaultCode = "GBP",
        )

        assertEquals(1.27, settings.forWire().currencies.first { it.code == "USD" }.rate)
    }

    @Test
    fun `clearing every currency stays cleared`() {
        assertTrue(CurrencySettings().forWire().currencies.isEmpty())
    }

    /**
     * Custom tax identifiers are derived from what exists, not from a counter.
     *
     * A counter starting from zero in each session mints `custom_1` twice for
     * two accountants editing the same project.
     */
    @Test
    fun `the next custom tax identifier follows the highest already used`() {
        val existing = listOf(
            TaxType(identifier = "GB_standard"),
            TaxType(identifier = "custom_1"),
            TaxType(identifier = "custom_4"),
        )

        assertEquals("custom_5", TaxType.nextCustomIdentifier(existing))
    }

    @Test
    fun `the first custom tax identifier is custom_1`() {
        assertEquals("custom_1", TaxType.nextCustomIdentifier(emptyList()))
    }

    @Test
    fun `iso dates convert at UTC midnight and reject anything else`() {
        assertEquals(0L, IsoDate.toEpochMillis("1970-01-01"))
        assertEquals(1_785_542_400_000, IsoDate.toEpochMillis("2026-08-01"))
        // A leap day, which a 365.25-day approximation gets wrong.
        assertEquals(1_709_164_800_000, IsoDate.toEpochMillis("2024-02-29"))
        assertNull(IsoDate.toEpochMillis("not a date"))
        assertNull(IsoDate.toEpochMillis("2026-13-01"))
        assertNull(IsoDate.toEpochMillis(""))
        assertNull(IsoDate.toEpochMillis(null))
    }

    /**
     * The vendor form checks what the server checks.
     *
     * Measured on dev 2026-08-12: a name-only vendor is rejected field by field
     * — first the address shape, then its postcode, then its country — one
     * failed round trip each. Every rule here mirrors one the service enforces.
     */
    @Test
    fun `a vendor needs every field the service requires`() {
        assertEquals("Give the vendor a name.", NewVendor().validationError())
        assertEquals(
            "Name a contact person.",
            NewVendor(name = "Grip Co").validationError(),
        )
        assertEquals(
            "Give the vendor an email address.",
            NewVendor(name = "Grip Co", contactPerson = "Ada").validationError(),
        )
    }

    @Test
    fun `a complete vendor passes`() {
        val vendor = NewVendor(
            name = "Grip Co",
            contactPerson = "Ada Lovelace",
            email = "hire@grip.co.uk",
            address = VendorAddress(
                line1 = "12 Wardour Street",
                city = "London",
                postalCode = "W1D 6QF",
                country = "United Kingdom",
            ),
        )

        assertNull(vendor.validationError())
    }

    /** The address sub-fields the service refuses when empty are checked here. */
    @Test
    fun `a vendor without a postcode or country is refused`() {
        val base = NewVendor(
            name = "Grip Co",
            contactPerson = "Ada",
            email = "hire@grip.co.uk",
            address = VendorAddress(line1 = "12 Wardour Street", city = "London"),
        )

        assertEquals("Give the vendor a postcode.", base.validationError())
        assertEquals(
            "Give the vendor a country.",
            base.copy(address = base.address.copy(postalCode = "W1D 6QF", country = ""))
                .validationError(),
        )
    }

    /**
     * The email rule matches the server's, which is stricter than "has an @".
     *
     * `hire@ziltest.example` was refused as "must be a valid email" — a reserved
     * TLD. Accepting it here would only move the rejection later.
     */
    @Test
    fun `an implausible email is refused before it is sent`() {
        val base = NewVendor(
            name = "Grip Co",
            contactPerson = "Ada",
            address = VendorAddress(
                line1 = "12 Wardour Street",
                city = "London",
                postalCode = "W1D 6QF",
                country = "United Kingdom",
            ),
        )

        assertNotNull(base.copy(email = "nope").validationError())
        assertNotNull(base.copy(email = "no@domain").validationError())
        assertNull(base.copy(email = "hire@grip.co.uk").validationError())
    }

    /** Phone is optional; a number that *is* entered must be plausible. */
    @Test
    fun `a phone number is optional but checked when present`() {
        val base = NewVendor(
            name = "Grip Co",
            contactPerson = "Ada",
            email = "hire@grip.co.uk",
            address = VendorAddress(
                line1 = "12 Wardour Street",
                city = "London",
                postalCode = "W1D 6QF",
                country = "United Kingdom",
            ),
        )

        assertNull(base.validationError())
        assertNotNull(base.copy(phoneNumber = "123").validationError())
        assertNull(base.copy(phoneNumber = "7700900123").validationError())
    }

    /** Country is pre-filled because it is the field most often left blank. */
    @Test
    fun `a new vendor starts with a country and a dial code`() {
        assertEquals(NewVendor.DEFAULT_COUNTRY, NewVendor().address.country)
        assertEquals(NewVendor.DEFAULT_DIAL_CODE, NewVendor().phoneCountryCode)
    }

    /** No number typed means no phone object — not an empty pair. */
    @Test
    fun `an untouched phone resolves to nothing`() {
        assertNull(NewVendor().phone())
        assertEquals("7700900123", NewVendor(phoneNumber = "7700900123").phone()?.number)
    }

    // -- section lifecycle --------------------------------------------------

    @Test
    fun `a section is clean until it is edited, and clean again once committed`() {
        val section = SectionEdit(saved = listOf("a"))

        assertFalse(section.dirty)
        val edited = section.edit(listOf("a", "b"))
        assertTrue(edited.dirty)
        assertFalse(edited.committed(listOf("a", "b")).dirty)
    }

    @Test
    fun `reverting restores what the server last confirmed`() {
        val section = SectionEdit(saved = listOf("a")).edit(listOf("a", "b"))

        assertEquals(listOf("a"), section.reverted().edited)
    }

    /**
     * The server's echo becomes the new truth, not the payload that was sent.
     *
     * The server normalises — trimming, minting ids, renumbering — and
     * snapshotting from the payload leaves the section permanently dirty
     * against a value it can never reach.
     */
    @Test
    fun `committing takes the server's echo, so normalisation does not read as dirty`() {
        val section = SectionEdit(saved = listOf("a")).edit(listOf("a", " b "))

        val committed = section.committed(listOf("a", "b"))

        assertFalse(committed.dirty)
        assertEquals(listOf("a", "b"), committed.edited)
    }

    /**
     * A background reload must not discard what is being typed.
     *
     * It updates the baseline so the section stays dirty against fresh truth,
     * rather than replacing the edits with it.
     */
    @Test
    fun `a reload while dirty keeps the edits and moves the baseline`() {
        val section = SectionEdit(saved = listOf("a")).edit(listOf("a", "b"))

        val reloaded = section.loaded(listOf("a", "c"))

        assertEquals(listOf("a", "b"), reloaded.edited)
        assertEquals(listOf("a", "c"), reloaded.saved)
        assertTrue(reloaded.dirty)
    }

    @Test
    fun `a reload while clean adopts the new value outright`() {
        val reloaded = SectionEdit(saved = listOf("a")).loaded(listOf("a", "c"))

        assertEquals(listOf("a", "c"), reloaded.edited)
        assertFalse(reloaded.dirty)
    }

    // -- typed fields -------------------------------------------------------

    /**
     * Typing a budget must not be reformatted under the caret.
     *
     * Found live on 2026-08-12: the field was bound to `Double?`, so `2500000`
     * typed one character at a time came out as **25.0** — each keystroke
     * re-rendered the parsed number and the next character landed inside it.
     * The form now holds the text, so what is typed is what is kept.
     */
    @Test
    fun `a budget amount keeps exactly what was typed`() {
        var form = BudgetForm()
        "2500000".forEach { char -> form = form.copy(amountText = form.amountText + char) }

        assertEquals("2500000", form.amountText)
        assertEquals(2_500_000.0, form.toDomain().amount)
    }

    /** Whole amounts print whole — not "2500000.0", and never "2.5E7". */
    @Test
    fun `an amount round-trips through the form without gaining a decimal`() {
        val loaded = BudgetForm.from(ProjectBudget(amount = 2_500_000.0, currency = "GBP"))

        assertEquals("2500000", loaded.amountText)
    }

    @Test
    fun `a fractional amount keeps its decimals`() {
        assertEquals("1234.56", BudgetForm.from(ProjectBudget(amount = 1234.56)).amountText)
    }

    /** Text that is not a number saves as no amount rather than throwing. */
    @Test
    fun `a non-numeric amount resolves to no amount`() {
        assertNull(BudgetForm(amountText = "abc").toDomain().amount)
        assertNull(BudgetForm(amountText = "").toDomain().amount)
    }

    /**
     * A date must survive being typed one character at a time.
     *
     * Found live the same day: bound to the parsed epoch, `"2026-09-0"` read as
     * null and the field emptied itself on every keystroke, so a date could not
     * be entered at all.
     */
    @Test
    fun `a date survives being typed character by character`() {
        var dates = DateRangeText()
        "2026-09-01".forEach { char -> dates = dates.copy(from = dates.from + char) }

        assertEquals("2026-09-01", dates.from)
        assertEquals(IsoDate.toEpochMillis("2026-09-01"), dates.toPhase().startDate)
    }

    /** A half-typed date is simply not a date yet — not an error, not a wipe. */
    @Test
    fun `a partial date is kept as text and parses to nothing`() {
        val partial = DateRangeText(from = "2026-09-0")

        assertEquals("2026-09-0", partial.from)
        assertNull(partial.toPhase().startDate)
    }

    @Test
    fun `the schedule form round-trips every phase`() {
        val schedule = ProductionSchedule(
            startDate = IsoDate.toEpochMillis("2026-08-01"),
            endDate = IsoDate.toEpochMillis("2026-12-31"),
            prep = SchedulePhase(IsoDate.toEpochMillis("2026-08-01"), null),
            shoot = SchedulePhase(
                IsoDate.toEpochMillis("2026-09-01"),
                IsoDate.toEpochMillis("2026-11-30"),
            ),
        )

        val restored = ScheduleForm.from(schedule).toDomain()

        assertEquals(schedule.startDate, restored.startDate)
        assertEquals(schedule.shoot.startDate, restored.shoot.startDate)
        assertEquals(schedule.shoot.endDate, restored.shoot.endDate)
        assertNull(restored.prep.endDate)
        assertFalse(restored.wrap.isSet)
    }
}
