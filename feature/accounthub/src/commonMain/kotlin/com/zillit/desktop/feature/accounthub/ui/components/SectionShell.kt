package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.ExportFormat

/**
 * One Production Setup section — the web's `SectionShell`.
 *
 * Two columns: the title, the description and the Unsaved pill on the left,
 * the section's content on the right, and Save changes / Cancel under it
 * once something has changed. A page nobody has touched is quiet; a page of
 * fifteen permanently-enabled Save buttons reads as fifteen pending actions.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
fun SectionShell(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    dirty: Boolean = false,
    saving: Boolean = false,
    onSave: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    editable: Boolean = true,
    extraActions: (@Composable RowScope.() -> Unit)? = null,
    leftPanel: (@Composable ColumnScope.() -> Unit)? = null,
    /** Whether the section's slice has landed; until it has, no editor and no actions. */
    load: SectionLoad = SectionLoad(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
    ) {
        Column(
            modifier = Modifier.width(LEFT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(text = title, style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                if (onSave != null && dirty && load.ready) {
                    Pill(str(S.asset_unsaved), tone = StatusTone.Pending, dot = true)
                }
            }
            FieldHint(description)
            leftPanel?.invoke(this)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            // An editor over a slice that never loaded is an editor over the
            // empty default, one Save away from writing it over the server's.
            if (!load.ready) {
                SectionLoadState(load)
                return@Column
            }
            content()
            val canSave = onSave != null && dirty
            if (editable && (canSave || extraActions != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    extraActions?.invoke(this)
                    if (onSave != null && dirty) {
                        if (onCancel != null) {
                            ZillitButton(
                                text = str(S.cancel),
                                onClick = onCancel,
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                                enabled = !saving,
                            )
                        }
                        ZillitButton(
                            text = if (saving) str(S.ah_saving) else str(S.dm_setup_save),
                            onClick = onSave,
                            size = ButtonSize.Small,
                            loading = saving,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A section's read state, as its shell draws it — see `SliceLoads`.
 *
 * [loading] is a first read still in flight; [error] is one that failed, with
 * [onRetry] to ask again. Neither shows the editor.
 */
data class SectionLoad(
    val loading: Boolean = false,
    val error: String? = null,
    val onRetry: (() -> Unit)? = null,
) {
    val ready: Boolean get() = !loading && error == null
}

/**
 * The stand-in for a section that has not loaded: two placeholder bars while
 * it is read, or the web's error card with Retry when the read failed.
 */
@Composable
fun SectionLoadState(load: SectionLoad) {
    val error = load.error
    if (error != null) {
        ZillitNotice(
            text = "${str(S.desktop_hub_couldnt_load_this_section)} $error",
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Warning,
            action = load.onRetry?.let { retry ->
                {
                    ZillitButton(
                        text = str(S.retry),
                        onClick = retry,
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            },
        )
    } else if (load.loading) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSkeletonBar(modifier = Modifier.fillMaxWidth(LOADING_BAR_FILL))
            ZillitSkeletonBar(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A downstream module's setup as a tile — the web's `HubModuleCard`.
 *
 * A 44px peach icon block, the title, a line of description and a
 * "Configure ›" call to action that fills on hover. No status pill: nothing
 * on the wire says whether a module is set up, and the web's own tile
 * hardcodes "Setup required" where it cannot know.
 */
@Composable
fun HubModuleCard(
    title: String,
    description: String,
    icon: ImageVector,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    actionText: String = str(S.desktop_configure_chevron),
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, if (hovered) colors.accent else colors.border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .clickable(enabled = enabled, onClick = onConfigure)
            .padding(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Box(
            modifier = Modifier.size(ICON_BLOCK).clip(ZillitTheme.shapes.large).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = icon, tint = colors.accentText, size = 20.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = title, style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            FieldHint(description)
        }
        ZillitButton(
            text = actionText,
            onClick = onConfigure,
            variant = if (hovered) ButtonVariant.Primary else ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = enabled,
        )
    }
}

/**
 * The Export button and its menu — the web's `ui/ExportMenu`.
 *
 * A panel drops from the button's right edge: "Download as", then a card per
 * format with its badge, what the file is for, and — on hover — its extension.
 * While a file is on its way the button says so and takes no second press.
 */
@Composable
fun ExportMenu(
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    exporting: ExportFormat?,
    onExport: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val drop = with(LocalDensity.current) { (ZillitDimens.controlHeight + EXPORT_MENU_GAP).roundToPx() }
    Box(modifier = modifier) {
        ZillitButton(
            text = if (exporting != null) str(S.desktop_exporting) else str(S.asset_export),
            onClick = { onOpen(!open) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Download,
            enabled = enabled && exporting == null,
            loading = exporting != null,
        )
        if (open && enabled && exporting == null) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, drop),
                onDismissRequest = { onOpen(false) },
                properties = PopupProperties(focusable = true),
            ) {
                ExportMenuPanel(onExport)
            }
        }
    }
}

/** The menu's panel: "Download as" over a card per format. */
@Composable
private fun ExportMenuPanel(onExport: (ExportFormat) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .width(EXPORT_MENU_WIDTH)
            .shadow(EXPORT_MENU_ELEVATION, EXPORT_MENU_SHAPE)
            .clip(EXPORT_MENU_SHAPE)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, EXPORT_MENU_SHAPE)
            .padding(7.dp),
    ) {
        ZillitText(
            text = str(S.desktop_download_as),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.14.em,
            ),
            color = colors.textMuted,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        )
        ExportFormat.entries.forEachIndexed { index, format ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.divider),
                )
            }
            ExportCard(format, onClick = { onExport(format) })
        }
    }
}

@Composable
private fun ExportCard(format: ExportFormat, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExportBadge(format)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = "Export ${format.label}",
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            // Wraps rather than truncates, as the web's does: "print-rea…"
            // tells the reader less than a second line would.
            ZillitText(
                text = format.purpose,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 2,
            )
        }
        // Shown by alpha, not composed on hover, so the card never changes size under the pointer.
        Box(
            modifier = Modifier
                .alpha(if (hovered) 1f else 0f)
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.divider, ZillitTheme.shapes.medium)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            ZillitText(
                text = ".${format.extension}",
                style = ZillitTheme.typography.numeric.copy(fontSize = 10.sp),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** The format's coloured tile — red for PDF, green for a spreadsheet, grey for plain text, as the web tints them. */
@Composable
private fun ExportBadge(format: ExportFormat) {
    val colors = ZillitTheme.colors
    val tint = when (format) {
        ExportFormat.Pdf -> colors.danger
        ExportFormat.Excel -> colors.success
        ExportFormat.Csv -> colors.textSecondary
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(lerp(tint, Color.White, BADGE_LIGHTEN), tint))),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = format.badge,
            style = ZillitTheme.typography.numeric.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** The badge the web prints on each card. */
private val ExportFormat.badge: String
    get() = when (this) {
        ExportFormat.Pdf -> "PDF"
        ExportFormat.Excel -> "XLS"
        ExportFormat.Csv -> "CSV"
    }

/** What each file is for, in the web's words. */
private val ExportFormat.purpose: String
    get() = when (this) {
        ExportFormat.Pdf -> str(S.desktop_hub_formatted_document_print_ready)
        ExportFormat.Excel -> str(S.desktop_hub_editable_spreadsheet_with_live_data)
        ExportFormat.Csv -> str(S.desktop_hub_plain_comma_separated_values)
    }

private val LEFT_WIDTH = 260.dp
private const val LOADING_BAR_FILL = 0.6f
private val ICON_BLOCK = 44.dp
/** The web's 320px, plus the room Inter needs here to keep each description on one line. */
private val EXPORT_MENU_WIDTH = 348.dp
private val EXPORT_MENU_GAP = 8.dp
private val EXPORT_MENU_ELEVATION = 18.dp
private val EXPORT_MENU_SHAPE = RoundedCornerShape(16.dp)
private const val BADGE_LIGHTEN = 0.22f
