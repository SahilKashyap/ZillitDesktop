package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentRoles
import com.zillit.desktop.feature.talentdeals.ui.FileViewState
import com.zillit.desktop.feature.talentdeals.ui.RegisterState
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent

/**
 * Add Contract's first step, asked BEFORE the form opens so the form never
 * carries a role field of its own: the role titles the page and decides what
 * the form offers (Exhibit G exists for SAG cast only). A fresh dialog every
 * time it opens — a role picked and then cancelled must not sit pre-selected.
 */
@Suppress("LongMethod") // A page laid out section by section.
@Composable
internal fun RolePickerDialog(open: Boolean, onEvent: (TalentDealsEvent) -> Unit) {
    var picked by remember(open) { mutableStateOf("") }
    var adding by remember(open) { mutableStateOf(false) }
    var custom by remember(open) { mutableStateOf("") }
    val role = if (adding) custom.trim() else picked

    ZillitDialogShell(
        title = str(S.desktop_td_add_contract),
        onDismiss = { onEvent(TalentDealsEvent.CancelRolePicker) },
        visible = open,
        width = ROLE_DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(TalentDealsEvent.CancelRolePicker) },
            )
            ZillitButton(
                text = str(S.continue_text),
                enabled = role.isNotEmpty(),
                onClick = { if (role.isNotEmpty()) onEvent(TalentDealsEvent.PickRole(role)) },
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_td_which_role).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            modifier = Modifier.padding(top = ZillitTheme.spacing.sm)) {
            TalentRoles.ROLES.forEach { option ->
                RoleRow(label = option, selected = !adding && picked == option) {
                    picked = option
                    adding = false
                }
            }
            if (adding) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ZillitTheme.shapes.large)
                        .border(1.dp, ZillitTheme.colors.accent, ZillitTheme.shapes.large)
                        .padding(ZillitTheme.spacing.sm),
                ) {
                    ZillitTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        placeholder = str(S.desktop_td_name_the_role),
                        onImeAction = { if (role.isNotEmpty()) onEvent(TalentDealsEvent.PickRole(role)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                RoleRow(label = str(S.desktop_td_add_another_role), selected = false, dashed = true) {
                    adding = true
                    picked = ""
                }
            }
        }
    }
}

@Composable
private fun RoleRow(label: String, selected: Boolean, dashed: Boolean = false, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = if (dashed) ZillitIcons.Add else ZillitIcons.User, size = 18.dp)
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (dashed) colors.textSecondary else colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) ZillitIcon(icon = ZillitIcons.Check, tint = colors.accent, size = 16.dp)
    }
}

/** Remove contract — the register's confirm. The agreement stays in storage; only the row leaves the list. */
@Composable
internal fun RemoveContractDialog(register: RegisterState, onEvent: (TalentDealsEvent) -> Unit) {
    val target: TalentContract? = register.deleteTarget
    ZillitDialogShell(
        title = str(S.desktop_dm_remove_contract),
        onDismiss = { onEvent(TalentDealsEvent.CancelRemove) },
        visible = target != null,
        width = CONFIRM_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                enabled = !register.deleting,
                onClick = { onEvent(TalentDealsEvent.CancelRemove) },
            )
            ZillitButton(
                text = if (register.deleting) str(S.desktop_tax_removing) else str(S.remove),
                variant = ButtonVariant.Danger,
                loading = register.deleting,
                onClick = { onEvent(TalentDealsEvent.ConfirmRemove) },
            )
        },
    ) {
        if (target != null) {
            ZillitText(
                text = str(S.desktop_td_remove_contract_message, target.reference, target.person),
                maxLines = Int.MAX_VALUE,
            )
        }
    }
}

/**
 * The in-app file viewer — the web's `AttachmentPreviewOverlay`. View is open
 * to every viewer; only leaving with the file is gated by `download_access`, so
 * Download simply is not offered without it. (A PDF's own toolbar can still
 * save the file elsewhere on the web; here the server must enforce it too.)
 */
@Composable
internal fun FileViewerDialog(view: FileViewState?, canDownload: Boolean, onEvent: (TalentDealsEvent) -> Unit) {
    ZillitDialogShell(
        title = view?.attachment?.displayName ?: str(S.attachment),
        onDismiss = { onEvent(TalentDealsEvent.CloseViewer) },
        visible = view != null,
        scrollable = false,
        width = VIEWER_WIDTH,
        maxHeight = VIEWER_MAX_HEIGHT,
        actions = {
            if (canDownload && view != null && !view.loading) {
                ZillitButton(
                    text = str(S.download),
                    leadingIcon = ZillitIcons.Download,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    onClick = { onEvent(TalentDealsEvent.DownloadViewed) },
                )
            }
            ZillitButton(
                text = str(S.close),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(TalentDealsEvent.CloseViewer) },
            )
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(VIEWER_HEIGHT)
                .clip(ZillitTheme.shapes.medium)
                .background(Color(VIEWER_BACKDROP)),
        ) {
            when {
                view == null -> Unit
                view.loading -> Centre { ZillitSpinner(size = 28.dp) }
                view.error != null -> Centre { LightText(view.error) }
                view.unsupported -> Centre { LightText(str(S.desktop_td_no_preview_download)) }
                view.image != null -> ImageBody(view.image)
                else -> PageStack(view)
            }
        }
    }
}

@Composable
private fun Centre(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun LightText(text: String) {
    ZillitText(text, color = Color.White, maxLines = Int.MAX_VALUE)
}

@Composable
private fun ImageBody(bytes: ByteArray) {
    val bitmap = remember(bytes) { decodeImageBitmap(bytes) }
    Centre {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize())
        } else {
            LightText(str(S.desktop_preview_not_available))
        }
    }
}

@Composable
private fun PageStack(view: FileViewState) {
    ZillitLazyColumn(
        Modifier.fillMaxSize().padding(horizontal = ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = ZillitTheme.spacing.lg),
    ) {
        items(view.pages, key = { it.page }) { page ->
            val bitmap = remember(page.page) { decodeImageBitmap(page.imageBytes) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = str(S.desktop_page_n, page.page),
                    modifier = Modifier.fillMaxWidth().widthIn(max = PAGE_MAX),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

private val ROLE_DIALOG_WIDTH = 440.dp
private val CONFIRM_WIDTH = 460.dp
private val VIEWER_WIDTH = 1000.dp
private val VIEWER_MAX_HEIGHT = 860.dp
private val VIEWER_HEIGHT = 700.dp
private val PAGE_MAX = 880.dp
private const val VIEWER_BACKDROP = 0xFF525659
