package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `rawExportError` is what stops a real 403 permission refusal (Deal Memo's
 * `libs_no_posting_rights`, found live in ZT Temp Account) and a genuine 401
 * from both surfacing as the exact same "Something went wrong."
 */
class RawExportErrorTest {

    @Test
    fun `a 401 becomes the app's own session-expired copy, not the raw key`() {
        val error = rawExportError(401, "libs_moduledata_not_accepted", fallbackMessage = null)
        assertIs<ZillitError.Unauthorized>(error)
        assertEquals("Your session has expired. Please sign in again.", error.userMessage)
    }

    @Test
    fun `a 403 becomes the app's own access-denied copy, not the raw key`() {
        val error = rawExportError(httpStatus = 403, serverMessage = "libs_no_posting_rights", fallbackMessage = null)
        assertIs<ZillitError.Forbidden>(error)
        assertEquals("You do not have access to this.", error.userMessage)
    }

    @Test
    fun `any other status with a server message is shown, translatable`() {
        val error = rawExportError(httpStatus = 422, serverMessage = "libs_period_locked", fallbackMessage = null)
        assertIs<ZillitError.Http>(error)
        assertEquals("libs_period_locked", error.userMessage)
    }

    @Test
    fun `a real error status with no message still names the status`() {
        val error = rawExportError(httpStatus = 500, serverMessage = null, fallbackMessage = null)
        assertIs<ZillitError.Http>(error)
        assertEquals("Something went wrong (500).", error.userMessage)
    }

    @Test
    fun `a soft decline on a success response never shows that status as an error code`() {
        val error = rawExportError(httpStatus = null, serverMessage = "libs_nothing_to_export", fallbackMessage = null)
        assertIs<ZillitError.Http>(error)
        assertEquals("libs_nothing_to_export", error.userMessage)
    }

    @Test
    fun `no status and no message is the one genuinely unknown case`() {
        val error = rawExportError(null, null, fallbackMessage = "The service returned no file")
        assertIs<ZillitError.Unknown>(error)
        // Unknown's own userMessage is fixed by design; the fallback is for logs only.
        assertEquals("Something went wrong.", error.userMessage)
        assertEquals("The service returned no file", error.technical)
    }
}
