package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.data.body
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoApprovalTiers
import com.zillit.desktop.feature.purchaseorder.domain.PoAutoApproval
import com.zillit.desktop.feature.purchaseorder.domain.PoTier
import com.zillit.desktop.feature.purchaseorder.domain.PoTierConfig
import com.zillit.desktop.feature.purchaseorder.domain.PoTierRule
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web's `getAutoApprovals`: raising an order signs every consecutive tier the creator sits on. */
class PoAutoApprovalTest {

    private fun tier(order: Int, vararg users: String) =
        PoTier(order, listOf(PoTierRule(PoApprovalTiers.RULE_DEFAULT, null, users.toList())))

    private val chain = PoApprovalTiers(
        configs = listOf(
            PoTierConfig(PoApprovalTiers.SCOPE_ALL, null, listOf(tier(1, "me", "x"), tier(2, "me"), tier(3, "boss"))),
        ),
    )

    @Test
    fun `signs tiers one and two and stops at the one the creator is not on`() {
        val signed = chain.autoApprovals("dept", "me", approvedAt = 7L)
        assertEquals(listOf(1, 2), signed.map { it.tierNumber })
        assertTrue(signed.all { it.userId == "me" && it.approvedAt == 7L })
    }

    @Test
    fun `a creator who is not on tier one signs nothing, even if they sit on a later tier`() {
        val chain = PoApprovalTiers(
            configs = listOf(PoTierConfig(PoApprovalTiers.SCOPE_ALL, null, listOf(tier(1, "boss"), tier(2, "me")))),
        )
        assertTrue(chain.autoApprovals("dept", "me", 1L).isEmpty())
    }

    @Test
    fun `no chain configured means nothing to sign`() {
        assertTrue(PoApprovalTiers().autoApprovals("dept", "me", 1L).isEmpty())
    }

    private fun order(approvals: List<PoAutoApproval>?) = NewPurchaseOrder(
        vendorId = "v1", vendorName = "V", description = "d", departmentId = "dept", companyId = null,
        currency = "GBP", nominalCode = null, episode = null, notes = null, effectiveDate = null,
        lines = emptyList(), approvals = approvals,
    )

    @Test
    fun `the body carries approvals as the server names them, and an empty list is still sent`() {
        val body = order(listOf(PoAutoApproval("me", 1, 9L))).body()
        val first = body["approvals"]!!.jsonArray.single().jsonObject
        assertEquals("me", first["user_id"]!!.jsonPrimitive.content)
        assertEquals(1, first["tier_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(9L, first["approved_at"]!!.jsonPrimitive.content.toLong())

        assertTrue(order(emptyList()).body()["approvals"]!!.jsonArray.isEmpty(), "an edit has to clear stale approvals")
        assertFalse(order(null).body().containsKey("approvals"), "accounts' own raise sends none")
    }
}
