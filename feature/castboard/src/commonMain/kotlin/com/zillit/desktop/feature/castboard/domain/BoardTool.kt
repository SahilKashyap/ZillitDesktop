package com.zillit.desktop.feature.castboard.domain

import com.zillit.desktop.core.config.ZillitService

/**
 * Casting and Wardrobe are one board.
 *
 * Both are the production's characters at a stage of getting them on screen —
 * who is playing the part, and what that part wears — and both services
 * answer the same shape from the same route spelled with a different noun
 * (`casting-info/{unitId}?status=` and `wardrobe-info/{unitId}?status=`), on
 * their own hosts, each split into a main and a background unit.
 *
 * The web keeps two three-thousand-line pages that differ by that noun. One
 * engine with a descriptor is the same behaviour with one place to fix.
 */
data class BoardTool(
    val title: String,
    /** Which host answers — they are different services, despite the shared shape. */
    val service: ZillitService,
    /** `casting` or `wardrobe`: the path segment and the info route's prefix. */
    val segment: String,
    /** The two units, in the order the tabs show them. */
    val units: List<BoardUnitKind>,
    /**
     * Wardrobe rows carry the scenes a costume is worn in
     * (`wardrobeApi/api.js:212`); casting rows carry a hierarchy instead.
     */
    val showsScenes: Boolean,
) {

    val infoPath: String get() = "$segment/$segment-info"

    companion object {

        val Casting = BoardTool(
            title = "Casting",
            service = ZillitService.Casting,
            segment = "casting",
            units = listOf(
                BoardUnitKind("casting_main_tool", "Main cast"),
                BoardUnitKind("casting_background_tool", "Background cast"),
            ),
            showsScenes = false,
        )

        val Wardrobe = BoardTool(
            title = "Wardrobe",
            service = ZillitService.Wardrobe,
            segment = "wardrobe",
            units = listOf(
                BoardUnitKind("wardrobe_main_tool", "Main wardrobe"),
                BoardUnitKind("wardrobe_background_tool", "Background wardrobe"),
            ),
            showsScenes = true,
        )
    }
}

/** One of a board's two lists, named by the tool that grants it. */
data class BoardUnitKind(val identifier: String, val label: String)
