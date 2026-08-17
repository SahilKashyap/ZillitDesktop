package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.ui.MentionPickerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picker's keyboard model: what the arrows light, what Enter completes,
 * and how long an Escape holds.
 *
 * The rule worth pinning is the reset — when the token changes the rows
 * reorder, and a selection index that survived would silently light a
 * different person than the one the user chose.
 */
class MentionPickerStateTest {

    private val crew = listOf("Aisha Khan", "Aisha", "Sunil k Gautam", "Vidya Pixel")

    @Test
    fun `an at sign opens with everyone and the first row lit`() {
        val picker = MentionPickerState()
        picker.sync("@", crew)

        assertTrue(picker.isOpen)
        assertEquals(4, picker.matches.size)
        assertEquals(0, picker.selected)
        assertEquals("Aisha Khan", picker.selectedName())
    }

    @Test
    fun `arrows move and wrap in both directions`() {
        val picker = MentionPickerState()
        picker.sync("@ai", crew)

        picker.moveDown()
        assertEquals("Aisha", picker.selectedName())
        picker.moveDown()
        assertEquals("Aisha Khan", picker.selectedName(), "wraps past the end")
        picker.moveUp()
        assertEquals("Aisha", picker.selectedName(), "wraps past the top")
    }

    @Test
    fun `a changed token snaps the selection back to the top`() {
        val picker = MentionPickerState()
        picker.sync("@", crew)
        picker.moveDown()
        picker.moveDown()

        picker.sync("@ai", crew)

        assertEquals(0, picker.selected)
        assertEquals("Aisha Khan", picker.selectedName())
    }

    @Test
    fun `escape hides the list until the token changes`() {
        val picker = MentionPickerState()
        picker.sync("@ai", crew)
        picker.dismiss()

        assertFalse(picker.isOpen)
        picker.sync("@ai", crew)
        assertFalse(picker.isOpen, "the same token stays hidden")

        picker.sync("@ais", crew)
        assertTrue(picker.isOpen, "a new token reopens")
    }

    @Test
    fun `no token means no picker and no answer for enter`() {
        val picker = MentionPickerState()
        picker.sync("plain text", crew)

        assertFalse(picker.isOpen)
        assertNull(picker.selectedName())
        picker.moveDown()
        assertEquals(0, picker.selected, "arrows are inert while closed")
    }

    @Test
    fun `recent names float first for a bare at sign`() {
        val picker = MentionPickerState()
        picker.sync("@", crew, recentFirst = listOf("Vidya Pixel"))

        assertEquals("Vidya Pixel", picker.selectedName(), "the lit row is the last mentioned")
    }

    @Test
    fun `a list that shrinks under the selection clamps it`() {
        val picker = MentionPickerState()
        picker.sync("@ai", crew)
        picker.moveDown()

        picker.sync("@ai", listOf("Aisha Khan"))

        assertEquals(0, picker.selected)
        assertEquals("Aisha Khan", picker.selectedName())
    }
}
