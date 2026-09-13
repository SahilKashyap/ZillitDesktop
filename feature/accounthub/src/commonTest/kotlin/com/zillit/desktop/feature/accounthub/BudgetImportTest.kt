package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.ParsedBudgetDto
import com.zillit.desktop.feature.accounthub.domain.BudgetImports
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.SetupUpload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Importing a budget file.
 *
 * The version number and the total are the two things worth pinning: a
 * duplicate version is refused by the server, and a total counted twice is a
 * production believing it has budgeted more than it has.
 */
class BudgetImportTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun version(v: String) = BudgetVersion(id = v, version = v)

    /**
     * Versions are unique per production, not per budget name.
     *
     * A brand-new Sound Budget in a production already at v3 gets v4, not v1 —
     * numbering per name would collide the moment two budgets reached the same
     * number, which is the server error this exists to prevent.
     */
    @Test
    fun `the next version counts every version the production has`() {
        assertEquals("v1", BudgetImports.suggestNextVersion(emptyList()))
        assertEquals("v4", BudgetImports.suggestNextVersion(listOf(version("v1"), version("v3"))))
        assertEquals("v4", BudgetImports.suggestNextVersion(listOf(version("3"))), "the v is optional")
    }

    /** Free text in the column simply does not take part. */
    @Test
    fun `an unparseable version is ignored, not guessed at`() {
        assertEquals("v1", BudgetImports.suggestNextVersion(listOf(version("Rev A"), version("Final"))))
        assertEquals("v3", BudgetImports.suggestNextVersion(listOf(version("v2"), version("Final"))))
    }

    /**
     * Whatever the parse made of it, the answer is one nobody holds.
     *
     * Exactly, not case-insensitively: the server stores plain strings, so V4
     * and v4 are genuinely different rows and a case-blind check would report
     * a collision that cannot happen.
     */
    @Test
    fun `the suggestion is always free`() {
        val taken = listOf(version("v1"), version("v2"), version("v3"))
        assertEquals("v4", BudgetImports.suggestNextVersion(taken))

        assertEquals(
            "v5",
            BudgetImports.suggestNextVersion(taken + version("v4")),
            "the probe walks past a number the parse already counted",
        )
        assertEquals("v2", BudgetImports.suggestNextVersion(listOf(version("V1"))), "V1 is not v1")
    }

    @Test
    fun `the latest version is the highest that parses`() {
        assertEquals("v3", BudgetImports.latestVersion(listOf(version("v1"), version("v3"), version("Rev A"))))
        assertNull(BudgetImports.latestVersion(listOf(version("Final"))))
        assertNull(BudgetImports.latestVersion(emptyList()))
    }

    @Test
    fun `a name is read out of the filename`() {
        assertEquals("Sound budget v2", BudgetImports.labelFrom("Sound_budget-v2.xlsx"))
        assertEquals("Imported Budget", BudgetImports.labelFrom(".pdf"))
    }

    /**
     * The nominals roll up into their headers.
     *
     * Adding them to the total would count the same money twice — the
     * production would believe it had budgeted double.
     */
    @Test
    fun `the total is headers plus uncoded, never the nominals`() {
        val parsed = json.decodeFromString(
            ParsedBudgetDto.serializer(),
            """{"headers":[{"code":"100","amount":600}],
               "nominals":[{"code":"101","amount":400,"parent_code":"100"},
                           {"code":"102","amount":200,"parent_code":"100"}],
               "uncodedItems":[{"name":"Sundries","amount":50}]}""",
        ).toDomain()

        assertEquals(650.0, parsed.total)
    }

    /** The server's own figure wins where it gives one. */
    @Test
    fun `a server total is taken as given`() {
        val parsed = json.decodeFromString(
            ParsedBudgetDto.serializer(),
            """{"grandTotal":1000,"headers":[{"code":"100","amount":600}]}""",
        ).toDomain()

        assertEquals(1000.0, parsed.total)
    }

    /** A file that parsed to nothing is a parse that failed quietly. */
    @Test
    fun `nothing to save is recognised as nothing`() {
        val empty = json.decodeFromString(ParsedBudgetDto.serializer(), """{"sections":[{"section_id":"s1"}]}""")
        assertTrue(empty.toDomain().isEmpty, "sections alone are not a budget")

        val uncodedOnly = json.decodeFromString(
            ParsedBudgetDto.serializer(),
            """{"uncodedItems":[{"name":"Sundries","amount":50}]}""",
        )
        assertTrue(!uncodedOnly.toDomain().isEmpty, "uncoded lines are still money")
    }

    /** Warnings come through in the server's own words, blanks dropped. */
    @Test
    fun `warnings are carried verbatim`() {
        val parsed = json.decodeFromString(
            ParsedBudgetDto.serializer(),
            """{"warnings":["Duplicate code 100","","Orphaned parent 900"]}""",
        ).toDomain()

        assertEquals(listOf("Duplicate code 100", "Orphaned parent 900"), parsed.warnings)
    }

    // -- what may be uploaded ------------------------------------------------

    /**
     * A budget takes spreadsheets; the documents take PDFs only.
     *
     * The document purposes ride the signing flow, which takes nothing else. A
     * budget is read once by the parser, so it takes whatever the parser reads.
     */
    @Test
    fun `each purpose accepts what it can actually use`() {
        assertNull(SetupUpload.BudgetImport.refuse("budget.xlsx", 1_000))
        assertNull(SetupUpload.BudgetImport.refuse("budget.CSV", 1_000), "the check is case-blind")
        assertNull(SetupUpload.BudgetImport.refuse("budget.pdf", 1_000))

        assertTrue(SetupUpload.Agreement.refuse("budget.xlsx", 1_000)!!.contains("PDF"))
        assertNull(SetupUpload.PurchaseOrderTerms.refuse("terms.docx", 1_000), "the terms document takes Word files")
        assertTrue(SetupUpload.PurchaseOrderTerms.refuse("terms.png", 1_000)!!.contains("DOCX"))
    }

    /** Each purpose has its own cap — 20 MB, or the web's 10 MB for the terms document — and says which. */
    @Test
    fun `the size cap is inclusive and the refusal names it`() {
        SetupUpload.entries.forEach { purpose ->
            assertNull(purpose.refuse("file.pdf", purpose.maxBytes), "${purpose.name} at the cap")
            assertEquals(purpose.tooLarge, purpose.refuse("file.pdf", purpose.maxBytes + 1))
            val megabytes = (purpose.maxBytes / (1024 * 1024)).toString()
            assertTrue(purpose.tooLarge.contains(megabytes), "${purpose.name} names its own limit")
        }
    }
}
