package com.zillit.desktop

import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.common.currentPlatform
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What this machine tells the server it is. It used to send the JVM's version
 * inside the OS version, "Mac OS X" as the device's name, and whatever agent
 * the HTTP library chose — which the backend read as Android (2026-09-24).
 */
class DesktopDeviceTest {

    @Test
    fun `the OS release is the OS's own, without the JVM`() {
        assertFalse("JVM" in DesktopDevice.osVersion, DesktopDevice.osVersion)
        if (currentPlatform().os == OperatingSystem.MacOs) {
            assertTrue(DesktopDevice.osVersion.startsWith("macOS "), DesktopDevice.osVersion)
            assertTrue(DesktopDevice.model.isNotBlank(), "hw.model came back blank")
        }
    }

    @Test
    fun `the device has a real name and the agent names the desktop app`() {
        assertTrue(DesktopDevice.name.isNotBlank())
        assertFalse(DesktopDevice.name == "Mac OS X")
        assertTrue(DesktopDevice.userAgent.startsWith("Zillit-Desktop/"), DesktopDevice.userAgent)
        assertTrue(DesktopDevice.osVersion in DesktopDevice.userAgent, DesktopDevice.userAgent)
        assertFalse("android" in DesktopDevice.userAgent.lowercase())
    }
}
