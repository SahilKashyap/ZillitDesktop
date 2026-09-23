package com.zillit.desktop.feature.draft.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The eight paragraph kinds a screenplay is written in — Final Draft's
 * element list, in its order. The number is the ⌘/Ctrl shortcut.
 */
@Suppress("MagicNumber") // The shortcut digits are the entries' own numbers.
enum class ElementType(private val labelKey: String, val shortcut: Int, val fdxName: String) {
    SceneHeading(S.desktop_draft_scene_heading, 1, "Scene Heading"),
    Action(S.txt_action, 2, "Action"),
    Character(S.character, 3, "Character"),
    Parenthetical(S.desktop_draft_parenthetical, 4, "Parenthetical"),
    Dialogue(S.desktop_draft_dialogue, 5, "Dialogue"),
    Transition(S.desktop_draft_transition, 6, "Transition"),
    Shot(S.desktop_draft_shot, 7, "Shot"),
    General(S.ce_note_type_general, 8, "General"),
    ;

    val label: String get() = str(labelKey)

    /** Scene headings, characters, transitions and shots are typed in caps. */
    val isUppercase: Boolean
        get() = this == SceneHeading || this == Character || this == Transition || this == Shot

    /** Character, parenthetical and dialogue belong to one speech. */
    val isSpeech: Boolean
        get() = this == Character || this == Parenthetical || this == Dialogue

    companion object {
        fun fromFdx(name: String): ElementType =
            entries.firstOrNull { it.fdxName.equals(name.trim(), ignoreCase = true) } ?: Action

        fun fromShortcut(digit: Int): ElementType? = entries.firstOrNull { it.shortcut == digit }
    }
}

/** One paragraph of the script. */
data class ScriptElement(
    val id: String,
    val type: ElementType,
    val text: String,
) {
    val isBlank: Boolean get() = text.isBlank()
}

/** What the title page says. Empty fields are left off the page. */
data class TitlePage(
    val title: String = "",
    val credit: String = "Written by",
    val author: String = "",
    val source: String = "",
    val draftDate: String = "",
    val contact: String = "",
    val notes: String = "",
)

/** One script of one production, as written. */
data class Screenplay(
    val id: String,
    val projectId: String,
    val title: String,
    val titlePage: TitlePage = TitlePage(),
    val elements: List<ScriptElement> = emptyList(),
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    val scenes: List<IndexedValue<ScriptElement>>
        get() = elements.withIndex().filter { it.value.type == ElementType.SceneHeading }

    val wordCount: Int
        get() = elements.sumOf { e -> e.text.split(WHITESPACE).count { it.isNotBlank() } }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** What the list page shows for a script. */
data class ScriptSummary(
    val id: String,
    val title: String,
    val pageCount: Int,
    val sceneCount: Int,
    val updatedAtMillis: Long,
)

/**
 * Where the caret goes next — Final Draft's typing rhythm.
 *
 * Enter finishes an element and starts the one that usually follows it:
 * a heading is followed by action, a character by their line, a line by the
 * next speaker. Tab moves sideways: from action to a character cue, from a
 * cue into a parenthetical, from dialogue to a parenthetical, and on an
 * empty element it cycles through the types so the whole set is reachable
 * without a menu.
 */
object ElementFlow {

    fun onEnter(type: ElementType): ElementType = when (type) {
        ElementType.SceneHeading -> ElementType.Action
        ElementType.Action -> ElementType.Action
        ElementType.Character -> ElementType.Dialogue
        ElementType.Parenthetical -> ElementType.Dialogue
        ElementType.Dialogue -> ElementType.Character
        ElementType.Transition -> ElementType.SceneHeading
        ElementType.Shot -> ElementType.Action
        ElementType.General -> ElementType.General
    }

    /** Tab on an element that has text in it. */
    fun onTab(type: ElementType): ElementType = when (type) {
        ElementType.SceneHeading -> ElementType.Action
        ElementType.Action -> ElementType.Character
        ElementType.Character -> ElementType.Parenthetical
        ElementType.Parenthetical -> ElementType.Dialogue
        ElementType.Dialogue -> ElementType.Parenthetical
        ElementType.Transition -> ElementType.SceneHeading
        ElementType.Shot -> ElementType.Action
        ElementType.General -> ElementType.Action
    }

    /**
     * Tab on an EMPTY element — Final Draft's shortcut for "not this one":
     * an empty cue becomes a transition, an empty transition a heading, an
     * empty line of dialogue ends the speech and becomes action. Pressed
     * again it keeps going, so every writing element is a few Tabs away.
     */
    fun onEmptyTab(type: ElementType): ElementType = when (type) {
        ElementType.SceneHeading -> ElementType.Action
        ElementType.Action -> ElementType.Character
        ElementType.Character -> ElementType.Transition
        ElementType.Parenthetical -> ElementType.Dialogue
        ElementType.Dialogue -> ElementType.Action
        ElementType.Transition -> ElementType.SceneHeading
        ElementType.Shot -> ElementType.Action
        ElementType.General -> ElementType.SceneHeading
    }

    /** Shift+Tab: the previous type round the full list. */
    fun previous(type: ElementType): ElementType {
        val all = ElementType.entries
        return all[(all.indexOf(type) - 1 + all.size) % all.size]
    }

    /** What a brand-new script opens with. */
    val first: ElementType = ElementType.SceneHeading
}
