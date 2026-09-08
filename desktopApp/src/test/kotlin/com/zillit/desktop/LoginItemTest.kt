package com.zillit.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginItemTest {
    @Test
    fun `the agent starts the launcher hidden at sign-in`() {
        val plist = LoginItem.plist("/Applications/Zillit-Desktop.app/Contents/MacOS/Zillit-Desktop")
        assertTrue("<string>/Applications/Zillit-Desktop.app/Contents/MacOS/Zillit-Desktop</string>" in plist)
        assertTrue("<string>${BackgroundLaunch.FLAG}</string>" in plist)
        assertTrue("<key>RunAtLoad</key>\n    <true/>" in plist, "RunAtLoad is the whole point")
        assertTrue("<key>KeepAlive</key>\n    <false/>" in plist, "Quit must stay quit")
        assertTrue("<string>${LoginItem.LABEL}</string>" in plist)
        assertTrue(plist.startsWith("<?xml"), "launchd reads nothing before the declaration")
    }

    @Test
    fun `a launcher path is escaped for xml`() {
        val plist = LoginItem.plist("/Users/a&b/Zillit <dev>")
        assertTrue("<string>/Users/a&amp;b/Zillit &lt;dev&gt;</string>" in plist)
    }

    @Test
    fun `the windows run value quotes the launcher against spaces`() {
        assertEquals(
            "\"C:\\Program Files\\Zillit\\Zillit-Desktop.exe\" --background",
            LoginItem.windowsCommand("C:\\Program Files\\Zillit\\Zillit-Desktop.exe"),
        )
    }

    @Test
    fun `only the flag asks for a background start`() {
        assertTrue(BackgroundLaunch.requestedBy(arrayOf("--BACKGROUND")))
        assertFalse(BackgroundLaunch.requestedBy(arrayOf("--drive-widget")))
        assertFalse(BackgroundLaunch.requestedBy(emptyArray()))
    }
}
