package com.zillit.desktop.feature.productionreport.ui

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.ui.components.ProvideReportFaces
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.dialogs.PdfOverlayView
import com.zillit.desktop.feature.productionreport.ui.dialogs.ReportDialogHost
import com.zillit.desktop.feature.productionreport.ui.editor.EditorView
import com.zillit.desktop.feature.productionreport.ui.pages.ApprovalsPage
import com.zillit.desktop.feature.productionreport.ui.pages.DraftsPage
import com.zillit.desktop.feature.productionreport.ui.pages.ManageToolbar
import com.zillit.desktop.feature.productionreport.ui.pages.NothingToManage
import com.zillit.desktop.feature.productionreport.ui.pages.PublishedPage
import com.zillit.desktop.feature.productionreport.ui.pages.ReportTopBar
import com.zillit.desktop.feature.productionreport.ui.pages.WorkspaceRail
import com.zillit.desktop.feature.productionreport.ui.theme.ProvideReportPalette
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme
import kotlin.time.Clock

/**
 * The production report tool — the web's `ProductionReportApp`: the Chat
 * workspace, the Manage Reports tabs, the editor, and every dialog.
 */
@Composable
fun ProductionReportScreen(
    state: ReportUiState,
    onEvent: (ReportEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** The tool's unit chat, hosted by the app; null shows the manager alone. */
    chat: (@Composable () -> Unit)? = null,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    ProvideReportPalette {
        ProvideReportFaces(loadAvatar) {
            Box(modifier.fillMaxSize().background(ReportTheme.colors.bgPrimary)) {
                val editor = state.editor
                when {
                    state.viewer.isBlocked -> NoAccess()
                    editor != null -> EditorView(state, editor, onEvent)
                    else -> ListView(state, onEvent, chat, nowMillis())
                }
                PdfOverlayView(state.pdf, onEvent)
                ReportDialogHost(state, onEvent)
            }
        }
    }
}

@Composable
private fun ListView(
    state: ReportUiState,
    onEvent: (ReportEvent) -> Unit,
    chat: (@Composable () -> Unit)?,
    nowMillis: Long,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(if (ReportTheme.colors.isDark) ReportTheme.colors.bgPrimary else ReportTheme.colors.editorBg),
    ) {
        ReportTopBar(state)
        val showsChat = chat != null && state.hasChat
        if (state.canManage && showsChat) WorkspaceRail(state, onEvent)
        when {
            showsChat && (state.workspace == Workspace.Chat || !state.canManage) -> Box(
                Modifier.fillMaxSize().background(ReportTheme.colors.chatBg),
            ) { chat?.invoke() }
            state.canManage -> ManageArea(state, onEvent, nowMillis)
            else -> NothingToManage()
        }
    }
}

@Composable
private fun ManageArea(state: ReportUiState, onEvent: (ReportEvent) -> Unit, nowMillis: Long) {
    ManageToolbar(state, onEvent)
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().zillitVerticalScroll(scroll).padding(16.dp)) {
            when (state.tab) {
                ManageTab.Drafts -> DraftsPage(state, onEvent, nowMillis)
                ManageTab.Approvals -> ApprovalsPage(state, onEvent, nowMillis)
                ManageTab.Published -> PublishedPage(state, onEvent, nowMillis)
            }
        }
        ZillitScrollRail(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun NoAccess() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            str(S.desktop_pr_no_access),
            style = reportText(14.sp),
            color = ReportTheme.colors.textTertiary,
        )
    }
}
