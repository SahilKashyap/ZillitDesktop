package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.AgreementDocumentDto
import com.zillit.desktop.feature.accounthub.data.AllowancesRentalsDto
import com.zillit.desktop.feature.accounthub.data.InvoicesSetupDto
import com.zillit.desktop.feature.accounthub.data.PayrollSettingsDto
import com.zillit.desktop.feature.accounthub.data.PurchaseOrderSetupDto
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AgreementUploads
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.InvoiceAlert
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.PayBasis
import com.zillit.desktop.feature.accounthub.domain.RunAuthorisationTier
import com.zillit.desktop.feature.accounthub.domain.SetupUpload
import com.zillit.desktop.feature.accounthub.domain.PoDescriptionFormat
import com.zillit.desktop.feature.accounthub.domain.PoSplitType
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.ui.SpendSetup
import com.zillit.desktop.feature.accounthub.ui.VendorFilter
import com.zillit.desktop.feature.accounthub.ui.VendorsState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The console against the web it is ported from.
 *
 * Each of these pins a place the two had drifted apart, found reviewing the
 * desktop hub against `zillit_web/src/accountHub` on 2026-09-09.
 */
class AccountHubWebParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    // -- vendors ------------------------------------------------------------

    private fun vendor(id: String, verified: Boolean) = Vendor(id = id, name = id, verified = verified)

    /** The web's three tabs, filtering rows already in hand. */
    @Test
    fun `the register filters by verification`() {
        val state = VendorsState(rows = listOf(vendor("a", true), vendor("b", false), vendor("c", true)))

        assertEquals(3, state.copy(filter = VendorFilter.All).visibleRows.size)
        assertEquals(listOf("a", "c"), state.copy(filter = VendorFilter.Verified).visibleRows.map { it.id })
        assertEquals(listOf("b"), state.copy(filter = VendorFilter.Unverified).visibleRows.map { it.id })
    }

    @Test
    fun `each tab counts what it would show`() {
        val state = VendorsState(rows = listOf(vendor("a", true), vendor("b", false), vendor("c", false)))

        assertEquals(3, state.countFor(VendorFilter.All))
        assertEquals(1, state.countFor(VendorFilter.Verified))
        assertEquals(2, state.countFor(VendorFilter.Unverified))
    }

    // -- approvers ----------------------------------------------------------

    /**
     * Six modules, not four.
     *
     * Time Card and Deal Memo have no sidebar row — they are their own tools —
     * but they still have approval chains, and leaving them out made those
     * chains unreachable here.
     */
    @Test
    fun `every module with a chain can be configured`() {
        assertEquals(
            listOf("purchase_orders", "invoices", "card_expenses", "cash_expenses", "timecard", "deal_memo"),
            ApprovalModule.entries.map { it.wire },
        )
    }

    /** Invoices lives inside the PO module, so it borrows PO's view access. */
    @Test
    fun `invoices share the purchase order tool`() {
        assertEquals(ApprovalModule.PurchaseOrders.tool, ApprovalModule.Invoices.tool)
        assertEquals("timecard_tool", ApprovalModule.Timecard.tool)
        assertEquals("deal_memo_tool", ApprovalModule.DealMemo.tool)
    }

    // -- allowances and rentals ---------------------------------------------

    /** A half-migrated row carries `on`, `rate` and `nominal`; all are read. */
    @Test
    fun `legacy field names still load`() {
        val dto = json.decodeFromString(
            AllowancesRentalsDto.serializer(),
            """{"allowances":[{"name":"Per diem","on":false,"rate":45,"nominal":"7100"}],"rentals":[]}""",
        )

        val row = dto.toDomain().allowances.single()
        assertEquals("Per diem", row.name)
        assertEquals(false, row.enabled)
        assertEquals("45", row.amount, "a whole amount loses the Double's .0")
        assertEquals("7100", row.nominalCode)
    }

    /** A row with no id is still addressable while it is being edited. */
    @Test
    fun `an id-less row gets a stable local one`() {
        val dto = json.decodeFromString(
            AllowancesRentalsDto.serializer(),
            """{"allowances":[{"name":"A"},{"name":"B"}],"rentals":[{"name":"Camera"}]}""",
        )

        val value = dto.toDomain()
        assertEquals(listOf("allow-legacy-0", "allow-legacy-1"), value.allowances.map { it.id })
        assertEquals("rental-legacy-0", value.rentals.single().id)
        assertTrue(value.allowances.all { it.enabled }, "absent enable means on")
    }

    /** Only a rental has a cap; `capped` is the explicit string, not a boolean. */
    @Test
    fun `a cap is read from the rental's cap type`() {
        val dto = json.decodeFromString(
            AllowancesRentalsDto.serializer(),
            """{"rentals":[{"name":"Camera","cap_type":"capped","cap_amount":1500},
               {"name":"Grip","cap_type":"uncapped"}]}""",
        )

        val rentals = dto.toDomain().rentals
        assertTrue(rentals[0].capped)
        assertEquals("1500", rentals[0].capAmount)
        assertTrue(!rentals[1].capped)
    }

    // -- invoices setup -----------------------------------------------------

    /**
     * Alerts are a list of the enabled keys, not a map of booleans.
     *
     * An alert nobody turned on is simply absent, and an unknown key is
     * dropped rather than round-tripped invisibly — this client cannot show a
     * switch for something it has no words for.
     */
    @Test
    fun `alerts read as the enabled set`() {
        val setup = json.decodeFromString(
            InvoicesSetupDto.serializer(),
            """{"alerts":["invoice_overdue","made_up_alert","daily_ap_summary"]}""",
        ).toDomain()

        assertEquals(setOf(InvoiceAlert.Overdue, InvoiceAlert.DailySummary), setup.alerts)
    }

    /** Both spellings of the run chain load, and the levels come back in order. */
    @Test
    fun `run levels are renumbered from one, in order`() {
        val setup = json.decodeFromString(
            InvoicesSetupDto.serializer(),
            """{"run_authorization":[{"tier":5,"user":["u2"]},{"tier":2,"user":["u1"]}]}""",
        ).toDomain()

        assertEquals(listOf(1, 2), setup.runAuthorisation.map { it.tier })
        assertEquals(listOf("u1"), setup.runAuthorisation[0].userIds, "the lower tier comes first")

        val legacy = json.decodeFromString(
            InvoicesSetupDto.serializer(),
            """{"run_auth":[{"tier":1,"user":["u9"]}]}""",
        ).toDomain()
        assertEquals(listOf("u9"), legacy.runAuthorisation.single().userIds)
    }

    /** An absent posting limit stays blank: zero is a real limit, not "unset". */
    @Test
    fun `a missing posting limit is not zero`() {
        val setup = json.decodeFromString(
            InvoicesSetupDto.serializer(),
            """{"team_members":[{"user_id":"u1"},{"user_id":"u2","posting_limit":0},
               {"user_id":"u3","posting_limit":2500}]}""",
        ).toDomain()

        assertEquals("", setup.teamMembers[0].postingLimit)
        assertEquals("0", setup.teamMembers[1].postingLimit)
        assertEquals("2500", setup.teamMembers[2].postingLimit)
    }

    /** Accounts payable needs both halves before it can process anything. */
    @Test
    fun `configured needs a team and a chain`() {
        val team = listOf(InvoiceTeamMember(userId = "u1"))
        val chain = listOf(RunAuthorisationTier(userIds = listOf("u2")))

        assertTrue(!InvoicesSetup().isConfigured)
        assertTrue(!InvoicesSetup(teamMembers = team).isConfigured)
        assertTrue(!InvoicesSetup(runAuthorisation = chain).isConfigured)
        assertTrue(InvoicesSetup(teamMembers = team, runAuthorisation = chain).isConfigured)
    }

    // -- purchase order setup -----------------------------------------------

    /**
     * Auto-split is on unless the project explicitly turned it off.
     *
     * The web reads it the same way. Defaulting the other way would leave a
     * project that has never opened this screen posting rentals unsplit.
     */
    @Test
    fun `absent auto-split means on`() {
        assertTrue(json.decodeFromString(PurchaseOrderSetupDto.serializer(), "{}").toDomain().autoSplitRentals)
        assertTrue(
            !json.decodeFromString(
                PurchaseOrderSetupDto.serializer(),
                """{"auto_split_rentals":false}""",
            ).toDomain().autoSplitRentals,
        )
    }

    /** Unknown or absent enum values fall back rather than failing the load. */
    @Test
    fun `unknown formats fall back to the defaults`() {
        val odd = json.decodeFromString(
            PurchaseOrderSetupDto.serializer(),
            """{"description_format":"WHAT","default_split_type":"fortnightly"}""",
        ).toDomain()

        assertEquals(PoDescriptionFormat.DayMonthItem, odd.descriptionFormat)
        assertEquals(PoSplitType.Weekly, odd.splitType)
    }

    /** The three the web offers, with its labels; a stored CUSTOM still decodes but is not offered. */
    @Test
    fun `the offered description formats are the web's three`() {
        assertEquals(
            listOf("DDMON → ITEM", "DDMM → ITEM", "ITEM → DDMON"),
            PoDescriptionFormat.offered.map { it.label },
        )
        assertEquals(PoDescriptionFormat.Custom, PoDescriptionFormat.from("CUSTOM"))
        assertTrue(PoDescriptionFormat.Custom !in PoDescriptionFormat.offered)
    }

    /** The section chips count what the web's `count` fields count. */
    @Test
    fun `the section chips follow the web`() {
        val bare = PurchaseOrderSetup()
        assertEquals(3, bare.rentalCount, "auto-split on, plus the two always-on rules")
        assertEquals(2, bare.copy(autoSplitRentals = false).rentalCount)
        assertEquals(0, bare.issuanceCount)
        val issued = bare.copy(numberPrefix = "QW", termsDocument = AgreementDocument(name = "t.pdf"))
        assertEquals(2, issued.issuanceCount)
    }

    /** The terms document takes what the web's `validateTermsFile` takes, and refuses in its words. */
    @Test
    fun `the terms document accepts the web's types and size`() {
        val terms = SetupUpload.PurchaseOrderTerms
        assertNull(terms.refuse("terms.docx", 1_000))
        assertNull(terms.refuse("Terms.PDF", 1_000))
        assertEquals("Only PDF, DOC or DOCX files are accepted", terms.refuse("terms.png", 1_000))
        assertEquals("File must be 10MB or smaller", terms.refuse("terms.pdf", 11L * 1024 * 1024))
        assertTrue(!AgreementDocument(name = "t.pdf").openable, "the web refuses to open one without its store keys")
        assertTrue(AgreementDocument(name = "t.pdf", media = "m", bucket = "b", region = "r").openable)
    }

    /** The prefix is normalised on the way in as well as on the way out. */
    @Test
    fun `a prefix is upper case letters and digits, at most eight`() {
        assertEquals("QW01", PurchaseOrderSetup.normalisePrefix("qw-01"))
        assertEquals("ABCDEFGH", PurchaseOrderSetup.normalisePrefix("abcdefghij"))
        assertEquals("", PurchaseOrderSetup.normalisePrefix("--/--"))
        assertEquals(
            "QW01",
            json.decodeFromString(
                PurchaseOrderSetupDto.serializer(),
                """{"po_number_prefix":"qw-01"}""",
            ).toDomain().numberPrefix,
        )
    }

    /**
     * An empty terms object is not a document.
     *
     * The server sends `{}` where the web sends null, and a card for a file
     * that is not there reads as an upload that failed.
     */
    @Test
    fun `an empty terms attachment reads as none`() {
        val empty = json.decodeFromString(
            PurchaseOrderSetupDto.serializer(),
            """{"terms_attachment":{}}""",
        ).toDomain()
        assertEquals(null, empty.termsDocument)

        val real = json.decodeFromString(
            PurchaseOrderSetupDto.serializer(),
            """{"terms_attachment":{"name":"terms.pdf","media":"k/terms.pdf"}}""",
        ).toDomain()
        assertEquals("terms.pdf", real.termsDocument?.name)
    }

    // -- payroll settings ---------------------------------------------------

    /** The window is always seven days; picking either end moves the other. */
    @Test
    fun `a pay period is a whole week either way round`() {
        assertEquals(PayrollSettings.SUNDAY, PayrollSettings.endFor(PayrollSettings.MONDAY))
        assertEquals(PayrollSettings.MONDAY, PayrollSettings.startFor(PayrollSettings.SUNDAY))
        // Wednesday to Tuesday — the web's own example of a non-default cycle.
        assertEquals(2, PayrollSettings.endFor(3))
        assertEquals(3, PayrollSettings.startFor(2))
        (1..7).forEach { start ->
            assertEquals(start, PayrollSettings.startFor(PayrollSettings.endFor(start)), "day $start")
        }
    }

    /** A day outside 1..7, or absent, falls back to a Monday week. */
    @Test
    fun `a nonsense pay period becomes the default`() {
        assertEquals(1 to 7, PayrollSettings.sanitise(null, null))
        assertEquals(1 to 7, PayrollSettings.sanitise(0, 9))
        assertEquals(3 to 2, PayrollSettings.sanitise(3, 2), "a valid pair is kept")
        assertEquals(3 to 2, PayrollSettings.sanitise(3, null), "an absent end is derived")
    }

    /**
     * Configured means an accountant touched it.
     *
     * An approver, or a window that is not Monday to Sunday — the web's
     * criterion for the same tile.
     */
    @Test
    fun `the default week alone is not configuration`() {
        assertTrue(!PayrollSettings().isConfigured)
        assertTrue(PayrollSettings(approverIds = listOf("u1")).isConfigured)
        assertTrue(PayrollSettings(payPeriodStartDay = 3, payPeriodEndDay = 2).isConfigured)
    }

    /** A locked period arrives as a number or a quoted bigint; both lock it. */
    @Test
    fun `a locked pay period is read either way it is sent`() {
        val quoted = json.decodeFromString(
            PayrollSettingsDto.serializer(),
            """{"payroll_approvers":["u1"," u1 ","","u2"],"pay_period_locked_at":"1750000000000"}""",
        ).toDomain()

        assertTrue(quoted.payPeriodLocked)
        assertEquals(listOf("u1", "u2"), quoted.approverIds, "trimmed and de-duplicated")

        val bare = json.decodeFromString(
            PayrollSettingsDto.serializer(),
            """{"pay_period_locked_at":1750000000000}""",
        ).toDomain()
        assertTrue(bare.payPeriodLocked)

        val open = json.decodeFromString(PayrollSettingsDto.serializer(), """{}""").toDomain()
        assertTrue(!open.payPeriodLocked)
    }

    // -- production setup tiles ---------------------------------------------

    /**
     * The spend tiles deep-link into the tools that own those settings.
     *
     * The web edits each module's `/settings` document in a modal here. The
     * desktop already renders that document in the tool, so the tile points at
     * it — two editors over one record would disagree the moment either
     * changed. The route tail is what lands the tool on that page.
     */
    @Test
    fun `a spend tile names the settings page inside its tool`() {
        assertEquals("/film-tools/card-expenses/settings", SpendSetup.Cards.route)
        assertEquals("/film-tools/cash-expenses/settings", SpendSetup.PettyCash.route)
        assertTrue(
            SpendSetup.entries.all { it.route.endsWith("/settings") },
            "the tail is the page, and the prefix is what resolves the tool",
        )
    }

    // -- agreements and documents -------------------------------------------

    /** PDF only, because these ride the signing flow and it takes nothing else. */
    @Test
    fun `only PDFs under the cap are accepted`() {
        assertEquals(null, AgreementUploads.refusal("contract.pdf", 1_000))
        assertEquals(null, AgreementUploads.refusal("CONTRACT.PDF", 1_000), "the check is case-blind")
        assertTrue(AgreementUploads.refusal("notes.docx", 1_000)!!.contains("PDF"))
        assertTrue(AgreementUploads.refusal("big.pdf", AgreementUploads.MAX_BYTES + 1)!!.contains("20 MB"))
        assertEquals(null, AgreementUploads.refusal("edge.pdf", AgreementUploads.MAX_BYTES), "the cap is inclusive")
    }

    /** Pre-migration rows nest their metadata; both shapes have to render. */
    @Test
    fun `a nested legacy document still reads`() {
        val dto = json.decodeFromString(
            AgreementDocumentDto.serializer(),
            """{"id":"d1","document":{"name":"deal.pdf","media":"k/deal.pdf","bucket":"b","region":"r",
               "caption":"Standard terms","file_size":2048}}""",
        )

        val doc = dto.toDomain()
        assertEquals("deal.pdf", doc.name)
        assertEquals("k/deal.pdf", doc.media)
        assertEquals("Standard terms", doc.description)
        assertEquals("pdf", doc.contentSubtype, "derived from the filename when absent")
        assertEquals(2048L, doc.fileSize)
    }

    /** A flat row wins over the nested one when both carry a field. */
    @Test
    fun `the flat row is preferred`() {
        val dto = json.decodeFromString(
            AgreementDocumentDto.serializer(),
            """{"name":"new.pdf","title":"Rider","document":{"name":"old.pdf","title":"Old"}}""",
        )

        assertEquals("new.pdf", dto.toDomain().name)
        assertEquals("Rider", dto.toDomain().title)
    }

    /**
     * A retired basis stays readable.
     *
     * Per Hour, Per Night and Per Event were withdrawn in 2026-07. A saved row
     * still carrying one must show it: blanking a required field reads as data
     * loss, and this field is somebody's agreed pay cadence.
     */
    @Test
    fun `a retired basis is shown, marked for re-picking`() {
        assertEquals("Daily", PayBasis.labelFor("day"))
        assertTrue(PayBasis.labelFor("hour").startsWith("Per Hour"))
        assertTrue(PayBasis.labelFor("hour").contains("retired"))
        assertEquals(null, PayBasis.from("hour"), "it is not offered as a live option")
    }
}
