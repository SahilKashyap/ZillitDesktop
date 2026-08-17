package com.zillit.desktop.feature.auth

import com.zillit.desktop.feature.auth.data.humanise
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Preset labels arrive as translation keys, not display names.
 *
 * Found by turning on `-Pzillit.http=body` against QA: `preset/project-types`
 * returns `entertainment_industry_label`, and the create dialog was rendering
 * that verbatim.
 */
class PresetLabelTest {

    @Test
    fun `label keys become readable names`() {
        assertEquals("Entertainment Industry", "entertainment_industry_label".humanise())
        assertEquals("Personal Communication", "personal_communication_label".humanise())
        assertEquals("Feature", "feature_label".humanise())
        assertEquals("Music Video", "music_video_label".humanise())
    }

    @Test
    fun `a plain value is left alone apart from casing`() {
        assertEquals("Other", "other".humanise())
    }

    @Test
    fun `nothing blows up on empty or odd input`() {
        assertEquals("", "".humanise())
        assertEquals("", "_label".humanise())
        assertEquals("A B", "a__b".humanise())
    }
}
