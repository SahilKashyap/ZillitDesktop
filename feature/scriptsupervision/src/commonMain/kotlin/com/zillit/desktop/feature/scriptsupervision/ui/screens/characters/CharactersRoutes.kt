package com.zillit.desktop.feature.scriptsupervision.ui.screens.characters

import androidx.compose.runtime.Composable
import com.zillit.desktop.feature.scriptsupervision.domain.CharacterSection
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.screens.looks.LookDetailScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.looks.PropDetailScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.looks.WardrobeItemDetailScreen

/**
 * The Characters page and everything under it — the web's `characters`,
 * `characters/:id`, `characters/:id/:section`, `…/:section/:day` and
 * `characters/:id/scenes/:sceneId` routes.
 *
 * One dispatcher rather than five menu rows, because the web keeps the
 * Characters row selected through all of them: a character's section is a
 * place INSIDE this page, not a page of its own.
 */
@Composable
internal fun CharactersRoutes(onOpen: (SsPage) -> Unit) {
    when (val view = nav().current) {
        is SsView.Character -> CharacterDetailScreen(view.id, onOpen)
        is SsView.Section ->
            // `scenes` has a screen of its own (the hand-made links are
            // managed there); every other section is one list.
            if (view.section == CharacterSection.Scenes) {
                CharacterScenesScreen(view.id)
            } else {
                CharacterSectionScreen(view.id, view.section, onOpen)
            }
        is SsView.SectionDay -> CharacterDayScenesScreen(view.id, view.section, view.day)
        is SsView.CharacterScene -> CharacterSceneScreen(view.id, view.sceneId)
        // A look reached from one of a character's look sections.
        is SsView.Look -> LookDetailScreen(view.lookId, view.department)
        is SsView.WardrobeItem -> WardrobeItemDetailScreen(view.itemId)
        is SsView.Prop -> PropDetailScreen(view.itemId)
        else -> CharactersScreen()
    }
}
