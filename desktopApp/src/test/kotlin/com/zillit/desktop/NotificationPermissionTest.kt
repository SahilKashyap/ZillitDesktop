package com.zillit.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The startup permission check: what the helper says, and when the person is asked. */
class NotificationPermissionTest {

    @Test
    fun `the helper's answers map to permissions`() {
        assertEquals(NotificationPermission.Granted, permissionFrom("authorized\n"))
        assertEquals(NotificationPermission.Granted, permissionFrom("provisional"))
        assertEquals(NotificationPermission.Granted, permissionFrom("granted"))
        assertEquals(NotificationPermission.Denied, permissionFrom("denied"))
        assertEquals(NotificationPermission.NotDetermined, permissionFrom("notDetermined"))
        assertEquals(NotificationPermission.Unknown, permissionFrom(""))
        // A request the system could not process is not a person's refusal.
        assertEquals(NotificationPermission.Unknown, permissionFrom("error"))
        assertEquals(NotificationPermission.Unknown, permissionFrom("zillit-notify: authorization failed"))
    }

    @Test
    fun `only a refusal opens the dialog`() {
        assertTrue(startupDecision(NotificationPermission.Denied) { error("not asked") })
        assertFalse(startupDecision(NotificationPermission.Granted) { error("not asked") })
        // A build that cannot know must not send anyone to a pane that says "allowed".
        assertFalse(startupDecision(NotificationPermission.Unknown) { error("not asked") })
    }

    /** The unsigned build's case: the request errors on every launch; the dialog must not follow it. */
    @Test
    fun `a request the system could not process opens nothing`() {
        assertFalse(startupDecision(NotificationPermission.NotDetermined) { permissionFrom("error") })
    }

    @Test
    fun `an undecided state gets the system prompt first`() {
        var asked = 0
        assertFalse(startupDecision(NotificationPermission.NotDetermined) { asked++; NotificationPermission.Granted })
        assertTrue(startupDecision(NotificationPermission.NotDetermined) { asked++; NotificationPermission.Denied })
        assertFalse(startupDecision(NotificationPermission.NotDetermined) { asked++; NotificationPermission.Unknown })
        assertEquals(3, asked)
    }

    @Test
    fun `the settings link names the app's row on Ventura and later`() {
        assertEquals(
            "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=com.zillit.desktop",
            macSettingsUrl("14.5"),
        )
        assertEquals(
            "x-apple.systempreferences:com.apple.preference.notifications?id=com.zillit.desktop",
            macSettingsUrl("12.7.1"),
        )
        assertTrue(macSettingsUrl("garbage").contains("preference.notifications"))
    }
}
