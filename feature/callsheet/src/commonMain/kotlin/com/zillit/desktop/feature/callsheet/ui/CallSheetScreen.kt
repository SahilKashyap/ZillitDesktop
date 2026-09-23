package com.zillit.desktop.feature.callsheet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.ui.components.ProvideSheetFaces
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.dialogs.PdfOverlayView
import com.zillit.desktop.feature.callsheet.ui.dialogs.SheetDialogHost
import com.zillit.desktop.feature.callsheet.ui.editor.EditorView
import com.zillit.desktop.feature.callsheet.ui.pages.ActivityPill
import com.zillit.desktop.feature.callsheet.ui.pages.ApprovalsPage
import com.zillit.desktop.feature.callsheet.ui.pages.DraftsPage
import com.zillit.desktop.feature.callsheet.ui.pages.LoadingBlock
import com.zillit.desktop.feature.callsheet.ui.pages.PermissionPage
import com.zillit.desktop.feature.callsheet.ui.pages.PublishedPage
import com.zillit.desktop.feature.callsheet.ui.pages.SheetTabRow
import com.zillit.desktop.feature.callsheet.ui.pages.SheetToolbar
import com.zillit.desktop.feature.callsheet.ui.theme.ProvideSheetPalette
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme
import kotlin.time.Clock

/**
 * The call sheet tool — the web's `CallSheetApp`: the list view (toolbar,
 * tabs, one tab at a time), the editor, the PDF viewer and every dialog.
 */
@Composable
fun CallSheetScreen(
    state: SheetUiState,
    onEvent: (SheetEvent) -> Unit,
    modifier: Modifier = Modifier,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    ProvideSheetPalette {
        ProvideSheetFaces(loadAvatar) {
            Box(modifier.fillMaxSize().background(SheetTheme.colors.bgPrimary)) {
                val editor = state.editor
                when {
                    state.viewer.isBlocked -> NoAccess()
                    editor != null -> EditorView(state, editor, onEvent)
                    else -> ListView(state, onEvent, nowMillis())
                }
                ActivityPill(
                    active = state.busy || state.lists.anyLoading() || state.permission.loading,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
                PdfOverlayView(state.pdf, onEvent)
                SheetDialogHost(state, onEvent, nowMillis)
            }
        }
    }
}

private fun SheetLists.anyLoading(): Boolean =
    listOf(drafts, sent, received, finalized, published).any { it.loading }

@Composable
private fun ListView(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: Long) {
    Column(Modifier.fillMaxSize()) {
        SheetToolbar(state)
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().zillitVerticalScroll(scroll)) {
                SheetTabRow(state, onEvent)
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    if (state.viewer.ready) {
                        when (state.activeTab) {
                            SheetTab.Drafts -> DraftsPage(state, onEvent, nowMillis)
                            SheetTab.Approvals -> ApprovalsPage(state, onEvent, nowMillis)
                            SheetTab.Published -> PublishedPage(state, onEvent, nowMillis)
                            SheetTab.Permission -> PermissionPage(state, onEvent)
                        }
                    } else {
                        LoadingBlock(str(S.desktop_cs_loading_call_sheets))
                    }
                }
            }
            ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun NoAccess() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            str(S.desktop_cs_no_access),
            style = sheetText(14.sp),
            color = SheetTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}
