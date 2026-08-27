package com.zillit.desktop.feature.draft

import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.ScriptElement
import com.zillit.desktop.feature.draft.domain.SmartType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The autocomplete a screenwriter leans on.
 *
 * Typing a character's name for the fortieth time is where a script editor
 * either helps or gets in the way: the wrong suggestion is worse than none,
 * because it is accepted by reflex.
 */
class SmartTypeTest {

    private var next = 0

    private fun element(type: ElementType, text: String) =
        ScriptElement(id = "e${next++}", type = type, text = text)

    private fun script() = listOf(
        element(ElementType.SceneHeading, "INT. KITCHEN - DAY"),
        element(ElementType.Character, "SAM"),
        element(ElementType.Dialogue, "Morning."),
        element(ElementType.SceneHeading, "EXT. KERB - NIGHT"),
        element(ElementType.Character, "SAM (V.O.)"),
        element(ElementType.Character, "RAVI"),
    )

    /** A cue's parenthetical is not part of the name. */
    @Test
    fun `a cue is offered without its parenthetical`() {
        assertEquals("SAM", SmartType.cueName("SAM (V.O.)"))
        assertEquals("SAM", SmartType.cueName("  sam  "))
    }

    /** The same character twice is one suggestion, not two. */
    @Test
    fun `characters are listed once`() {
        val names = SmartType.characters(script())

        assertEquals(listOf("RAVI", "SAM"), names.sorted())
    }

    /** Most recently used first: the person still in the scene is likeliest. */
    @Test
    fun `the most recent character leads`() {
        assertEquals("RAVI", SmartType.characters(script()).first())
    }

    /** Typing a prefix narrows to it. */
    @Test
    fun `a prefix narrows the characters offered`() {
        val offered = SmartType.suggestions(ElementType.Character, "S", script(), selfId = "none")

        assertEquals(listOf("SAM"), offered)
    }

    /**
     * What is already typed in full is not offered back.
     *
     * Suggesting the exact text sitting in the element is noise, and accepting
     * it by reflex would do nothing while feeling like it did something.
     */
    @Test
    fun `an exact match is not suggested`() {
        val offered = SmartType.suggestions(ElementType.Character, "SAM", script(), selfId = "none")

        assertTrue("SAM" !in offered, "was $offered")
    }

    /**
     * An element never suggests itself back to the person typing it.
     *
     * Only itself, though: a name another element also carries is still a
     * real suggestion — which is why this uses a script where the name
     * appears once. In the fuller script "SAM" survives self-exclusion
     * because "SAM (V.O.)" contributes it too, and that is right.
     */
    @Test
    fun `an element does not suggest itself`() {
        val ravi = element(ElementType.Character, "RAVI")
        val elements = listOf(element(ElementType.SceneHeading, "INT. HALL - DAY"), ravi)

        val offered = SmartType.suggestions(ElementType.Character, "RA", elements, selfId = ravi.id)

        assertTrue(offered.isEmpty(), "its own text is not a suggestion: was $offered")
    }

    /** But the same name from another element still counts. */
    @Test
    fun `another element's copy of the name still suggests`() {
        val elements = script()
        val sam = elements.first { it.text == "SAM" }

        val offered = SmartType.suggestions(ElementType.Character, "SA", elements, selfId = sam.id)

        assertEquals(listOf("SAM"), offered, "SAM (V.O.) still names SAM")
    }

    /** Locations are the middle of a heading — no INT./EXT., no time of day. */
    @Test
    fun `locations drop the slug and the time`() {
        val places = SmartType.locations(script())

        assertTrue("KITCHEN" in places, "was $places")
        assertTrue("KERB" in places, "was $places")
    }

    /**
     * A half-typed heading offers known locations under the slug typed.
     *
     * "INT. K" should reach INT. KITCHEN *and* INT. KERB — the location was
     * learned from an EXT. heading, but a place can be shot from either side
     * of its door.
     */
    @Test
    fun `a heading prefix offers known locations under it`() {
        val offered = SmartType.suggestions(ElementType.SceneHeading, "INT. K", script(), selfId = "none")

        assertTrue(offered.any { it == "INT. KITCHEN" }, "was $offered")
        assertTrue(offered.any { it == "INT. KERB" }, "a place can be shot from outside too: was $offered")
    }

    /** Dialogue and action have nothing to autocomplete against. */
    @Test
    fun `body text is not autocompleted`() {
        assertTrue(SmartType.suggestions(ElementType.Dialogue, "Mor", script(), "none").isEmpty())
        assertTrue(SmartType.suggestions(ElementType.Action, "He", script(), "none").isEmpty())
    }
}
