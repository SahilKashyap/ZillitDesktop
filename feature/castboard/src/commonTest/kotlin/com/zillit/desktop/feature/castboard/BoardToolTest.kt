package com.zillit.desktop.feature.castboard

import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.castboard.data.CastingEntryDto
import com.zillit.desktop.feature.castboard.data.toEntry
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingViewer
import com.zillit.desktop.feature.castboard.ui.CastingUiState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Casting and Wardrobe are one engine.
 *
 * These pin the handful of things that actually differ, so the shared parts
 * can stay shared without either board quietly borrowing the other's wire.
 */
class BoardToolTest {

    /** Same route shape, different noun — and different hosts. */
    @Test
    fun `each board names its own service and route`() {
        assertEquals(ZillitService.Casting, BoardTool.Casting.service)
        assertEquals("casting/casting-info", BoardTool.Casting.infoPath)

        assertEquals(ZillitService.Wardrobe, BoardTool.Wardrobe.service)
        assertEquals("wardrobe/wardrobe-info", BoardTool.Wardrobe.infoPath)
    }

    /** A board only ever offers its own two tools' lists. */
    @Test
    fun `a board sees only its own units`() {
        val permissions = ProjectPermissions(
            listOf(
                ToolAccess(identifier = "casting_main_tool", unitId = "cast-main", canView = true),
                ToolAccess(identifier = "wardrobe_main_tool", unitId = "ward-main", canView = true),
            ),
        )

        val casting = CastingViewer.from(permissions, BoardTool.Casting)
        val wardrobe = CastingViewer.from(permissions, BoardTool.Wardrobe)

        assertEquals(listOf("cast-main"), casting.units.map { it.unitId })
        assertEquals(listOf("ward-main"), wardrobe.units.map { it.unitId })
    }

    /** Wardrobe rights alone must not open the casting board. */
    @Test
    fun `the other board's rights are not this board's`() {
        val permissions = ProjectPermissions(
            listOf(ToolAccess(identifier = "wardrobe_background_tool", unitId = "ward-bg", canView = true)),
        )

        assertTrue(CastingViewer.from(permissions, BoardTool.Casting).hasNoAccess)
        assertTrue(!CastingViewer.from(permissions, BoardTool.Wardrobe).hasNoAccess)
    }

    /**
     * A costume is worn in scenes; a part has a hierarchy. Wardrobe rows
     * carry `scene_number` (`wardrobeApi/api.js:212`) and casting rows do not.
     */
    @Test
    fun `a wardrobe row carries its scenes`() {
        val rows = Json { ignoreUnknownKeys = true }
            .decodeFromString(
                ListSerializer(CastingEntryDto.serializer()),
                """[{"_id":"w1","character_name":"Inspector Rao","scene_number":["12","14"],"episode":2}]""",
            )
            .mapNotNull { it.toEntry() }

        assertEquals("12, 14", rows.single().scenes)
        assertEquals("2", rows.single().episode)
    }

    /**
     * The production's word wins over ours.
     *
     * This deployment calls its wardrobe lists "Costume (Main Cast)"; a board
     * headed "Wardrobe" would name something the crew has never seen. The
     * bracketed list stays on the tab, the word alone heads the page.
     */
    @Test
    fun `the board wears the production's own name`() {
        val permissions = ProjectPermissions(
            listOf(
                ToolAccess(
                    identifier = "wardrobe_main_tool",
                    unitId = "ward-main",
                    unitName = "Costume (Main Cast)",
                    canView = true,
                ),
            ),
        )
        val state = CastingUiState(viewer = CastingViewer.from(permissions, BoardTool.Wardrobe))

        assertEquals("Costume", state.title(BoardTool.Wardrobe.title))
        assertEquals("Costume (Main Cast)", state.viewer.units.single().label)
    }

    /** A production that sends no names keeps our own. */
    @Test
    fun `an unnamed unit falls back to the descriptor`() {
        val permissions = ProjectPermissions(
            listOf(ToolAccess(identifier = "casting_main_tool", unitId = "cast-main", canView = true)),
        )
        val state = CastingUiState(viewer = CastingViewer.from(permissions, BoardTool.Casting))

        assertEquals("Casting", state.title(BoardTool.Casting.title))
        assertEquals("Main Cast", state.viewer.units.single().label)
    }

    /** Only wardrobe draws them, whatever a row happens to carry. */
    @Test
    fun `only the wardrobe board shows scenes`() {
        assertTrue(BoardTool.Wardrobe.showsScenes)
        assertTrue(!BoardTool.Casting.showsScenes)
    }
}
