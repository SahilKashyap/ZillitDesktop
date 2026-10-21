package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.ui.HELP_ENTRIES
import com.zillit.desktop.feature.settings.ui.ZILLIT_TOUR_PATH
import com.zillit.desktop.feature.settings.ui.ZILLIT_TOUR_URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Zillit Help cards, against the phones' Zillit Guide.
 *
 * The tour row is pinned here too, for the opposite reason: it is the only
 * card that does not open the browser, and "simplifying" it into one would
 * take the tour out of the app, which is where both phones put it.
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
            listOf("Zillit Tour", "FAQs", "Privacy Policy", "Terms of Use", "Contact us"),
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
                // Android's `Constants.CORPORATE_TOUR_URL` and iOS's
                // `ZillitTour.url`, which the web's sixth card also opens.
                "https://zillit-tour.zillit.com/",
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
    fun `the tour is the only card the app opens itself, and it keeps its link`() {
        // Both phones host the tour in their own web view rather than hand it
        // to a browser (`HelpActivity.kt:272`, `ZillitTour.open(from:)`), so
        // this row opens a window of the app. The URL stays on the row all the
        // same: it is the fallback where there is no embedded browser, and
        // dropping it would leave that build with a card that does nothing.
        val tour = HELP_ENTRIES.single { it.opensInApp }
        assertEquals("Zillit Tour", tour.title)
        assertEquals(ZILLIT_TOUR_URL, tour.url)
        assertTrue(ZILLIT_TOUR_PATH.startsWith("/settings/help"))
    }

    @Test
    fun `contact us is the only card with no link, and the only callable one`() {
        val contact = HELP_ENTRIES.single { it.url == null }
        assertEquals("Contact us", contact.title)
        assertTrue(contact.callable)
        assertTrue(HELP_ENTRIES.filter { it.callable }.size == 1)
    }
}
