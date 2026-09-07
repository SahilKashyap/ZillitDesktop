package com.zillit.desktop

import com.zillit.desktop.feature.accounthub.ui.ACCOUNT_HUB_PATH
import com.zillit.desktop.feature.settings.ui.ACCOUNT_HUB_ROUTE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Admin Settings' "Production Setup" row and the page it opens.
 *
 * The row lives in `feature:settings` and the page in `feature:accounthub`,
 * and neither module depends on the other — settings carries the route as a
 * string of its own. This is the only place that sees both, so it is where the
 * two are held together: renaming the console's path without this would leave
 * the row opening a window that does not exist, and nothing would say so until
 * someone clicked it.
 */
class AdminRouteContractTest {

    @Test
    fun `the settings row points at the account hub's real route`() {
        assertEquals(ACCOUNT_HUB_PATH, ACCOUNT_HUB_ROUTE)
    }
}
