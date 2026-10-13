package com.zillit.desktop.core.cabinet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CabinetViewersTest {

    private class Fake(override val identifier: String) : CabinetViewer {
        @Composable
        override fun Content(entry: CabinetEntry?, modifier: Modifier) = Unit
    }

    @Test
    fun `a viewer is found by the row it serves`() {
        val home = Fake("home")
        val viewers = CabinetViewers(listOf(home, Fake("info")))

        assertSame(home, viewers["home"])
        assertNull(viewers["recce"])
    }

    @Test
    fun `no viewers leaves every row select-and-download only`() {
        assertNull(CabinetViewers.None["home"])
    }

    @Test
    fun `a row opens directly unless its viewer says it expands`() {
        assertEquals(false, Fake("info").expands)
    }
}
