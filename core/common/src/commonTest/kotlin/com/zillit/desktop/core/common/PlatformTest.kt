package com.zillit.desktop.core.common

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlatformTest {

    @Test
    fun `platform resolves to a known desktop os`() {
        val platform = currentPlatform()
        assertNotEquals(OperatingSystem.Unknown, platform.os, "unrecognised os.name: ${platform.name}")
        assertTrue(platform.os.isDesktop)
    }

    @Test
    fun `platform name is populated`() {
        assertTrue(currentPlatform().name.isNotBlank())
    }
}
