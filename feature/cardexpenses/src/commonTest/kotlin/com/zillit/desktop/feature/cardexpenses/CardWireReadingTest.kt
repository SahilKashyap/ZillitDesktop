package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.data.ReceiptDto
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.cardLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun anyCard(
    id: String = "652f1a9c4b3d2e1f0a9b8c7d",
    lastFour: String? = "4821",
    issuer: String? = "Visa",
    limit: Double = 1_000.0,
    balance: Double? = 1_000.0,
) = ExpenseCard(
    id = id,
    holderId = "user-1",
    holderName = "Ada",
    departmentId = null,
    companyId = null,
    status = CardStatus.Active,
    type = CardType.Physical,
    lastFour = lastFour,
    issuer = issuer,
    providerId = null,
    currency = "GBP",
    limit = limit,
    monthlyLimit = null,
    balance = balance,
    receiptsCommit = null,
    bsControlCode = null,
    proposedLimit = null,
    justification = null,
    requestedBy = null,
    rejectedBy = null,
    rejectionReason = null,
    createdAt = null,
)

/**
 * How the card tool reads the wire, and how it names a card back.
 *
 * Every one of these parsers has an Unknown arm, so a mis-read never throws
 * — it just quietly labels a live card "Unknown" and drops it out of the
 * status filters. That silence is why they are pinned here.
 */
class CardWireReadingTest {

    @Test
    fun `card status is read case- and space-insensitively`() {
        assertEquals(CardStatus.InTransit, CardStatus.from("in_transit"))
        assertEquals(CardStatus.InTransit, CardStatus.from("  IN_TRANSIT "))
        assertEquals(CardStatus.Cancelled, CardStatus.from("Cancelled"))
    }

    @Test
    fun `an unrecognised or absent card status is Unknown, never Active`() {
        assertEquals(CardStatus.Unknown, CardStatus.from("frozen"))
        assertEquals(CardStatus.Unknown, CardStatus.from(null))
        assertEquals(CardStatus.Unknown, CardStatus.from(""))
        assertEquals(CardStatus.Unknown, CardStatus.from("   "))
    }

    /** Unknown's own wire is the empty string; matching on it would make every blank Unknown-by-name. */
    @Test
    fun `Unknown is reached by falling through, not by matching`() {
        assertEquals(CardStatus.Unknown, CardStatus.from(CardStatus.Unknown.wire))
        assertEquals(CardWorkflowStatus.Unknown, CardWorkflowStatus.from(""))
    }

    /**
     * Card type is the one parser with no Unknown: anything that is not
     * digital is a physical card. A new digital variant on the wire would
     * therefore be drawn as plastic, which is the safer of the two errors.
     */
    @Test
    fun `anything that is not digital is physical`() {
        assertEquals(CardType.Digital, CardType.from("digital"))
        assertEquals(CardType.Digital, CardType.from(" DIGITAL "))
        assertEquals(CardType.Physical, CardType.from("physical"))
        assertEquals(CardType.Physical, CardType.from("virtual"))
        assertEquals(CardType.Physical, CardType.from(null))
    }

    @Test
    fun `workflow statuses round-trip through their wire values`() {
        for (status in CardWorkflowStatus.entries.filter { it != CardWorkflowStatus.Unknown }) {
            assertEquals(status, CardWorkflowStatus.from(status.wire), "${status.wire} did not survive")
            assertEquals(status, CardWorkflowStatus.from(status.wire.uppercase()))
        }
    }

    @Test
    fun `card statuses round-trip through their wire values`() {
        for (status in CardStatus.entries.filter { it != CardStatus.Unknown }) {
            assertEquals(status, CardStatus.from(status.wire), "${status.wire} did not survive")
        }
    }

    @Test
    fun `only posted counts as posted`() {
        assertTrue(CardWorkflowStatus.Posted.isPosted)
        assertTrue(
            CardWorkflowStatus.entries.none { it != CardWorkflowStatus.Posted && it.isPosted },
            "ready-to-post and approved are not posted",
        )
    }

    @Test
    fun `a card is named by its last four`() {
        assertEquals("•••• 4821", cardLabel(anyCard()))
    }

    @Test
    fun `without a last four the issuer names it`() {
        assertEquals("Visa", cardLabel(anyCard(lastFour = null)))
        assertEquals("Visa", cardLabel(anyCard(lastFour = "  ")), "blank is as absent as null")
    }

    /**
     * With neither, the label says "Card" and nothing more.
     *
     * It used to print the first six characters of the row id. That looked
     * like a card number to anyone who did not know better — the register read
     * "Card bb0cb7" down the whole column on a live production — and a
     * truncated ObjectId tells the reader nothing they can act on.
     */
    @Test
    fun `with neither, the label never falls back to the id`() {
        assertEquals("Card", cardLabel(anyCard(lastFour = null, issuer = null)))
        assertEquals("Card", cardLabel(anyCard(lastFour = "", issuer = "")))
    }

    /**
     * An issuer column holding a provider id is not an issuer name.
     *
     * Seen live: `card_issuer` came back as `fd82c1a1-d819-458a-8ed7-…` and
     * the card face drew it under the card number.
     */
    @Test
    fun `a uuid in the issuer column is not drawn as a name`() {
        assertEquals("Card", cardLabel(anyCard(lastFour = null, issuer = "fd82c1a1-d819-458a-8ed7-9c1a2b3c4d5e")))
        assertEquals("Card", cardLabel(anyCard(lastFour = null, issuer = "652f1a9c8d7b6e5f4a3b2c1d")))
        // A real issuer still reads as one, hex letters and all.
        assertEquals("Barclaycard", cardLabel(anyCard(lastFour = null, issuer = "Barclaycard")))
        assertEquals("Amex", cardLabel(anyCard(lastFour = null, issuer = "Amex")))
    }

    /**
     * A confidence score is a fraction on the wire, and a percentage on screen.
     *
     * Typed as an int once. kotlinx stops at the first element it cannot read,
     * so a single receipt carrying `0.63` blanked the **whole** Receipt Inbox
     * behind "The server sent something unexpected" — seen on a live
     * production, 2026-09-12.
     */
    @Test
    fun `a fractional confidence score reads as a percentage`() {
        val receipt = json.decodeFromString(
            ReceiptDto.serializer(),
            """
            {"id":"r1","amount":"84.20","match_score":0.63,"duplicate_score":0.85,
             "personal_score":"0.4","status":"pending_code"}
            """.trimIndent(),
        ).toDomain()!!

        assertEquals(63, receipt.matchScore)
        assertEquals(85, receipt.duplicateScore)
        assertEquals(40, receipt.personalScore, "a quoted fraction reads the same as a bare one")
    }

    /** Zero is no score at all, matching the web's `> 0` guard on all three. */
    @Test
    fun `a zero or missing score is no score`() {
        val receipt = json.decodeFromString(
            ReceiptDto.serializer(),
            """{"id":"r1","amount":"1","match_score":0,"status":"pending_code"}""",
        ).toDomain()!!

        assertNull(receipt.matchScore)
        assertNull(receipt.duplicateScore, "absent is absent")
    }

    @Test
    fun `spend is the limit less the balance`() {
        assertEquals(250.0, anyCard(limit = 1_000.0, balance = 750.0).spent)
        assertEquals(0.25f, anyCard(limit = 1_000.0, balance = 750.0).consumedFraction)
    }

    /** A card the server has not costed yet reads as unspent, not as fully spent. */
    @Test
    fun `an uncosted card shows no spend`() {
        val card = anyCard(balance = null)

        assertEquals(0.0, card.spent)
        assertEquals(0f, card.consumedFraction)
    }

    @Test
    fun `an overpaid card does not show negative spend`() {
        assertEquals(0.0, anyCard(limit = 1_000.0, balance = 1_200.0).spent)
    }

    @Test
    fun `a limitless card never fills its meter`() {
        assertEquals(0f, anyCard(limit = 0.0, balance = 0.0).consumedFraction)
        assertEquals(0f, anyCard(limit = -1.0, balance = -50.0).consumedFraction)
    }

    @Test
    fun `an overspent card fills the meter but does not overflow it`() {
        assertEquals(1f, anyCard(limit = 1_000.0, balance = -500.0).consumedFraction)
    }
}
