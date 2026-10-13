package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.feature.dealmemo.domain.ApprovalLevelPrompt
import com.zillit.desktop.feature.dealmemo.domain.ApprovalTier
import com.zillit.desktop.feature.dealmemo.domain.ApprovalTierConfig
import com.zillit.desktop.feature.dealmemo.domain.ApproversBanner
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDIT_TAIL = "Deals from these departments cannot be approved until approval levels are configured."
private const val ASK_TAIL = "Ask the accounts team to configure approval levels."

private fun tiers(vararg levels: List<String>) =
    levels.mapIndexed { index, users -> ApprovalTier(order = index + 1, rules = listOf(users)) }

private fun global(vararg levels: List<String>) = ApprovalTierConfig("all", tiers = tiers(*levels))

private fun department(id: String, vararg levels: List<String>) =
    ApprovalTierConfig("department", departmentId = id, tiers = tiers(*levels))

private fun deal(departmentId: String?): DealDoc {
    val department = departmentId?.let { ",\"department_id\":\"$it\"" }.orEmpty()
    return rawDeal("{\"_id\":\"d-${departmentId ?: "none"}\"$department}")
}

private fun rawDeal(json: String) = DealDoc(Json.parseToJsonElement(json).jsonObject)

/** The deal page's "Set Approval Level" prompt, `shouldPromptApprovalLevel` case for case. */
class ApprovalLevelPromptTest {

    private fun prompt(
        configs: List<ApprovalTierConfig> = emptyList(),
        departmentId: String? = "dept_a",
        status: String? = "awaiting_approval",
        embedded: Boolean = false,
        canSetApprovers: Boolean = true,
        loaded: Boolean = true,
    ) = ApprovalLevelPrompt.shouldShow(
        status = status,
        embedded = embedded,
        canSetApprovers = canSetApprovers,
        metadata = DealMemoMetadata(approvalTierConfigs = configs, loaded = loaded),
        departmentId = departmentId,
    )

    @Test
    fun `prompts when no chain resolves for the deal`() {
        assertTrue(prompt())
    }

    @Test
    fun `does not prompt when the department or the all default has a level-1 approver`() {
        assertFalse(prompt(listOf(department("dept_a", listOf("u1")))))
        assertFalse(prompt(listOf(global(listOf("u1")))))
        assertFalse(prompt(listOf(global(listOf("u1"))), departmentId = null))
        assertFalse(prompt(listOf(global(listOf("u1"))), departmentId = ""))
    }

    @Test
    fun `prompts when a chain exists but its levels hold nobody`() {
        assertTrue(prompt(listOf(global(emptyList()))))
        // Another department's approvers don't cover this deal.
        assertTrue(prompt(listOf(department("dept_b", listOf("u1")))))
    }

    @Test
    fun `a chosen department chain that is empty does not fall back to the all default`() {
        assertTrue(prompt(listOf(department("dept_a", emptyList()), global(listOf("u1")))))
    }

    @Test
    fun `a level with nobody is skipped, so a later level still counts as level 1`() {
        assertFalse(prompt(listOf(global(emptyList(), listOf("u2")))))
    }

    @Test
    fun `a department-scope config with no department never answers a deal with none`() {
        val orphan = ApprovalTierConfig("department", departmentId = null, tiers = tiers(listOf("u1")))
        assertTrue(prompt(listOf(orphan), departmentId = null))
    }

    @Test
    fun `never prompts before the metadata has answered`() {
        assertFalse(prompt(loaded = false))
    }

    @Test
    fun `posting users only, on the detail page only, and only while awaiting approval`() {
        assertFalse(prompt(canSetApprovers = false))
        assertFalse(prompt(embedded = true))
        for (status in listOf("draft", "issued", "rejected", "active", "deactivated", null)) {
            assertFalse(prompt(status = status), "status $status")
        }
    }
}

/** All Deals' banner, `approversBanner.test.js` case for case. */
class ApproversBannerTest {

    private val names = mapOf("dA" to "A", "dB" to "B", "dC" to "C")

    private fun missing(configs: List<ApprovalTierConfig>, vararg deals: DealDoc) =
        ApproversBanner.missingDepartments(configs, deals.toList(), names, "Deal Memo")

    @Test
    fun `global with an approver covers every department`() {
        val result = missing(
            listOf(global(listOf("u1")), department("dA", emptyList())),
            deal("dA"),
            deal("dB"),
            deal(null),
        )
        assertEquals(emptyList(), result)
        assertNull(ApproversBanner.message(result, canEdit = true))
    }

    @Test
    fun `no global and a department with deals and no config is named`() {
        val result = missing(emptyList(), deal("dA"), deal("dA"))
        assertEquals(listOf("A"), result)
        assertEquals("No approvers are set for A. $EDIT_TAIL", ApproversBanner.message(result, canEdit = true))
    }

    @Test
    fun `three departments missing read as a count, sorted`() {
        val result = missing(emptyList(), deal("dC"), deal("dA"), deal("dB"))
        assertEquals(listOf("A", "B", "C"), result)
        assertEquals("No approvers are set for 3 departments. $EDIT_TAIL", ApproversBanner.message(result, true))
    }

    @Test
    fun `a department with its own approvers is covered`() {
        val result = missing(listOf(department("dA", emptyList(), listOf("u2"))), deal("dA"), deal("dB"))
        assertEquals(listOf("B"), result)
    }

    @Test
    fun `a department override whose every level is empty, with no global, is missing`() {
        val result = missing(listOf(department("dA", emptyList(), emptyList()), global()), deal("dA"))
        assertEquals(listOf("A"), result)
    }

    @Test
    fun `a draft whose department sits only in crew_details resolves to it`() {
        val draft = rawDeal("""{"_id":"draft-1","crew_details":{"department_id":" dB "}}""")
        assertEquals("dB", ApproversBanner.departmentIdOf(draft))
        val nullText = rawDeal("""{"department_id":"null","crew_details":{"department_id":""}}""")
        assertNull(ApproversBanner.departmentIdOf(nullText))
        assertEquals(emptyList(), missing(listOf(department("dB", listOf("u1"))), draft))
        assertEquals(listOf("B"), missing(emptyList(), draft))
    }

    @Test
    fun `a deal with no department and no label, nothing else missing, is named for the tool`() {
        val result = missing(listOf(department("dA", listOf("u1"))), deal("dA"), rawDeal("""{"_id":"x"}"""))
        assertEquals(listOf("Deal Memo"), result)
    }

    @Test
    fun `a deal with no department but a label is named by it, humanised`() {
        val labelled = rawDeal("""{"_id":"x","department_name":"camera_department"}""")
        assertEquals(listOf("Camera Department"), missing(emptyList(), labelled))
    }

    @Test
    fun `a department the master does not know falls back to the deal's own label, then its id`() {
        val labelled = rawDeal("""{"_id":"x","department_id":"dZ","department_name":"sound_department"}""")
        assertEquals(listOf("Sound Department"), missing(emptyList(), labelled))
        assertEquals(listOf("dZ"), missing(emptyList(), rawDeal("""{"_id":"y","department_id":"dZ"}""")))
    }

    @Test
    fun `a viewer who cannot edit is told to ask the accounts team`() {
        assertEquals("No approvers are set for A. $ASK_TAIL", ApproversBanner.message(listOf("A"), canEdit = false))
        assertEquals(
            "No approvers are set for 2 departments. $ASK_TAIL",
            ApproversBanner.message(listOf("A", "B"), canEdit = false),
        )
    }

    @Test
    fun `the banner shows to posting users only, and says nothing until the configs are read`() {
        val deals = listOf(deal("dA"))
        fun say(canPost: Boolean, configs: List<ApprovalTierConfig>?, failed: Boolean = false, flag: Boolean? = null) =
            ApproversBanner.messageFor(canPost, configs, failed, deals, names, flag, "Deal Memo")

        assertNull(say(canPost = false, configs = emptyList()))
        assertNull(say(canPost = true, configs = null))
        assertEquals("No approvers are set for A. $EDIT_TAIL", say(canPost = true, configs = emptyList()))
        assertNull(say(canPost = true, configs = listOf(global(listOf("u1")))))
    }

    @Test
    fun `when the configs cannot be read it falls back to the metadata flag and an awaiting deal`() {
        val waiting = rawDeal("""{"_id":"w","status":"awaiting_approval"}""")
        val other = rawDeal("""{"_id":"o","status":"active"}""")
        fun say(flag: Boolean?, vararg deals: DealDoc) =
            ApproversBanner.messageFor(true, null, true, deals.toList(), names, flag, "Deal Memo")

        assertEquals(
            "1 deal memo is awaiting approval, but no approvers are set for Deal Memo. $EDIT_TAIL",
            say(false, waiting, other),
        )
        assertEquals(
            "2 deal memos are awaiting approval, but no approvers are set for Deal Memo. $EDIT_TAIL",
            say(false, waiting, waiting),
        )
        // `approvers_configured === false` only: absent or true is silent, as is nobody waiting.
        assertNull(say(null, waiting))
        assertNull(say(true, waiting))
        assertNull(say(false, other))
    }
}
