@file:Suppress("LongMethod")

package com.zillit.desktop.feature.esignature.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.esignature.ui.components.AccentSegmented
import com.zillit.desktop.feature.esignature.ui.pages.BulkPage
import com.zillit.desktop.feature.esignature.ui.pages.DetailPage
import com.zillit.desktop.feature.esignature.ui.pages.EditorPage
import com.zillit.desktop.feature.esignature.ui.pages.ManageListPage
import com.zillit.desktop.feature.esignature.ui.pages.MarksDialog
import com.zillit.desktop.feature.esignature.ui.pages.SignListPage
import com.zillit.desktop.feature.esignature.ui.pages.SigningPage
import com.zillit.desktop.feature.esignature.ui.pages.TemplatesPage

/**
 * E-Signature — the web's `DocuSignPanel`.
 *
 * Four segments for the manager (upload, sign, templates, bulk sends) and
 * one for everyone else; the editor, the tracker and the signing surface
 * stack over the lists as full pages. A member without posting rights
 * gets only the receiver's side, which is the web's receiver-only branch.
 */
@Composable
fun EsignScreen(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize().background(colors.surface)) {
        AnimatedContent(
            targetState = state.page,
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    (fadeIn() + slideInHorizontally { it / SLIDE_FRACTION }) togetherWith
                        (fadeOut() + slideOutHorizontally { -it / SLIDE_FRACTION })
                } else {
                    (fadeIn() + slideInHorizontally { -it / SLIDE_FRACTION }) togetherWith
                        (fadeOut() + slideOutHorizontally { it / SLIDE_FRACTION })
                }
            },
            label = "esignPage",
        ) { page ->
            when (page) {
                EsignPageKind.Lists -> ListsShell(state, onEvent)
                EsignPageKind.Editor -> EditorPage(state, onEvent)
                EsignPageKind.Detail -> DetailPage(state, onEvent)
                EsignPageKind.Signing -> SigningPage(state, onEvent)
            }
        }
    }
    MarksDialog(state, onEvent)
}

@Composable
private fun ListsShell(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitPageHeader(
            eyebrow = str(S.desktop_film_tools),
            title = str(S.desktop_ds_e_signature),
            description = if (state.viewer.receiverOnly) {
                str(S.docusign_receiver_subtitle)
            } else {
                str(S.desktop_ds_send_documents_for_signature_track_them_and_sign)
            },
            actions = {
                ZillitButton(
                    text = str(S.docusign_my_saved_signatures),
                    onClick = { onEvent(EsignEvent.OpenMarks) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
                ZillitButton(
                    text = str(S.docusign_refresh),
                    onClick = { onEvent(EsignEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.manage.loading || state.signList.loading ||
                        state.templates.loading || state.bulk.loading,
                )
            },
        )
        when {
            state.viewer.isBlocked -> ZillitNotice(
                text = str(S.desktop_ds_you_don_t_have_access_to_e_signature),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
            state.viewer.receiverOnly -> SignListPage(state, onEvent)
            else -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AccentSegmented(
                        options = EsignSurface.entries.map { surface ->
                            Triple(
                                surface.name,
                                surface.label,
                                if (surface == EsignSurface.Sign) state.pendingForMe else 0,
                            )
                        },
                        activeId = state.surface.name,
                        onSelect = { id ->
                            EsignSurface.entries.firstOrNull { it.name == id }
                                ?.let { onEvent(EsignEvent.SwitchSurface(it)) }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                }
                AnimatedContent(
                    targetState = state.surface,
                    transitionSpec = {
                        (fadeIn() + slideInHorizontally { SLIDE_PX }) togetherWith
                            (fadeOut() + slideOutHorizontally { -SLIDE_PX })
                    },
                    label = "esignSurface",
                ) { surface ->
                    Box(Modifier.fillMaxSize()) {
                        when (surface) {
                            EsignSurface.Manage -> ManageListPage(state, onEvent)
                            EsignSurface.Sign -> SignListPage(state, onEvent)
                            EsignSurface.Templates -> TemplatesPage(state, onEvent)
                            EsignSurface.Bulk -> BulkPage(state, onEvent)
                        }
                    }
                }
            }
        }
    }
}

private const val SLIDE_FRACTION = 20
private const val SLIDE_PX = 24
