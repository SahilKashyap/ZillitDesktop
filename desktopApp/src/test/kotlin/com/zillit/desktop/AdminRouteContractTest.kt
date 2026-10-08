package com.zillit.desktop

import com.zillit.desktop.feature.accounthub.ui.PRODUCTION_SETUP_BACK_PATH
import com.zillit.desktop.feature.accounthub.ui.PRODUCTION_SETUP_COMPANIES_PATH
import com.zillit.desktop.feature.settings.ui.ADMIN_SETTINGS_PATH
import com.zillit.desktop.feature.settings.ui.PRODUCTION_SETUP_ROUTE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Admin Settings' "Production Setup" row and the page it opens.
 *
 * The row lives in `feature:settings` and the page in `feature:accounthub`,
 * and neither module depends on the other — settings carries the route as a
 * string of its own. This is the only place that sees both, so it is where the
 * two are held together: renaming the page's path without this would leave
 * the row opening a page that does not exist, and nothing would say so until
 * someone clicked it.
 */
class AdminRouteContractTest {

    @Test
    fun `the settings row points at the companies page's real route`() {
        assertEquals(PRODUCTION_SETUP_COMPANIES_PATH, PRODUCTION_SETUP_ROUTE)
    }

    /** The page's back arrow lands on the admin tab; it carries that path as a string of its own. */
    @Test
    fun `the companies page goes back to admin settings`() {
        assertEquals(ADMIN_SETTINGS_PATH, PRODUCTION_SETUP_BACK_PATH)
    }
}
