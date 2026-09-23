package com.zillit.desktop.feature.productionreport.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.reportToolName
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.Workspace
import com.zillit.desktop.feature.productionreport.ui.components.TabBadge
import com.zillit.desktop.feature.productionreport.ui.components.UnderlineTab
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/** The list view's title bar: the tool's name — "Production Report Creation" for posting users. */
@Composable
internal fun ReportTopBar(state: ReportUiState) {
    val colors = ReportTheme.colors
    val title = if (state.kind == ReportKind.Production) reportToolName(state.isPoster) else state.kind.title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentLight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ReportIcons.FileDone, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Text(
            title,
            style = reportText(18.sp, FontWeight.SemiBold, 26.sp),
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderStrong))
}

/**
 * The Chat ⇄ Manage Reports switch — `.rpt-view-switch`, a sliding outlined
 * thumb. Both segments take the wider label's width; the thumb is drawn
 * behind them and slides by half the track. Drawn rather than laid out: a
 * `BoxWithConstraints` here sat under an intrinsic width and threw the moment
 * the rail was shown.
 */
@Composable
internal fun WorkspaceRail(state: ReportUiState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val second = state.workspace == Workspace.Manage
    val progress by animateFloatAsState(if (second) 1f else 0f, tween(THUMB_MS))
    val thumbFill = if (colors.isDark) colors.elevated else Color.White
    val thumbShade = colors.border
    val thumbEdge = colors.accent
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.segmentTrack)
                .border(1.dp, colors.border, RoundedCornerShape(9.dp))
                .padding(2.dp)
                .drawBehind {
                    val half = size.width / 2
                    val radius = CornerRadius(7.dp.toPx())
                    val stroke = 1.dp.toPx()
                    val left = half * progress
                    drawRoundRect(thumbShade, Offset(left, stroke), Size(half, size.height), radius)
                    drawRoundRect(thumbFill, Offset(left, 0f), Size(half, size.height), radius)
                    drawRoundRect(
                        color = thumbEdge,
                        topLeft = Offset(left + stroke / 2, stroke / 2),
                        size = Size(half - stroke, size.height - stroke),
                        cornerRadius = radius,
                        style = Stroke(stroke),
                    )
                },
        ) {
            SwitchSegment(str(S.pr_workspace_chat), ZillitIcons.Chat, !second, badge = 0, Modifier.weight(1f)) {
                onEvent(ListEvent.SetWorkspace(Workspace.Chat))
            }
            SwitchSegment(
                str(S.pr_workspace_manage),
                ReportIcons.FileDone,
                second,
                badge = if (second) 0 else state.manageBadge,
                Modifier.weight(1f),
            ) {
                onEvent(ListEvent.SetWorkspace(Workspace.Manage))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun SwitchSegment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badge: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    val text = when {
        selected -> colors.textPrimary
        hovered -> colors.textSecondary
        else -> colors.textTertiary
    }
    Row(
        modifier = modifier
            .height(30.dp)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) colors.accent else text,
            modifier = Modifier.size(15.dp),
        )
        Text(label, style = reportText(13.sp, FontWeight.Medium), color = text, maxLines = 1)
        TabBadge(badge)
    }
}

private const val THUMB_MS = 180

/** Drafts · Approvals · Published, and Create Template for authors. */
@Composable
internal fun ManageToolbar(state: ReportUiState, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.manageTabs.forEach { tab ->
            UnderlineTab(tab.label, active = state.tab == tab, badge = state.tabBadge(tab), strong = true) {
                onEvent(ListEvent.OpenTab(tab))
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.isPoster) CreateTemplateButton { onEvent(DialogEvent.CreateTemplate) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun CreateTemplateButton(onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .shadow(
                if (hovered) 6.dp else 3.dp,
                RoundedCornerShape(12.dp),
                ambientColor = colors.accent,
                spotColor = colors.accent,
            )
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) colors.accentHover else colors.accent)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ZillitIcons.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
        Text(str(S.create_template), style = reportText(14.sp, FontWeight.SemiBold), color = Color.White)
    }
}

/** What a view-only user sees when the chat is not theirs and nothing else is. */
@Composable
internal fun NothingToManage(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(
            str(S.desktop_pr_published_arrive_in_chat),
            style = reportText(14.sp),
            color = ReportTheme.colors.textTertiary,
        )
    }
}
