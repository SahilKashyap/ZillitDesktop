package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.ui.HELP_ENTRIES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Zillit Help cards, against the phones' Zillit Guide.
 *
 * Pinned because the list is a plain literal that reads as harmless to edit:
 * the titles and their order are the phones' (`ZillitGuideViewController`),
 * and the links are the ones iOS opens rather than the shorter web slugs this
 * screen used to carry — a difference nothing else in the build would notice.
 */
class HelpListingTest {

    @Test
    fun `the cards are the phones' cards, in their order`() {
        assertEquals(
            listOf("FAQs", "Privacy Policy", "Terms of Use", "Contact Us"),
            HELP_ENTRIES.map { it.title },
        )
    }

    @Test
    fun `reviews is not offered`() {
        // Commented out in iOS's own list, so a desktop card for it would send
        // people somewhere the phones deliberately do not.
        assertTrue(HELP_ENTRIES.none { it.title == "Reviews" })
        assertTrue(HELP_ENTRIES.none { it.url?.endsWith("/d") == true })
    }

    @Test
    fun `every link is one the phones open`() {
        val links = HELP_ENTRIES.mapNotNull { it.url }
        assertEquals(
            listOf(
                "https://corporate.zillit.com/frequently-asked-questions-for-zillit-application-and-web-platform",
                "https://corporate.zillit.com/privacy-policy-for-zillit-application-and-web-platform",
                "https://corporate.zillit.com/terms-conditions-for-zillit-application-and-web-platform",
            ),
            links,
        )
        // The launcher refuses anything that is not http(s), so a card with a
        // mistyped scheme would be a button that silently does nothing.
        assertTrue(links.all { it.startsWith("https://") })
    }

    @Test
    fun `contact us is the only card with no link, and the only callable one`() {
        val contact = HELP_ENTRIES.single { it.url == null }
        assertEquals("Contact Us", contact.title)
        assertTrue(contact.callable)
        assertTrue(HELP_ENTRIES.filter { it.callable }.size == 1)
    }
}
