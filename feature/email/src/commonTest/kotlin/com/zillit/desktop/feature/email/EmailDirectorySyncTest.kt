package com.zillit.desktop.feature.email

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.email.data.EMAIL_CONTACTS_SYNC_EVENTS
import com.zillit.desktop.feature.email.data.EMAIL_GROUPS_SYNC_EVENTS
import com.zillit.desktop.feature.email.data.EMAIL_SIGNATURE_SYNC_EVENTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mailbox's two side lists.
 *
 * Saved addresses and sign-offs each live on their own page behind their own
 * tool provider rather than in `EmailViewModel` — which is why the mailbox had
 * a realtime source and these pages had none until 2026-09-09.
 *
 * Two families the audit flagged stay out on purpose, and this pins that so a
 * later sweep does not "fix" them back in: the desktop address book holds no
 * groups, and this mailbox shows no read-by receipts.
 */
class EmailDirectorySyncTest {

    private val contacts = EMAIL_CONTACTS_SYNC_EVENTS.map(SocketEventName::value)
    private val signatures = EMAIL_SIGNATURE_SYNC_EVENTS.map(SocketEventName::value)

    @Test
    fun `the address book listens for its three`() {
        assertEquals(
            listOf("email:contact:saved", "email:contact:updated", "email:contact:deleted"),
            contacts,
        )
    }

    @Test
    fun `the signature manager listens for its three`() {
        // `created`, not `saved` — the two families do not share a verb, and
        // guessing the symmetry is how a subscription goes silent.
        assertEquals(
            listOf("email:signature:created", "email:signature:updated", "email:signature:deleted"),
            signatures,
        )
    }

    @Test
    fun `the two lists never overlap`() {
        assertTrue(contacts.intersect(signatures.toSet()).isEmpty())
    }

    /**
     * The group page has its own list, so it has its own three names.
     *
     * These were excluded until 2026-09-09 on the grounds that the address
     * book holds no groups — true of the address book, false of the mailbox,
     * which has had a distribution-group page all along.
     */
    @Test
    fun `distribution groups follow their own three names`() {
        assertEquals(
            listOf("email:group:saved", "email:group:updated", "email:group:deleted"),
            EMAIL_GROUPS_SYNC_EVENTS.map { it.value },
        )
        assertTrue(
            (contacts + signatures).none { it.startsWith("email:group:") },
            "the contact and signature pages have no groups to reload",
        )
    }

    @Test
    fun `read-by stays unsubscribed while there are no receipts to show`() {
        // Android routes this through its signature flow — a quirk of its own
        // plumbing, not a contract worth copying.
        assertFalse("email:readby:update" in (contacts + signatures))
    }
}
