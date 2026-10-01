package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.ui.fileSize
import com.zillit.desktop.feature.costumesetsync.ui.screens.deadlineIso
import com.zillit.desktop.feature.costumesetsync.ui.screens.escapeHtml
import com.zillit.desktop.feature.costumesetsync.ui.screens.qrSvg
import com.zillit.desktop.feature.costumesetsync.ui.siteName
import com.zillit.desktop.feature.costumesetsync.ui.withScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaAndLabelsTest {

    @Test
    fun linkGetsHttpsWhenItHasNoScheme() {
        assertEquals("https://drive.google.com/x", withScheme(" drive.google.com/x "))
        assertEquals("http://a.b", withScheme("http://a.b"))
        assertEquals("", withScheme("  "))
    }

    @Test
    fun siteNameIsTheHostWithoutWww() {
        assertEquals("example.com", siteName("https://www.example.com/path?q=1"))
    }

    @Test
    fun fileSizesReadLikeTheWeb() {
        assertEquals("", fileSize(0))
        assertEquals("512 B", fileSize(512))
        assertEquals("1.5 KB", fileSize(1536))
        assertEquals("12 MB", fileSize(12L * 1024 * 1024))
    }

    @Test
    fun htmlIsEscaped() {
        assertEquals("&lt;b&gt; &amp; &quot;x&quot;", escapeHtml("<b> & \"x\""))
    }

    @Test
    fun noDateMeansNoDeadline() {
        assertNull(deadlineIso("", "10:00"))
        assertNull(deadlineIso("not-a-date", ""))
        assertTrue(deadlineIso("2026-10-01", "17:00")!!.endsWith("Z"))
    }

    @Test
    fun qrIsAnSvgOfSquares() {
        val svg = qrSvg("CST-000123")
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("<rect"))
    }
}
