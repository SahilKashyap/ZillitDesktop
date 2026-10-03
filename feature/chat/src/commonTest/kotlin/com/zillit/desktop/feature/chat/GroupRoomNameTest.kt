package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.feature.chat.domain.GroupRoom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A department's room is named by a label key (`camera_label`), not by words;
 * the listing, the thread header and the forward picker all showed the key.
 */
class GroupRoomNameTest {

    @BeforeTest
    fun install() {
        Labels.install(
            MutableStateFlow(
                LabelDictionary.Empty.with(LabelKind.Labels, mapOf("camera_label" to "Camera")),
            ),
        )
    }

    @AfterTest
    fun uninstall() = Labels.reset()

    @Test
    fun `a system group's label key reads as its translation`() {
        assertEquals("Camera", GroupRoom(id = "r1", name = "camera_label").displayName)
    }

    @Test
    fun `a name someone typed comes back as typed`() {
        assertEquals("Night shoot crew", GroupRoom(id = "r2", name = "Night shoot crew").displayName)
    }
}
