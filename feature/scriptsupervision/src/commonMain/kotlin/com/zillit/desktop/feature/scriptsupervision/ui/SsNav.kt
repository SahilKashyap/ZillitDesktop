package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.feature.scriptsupervision.domain.CharacterSection

/**
 * Where inside a page the reader is — the web's routes under a page
 * (`characters/:id/:section`, `looks/:id`, `scenes/:id`).
 *
 * [SsPage] is the menu, and a detail page is deliberately NOT in it: the web
 * keeps the menu row selected while a detail screen is open, and a detail page
 * with a menu row of its own would highlight nothing. So a page's inner
 * position lives here instead, and the menu stays on the page it belongs to.
 */
sealed interface SsView {
    /** The page's own screen — its list or board. */
    data object Root : SsView

    /** A character's hub, `characters/:id`. */
    data class Character(val id: String) : SsView

    /** One of a character's sections, `characters/:id/:section`. */
    data class Section(val id: String, val section: CharacterSection) : SsView

    /**
     * One day's scenes inside a section, `characters/:id/script-days/:label`
     * and `…/shooting-days/:day`. [day] is the label, or the day number as
     * text, exactly as the web's URL segment is.
     */
    data class SectionDay(val id: String, val section: CharacterSection, val day: String) : SsView

    /** A scene, as a character's page opens it: `characters/:id/scenes/:sceneId`. */
    data class CharacterScene(val id: String, val sceneId: String) : SsView

    /**
     * A look, `characters/:id/looks/:lookId` or the look board's `looks/:id`.
     *
     * [department] travels with it because the look's own record is not loaded
     * until the detail screen fetches its department's list — and that fetch
     * is what needs to know which department it is.
     */
    data class Look(val lookId: String, val characterId: String = "", val department: String = "costume") : SsView

    /** A wardrobe item's page, `wardrobe/:id`. */
    data class WardrobeItem(val itemId: String) : SsView

    /** A prop's page, `props/:id`. */
    data class Prop(val itemId: String) : SsView

    /**
     * One scene's breakdown, the web's `script-breakdown?scene=<id>`.
     *
     * A query parameter there rather than a path segment, so a link from
     * elsewhere opens straight onto a scene; here it is the page's inner
     * position, which is the same thing.
     */
    data class BreakdownScene(val sceneId: String) : SsView

    /**
     * Designing a template, the web's `template-editor/:templateId`.
     *
     * A blank [templateId] is a NEW template — the web spells that `/new` in
     * its URL, and the editor branches on it either way.
     */
    data class TemplateEditor(val templateId: String) : SsView

    /** Logging through a template, `template-log/:templateId`. */
    data class TemplateLog(val templateId: String) : SsView
}

/**
 * The reader's way back, per page.
 *
 * A stack rather than one current view, because every detail screen in this
 * module is reached from another one (Characters › RAVI › Costume › a look)
 * and the web gives each a breadcrumb back. [back] pops one step. Leaving the
 * page keeps that page's stack, so returning to Characters returns to the
 * character that was open — which is what the web's history does.
 */
@Stable
class SsNav {

    /**
     * Each page's stack. One state value rather than a mutable map, so a push
     * or a pop recomposes whoever is reading [current].
     */
    private var stacks: Map<SsPage, List<SsView>> by mutableStateOf(emptyMap())

    /** The page whose stack is being read; the shell sets it as the menu moves. */
    var page: SsPage by mutableStateOf(SsPage.Start)
        internal set

    /** Where the open page is. */
    val current: SsView get() = stacks[page]?.lastOrNull() ?: SsView.Root

    val canGoBack: Boolean get() = stacks[page]?.isNotEmpty() == true

    /** Opens [view] one level deeper on the open page. */
    fun go(view: SsView) = replace(page, stacks[page].orEmpty() + view)

    /** Replaces the innermost step — a sibling, not a deeper level (the next look along). */
    fun swap(view: SsView) = replace(page, stacks[page].orEmpty().dropLast(1) + view)

    fun back() = replace(page, stacks[page].orEmpty().dropLast(1))

    /** Back to the page's own screen, as a breadcrumb's first crumb goes. */
    fun root() = replace(page, emptyList())

    /**
     * Opens [view] on another page, as an Actors card does when it opens the
     * character that actor plays.
     *
     * The jump REPLACES that page's stack rather than adding to it: the reader
     * asked for this character, not for whatever was open there before.
     */
    fun goOn(target: SsPage, view: SsView, open: (SsPage) -> Unit) {
        replace(target, listOf(view))
        open(target)
    }

    private fun replace(target: SsPage, stack: List<SsView>) {
        stacks = stacks + (target to stack)
    }
}

val LocalNav = compositionLocalOf<SsNav> { error("No SsNav") }

@Composable
fun nav(): SsNav = LocalNav.current
