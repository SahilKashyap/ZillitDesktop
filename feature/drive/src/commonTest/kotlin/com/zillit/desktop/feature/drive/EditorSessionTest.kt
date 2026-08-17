package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.EditorSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Assembling the Collabora address.
 *
 * The config route returns pieces, not a URL, and the first pass here looked
 * for a `editor_url` field that does not exist — the response is camelCase and
 * carries `collaboraUrl` / `wopiSrc` / `accessToken` instead. The symptom was
 * an editor that silently never opened.
 */
class EditorSessionTest {

    private val session = EditorSession(
        baseUrl = "https://collabora-dev.zillit.com/browser/dist/cool.html",
        wopiSrc = "https://driveapi-dev.zillit.com/api/v2/drive/wopi/files/64f1a2b3",
        accessToken = "eyJhbGciOi.J9+abc/d==",
        fileName = "Budget.xlsx",
    )

    @Test
    fun `the wopi source is percent-encoded into the query`() {
        val address = session.address()

        // Unencoded, the `:` and `/` of a full URL are read as part of *this*
        // query string and Collabora fetches nothing.
        assertTrue(
            address.contains(
                "WOPISrc=https%3A%2F%2Fdriveapi-dev.zillit.com%2Fapi%2Fv2%2Fdrive%2Fwopi%2Ffiles%2F64f1a2b3",
            ),
            address,
        )
    }

    @Test
    fun `the token's base64 padding survives the round trip`() {
        val address = session.address()

        // `+` decodes to a space and `=` is a delimiter — a token passed raw
        // comes back as "invalid token" from a token that was perfectly good.
        assertTrue(address.contains("access_token=eyJhbGciOi.J9%2Babc%2Fd%3D%3D"), address)
    }

    @Test
    fun `the iframe flag is set`() {
        // Without it Collabora renders its own chrome and its close button
        // tries to navigate the host away.
        assertTrue(session.address().endsWith("&NotWOPIButIframe=true"))
    }

    @Test
    fun `a base that already has a query gets an ampersand, not a second question mark`() {
        val withQuery = session.copy(baseUrl = "https://collabora.example/cool.html?lang=en")

        assertTrue(withQuery.address().contains("?lang=en&WOPISrc="), withQuery.address())
    }

    @Test
    fun `a config missing any piece is not usable`() {
        assertTrue(session.isUsable)
        assertFalse(session.copy(baseUrl = "").isUsable)
        assertFalse(session.copy(wopiSrc = "").isUsable)
        // A blank token would load the editor onto an error page, which reads
        // as the document being broken rather than the session being absent.
        assertFalse(session.copy(accessToken = "").isUsable)
    }

    @Test
    fun `unreserved characters are left alone`() {
        val plain = session.copy(wopiSrc = "abcXYZ019-._", accessToken = "t")

        assertTrue(plain.address().contains("WOPISrc=abcXYZ019-._"), plain.address())
    }

    @Test
    fun `non-ascii is encoded as utf-8 bytes`() {
        val accented = session.copy(wopiSrc = "café", accessToken = "t")

        assertTrue(accented.address().contains("WOPISrc=caf%C3%A9"), accented.address())
    }

    @Test
    fun `the file name is carried for the window title`() {
        assertEquals("Budget.xlsx", session.fileName)
    }
}
