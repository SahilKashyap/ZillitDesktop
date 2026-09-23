package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.BuilderOrigin
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FormSectionsSkeleton
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill

/**
 * Which fields a module's form shows, in what order, and which are required —
 * the web's `FormConfigModule`.
 *
 * ## Three views
 *
 * The **preview** — the module rail, a hero, the green default card and the
 * sections as the form will render them. **Edit** — full width, rail gone: a
 * sticky bar with Rearrange, Reset and Save, the tips, every section with its
 * fields clickable and an insert rail between them, and a panel on the right
 * for the field in hand or for rearranging. And the **approver builder** the
 * default card's "Set Approver Level" opens — the Approvers page's own, saved
 * through the same route.
 *
 * ## A system field is taken off the form, never deleted
 *
 * The module's schema owns it. Removing one hides it and the add-a-field
 * panel offers it back under "System Fields"; a custom field is deleted
 * outright. One button, two meanings, and the difference is the whole design.
 *
 * ## Nothing is addressed by position
 *
 * Every surface here filters something — hidden fields, the terms section — so
 * an index taken from what is on screen names a different row in the
 * document. Sections go by key and fields by their form key.
 */
@Composable
fun FormConfigPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.formConfig
    val builder = state.approvals.builder?.takeIf { it.origin == BuilderOrigin.Forms }
    Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            builder != null ->
                ApprovalBuilderView(state, builder, onEvent, chrome = BuilderChrome.forms(config.module.label))
            config.approverLoad != null -> ApproverLoadingView(state, onEvent)
            config.editing -> FormEditView(state, onEvent)
            else -> Row(Modifier.fillMaxSize()) {
                ModuleRail(config, onEvent)
                Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
                PreviewView(state, onEvent, Modifier.weight(1f))
            }
        }
    }
    FormSectionDialogs(config, onEvent)
    FormScopeDialog(state, onEvent)
    if (builder != null) ApprovalBuilderDialogs(state, onEvent)
}

// -- module rail ---------------------------------------------------------------------------

/** The web's `MODULE_SECTIONS`: the modules whose forms are configurable, under their sidebar group. */
@Composable
private fun ModuleRail(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val needle = config.moduleSearch.trim()
    val shown = FormModule.entries.filter { needle.isEmpty() || it.label.contains(needle, ignoreCase = true) }
    ZillitScrollColumn(
        modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight().background(colors.surfaceSunken),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = str(S.desktop_modules), style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = str(S.desktop_form_configuration),
                style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.textMuted,
            )
        }
        ZillitSearchField(
            value = config.moduleSearch,
            onValueChange = { onEvent(AccountHubEvent.SearchFormModules(it)) },
            placeholder = str(S.desktop_search_modules),
            modifier = Modifier.fillMaxWidth(),
        )
        if (shown.isNotEmpty()) {
            MonoLabel(
                MODULE_GROUP,
                modifier = Modifier.padding(start = ZillitTheme.spacing.xs, top = ZillitTheme.spacing.sm),
            )
        }
        shown.forEach { module ->
            ModuleCard(module, active = module == config.module) { onEvent(AccountHubEvent.OpenFormModule(module)) }
        }
        if (shown.isEmpty()) FieldHint("No modules match “$needle”.")
    }
}

@Composable
private fun ModuleCard(module: FormModule, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) colors.accent else colors.border,
                shape = ZillitTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ApprovalIconTile(
            icon = module.icon,
            tint = if (active) colors.accent else colors.textMuted,
            background = if (active) colors.accentSoft else colors.surfaceSunken,
            ring = if (active) colors.accent.copy(alpha = APPROVAL_RING_ALPHA) else colors.border,
            size = MODULE_TILE,
        )
        ZillitText(
            text = module.label,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (active) colors.accentText else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

// -- preview ----------------------------------------------------------------------------------

@Composable
private fun PreviewView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, modifier: Modifier) {
    val config = state.formConfig
    ZillitScrollColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        FormHero(config.module)
        if (!state.viewer.canEdit) {
            ZillitNotice(
                text = str(S.desktop_hub_form_configuration_is_read_only_for_you),
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }
        if (config.dirty) UnsavedBanner(config, canEdit = state.viewer.canEdit, onEvent = onEvent)
        when {
            config.loading && config.template.sections.isEmpty() -> FormSectionsSkeleton()
            config.loadFailed && config.template.sections.isEmpty() -> ZillitNotice(
                text = "Couldn't load the ${config.module.label} form.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                action = {
                    ZillitButton(
                        text = str(S.retry),
                        onClick = { onEvent(AccountHubEvent.ReloadFormTemplate) },
                        size = ButtonSize.Small,
                    )
                },
            )
            config.template.configurable.isEmpty() -> NoTemplate(config.module)
            else -> {
                DefaultFormConfigCard(state, onEvent)
                SectionsDivider(config.sectionCount, config.fieldCount)
                config.template.configurable.forEach { section ->
                    val visible = section.visible
                    // A section with nothing on the form has nothing to preview, as on the web.
                    if (visible.isEmpty()) return@forEach
                    FormSectionCard(
                        header = {
                            SectionEyebrow(section.label)
                            Box(Modifier.weight(1f))
                            FieldHint("${visible.size} field${if (visible.size == 1) "" else "s"}")
                        },
                    ) {
                        if (section.isLineItems) {
                            LineItemsTable(visible)
                        } else {
                            FieldGrid(visible, config.module)
                        }
                    }
                }
            }
        }
    }
}

/** Title, what the page does, and the module tile — the web's `Hero`, wrapping when the pane is narrow. */
@Composable
private fun FormHero(module: FormModule) {
    val colors = ZillitTheme.colors
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HeroShape)
            .background(Brush.verticalGradient(listOf(colors.surface, colors.canvas)))
            .border(1.dp, colors.border, HeroShape)
            .padding(horizontal = ZillitTheme.spacing.xl + ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xl),
    ) {
        val tile = @Composable {
            ApprovalHeroStat(
                label = "Module",
                value = module.label,
                icon = module.icon,
                content = colors.accentText,
                background = colors.accentSoft,
                ring = colors.accent.copy(alpha = APPROVAL_RING_ALPHA),
            )
        }
        if (maxWidth < HERO_SIDE_BY_SIDE) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HeroTitle(module)
                tile()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroTitle(module, Modifier.weight(1f))
                tile()
            }
        }
    }
}

@Composable
private fun HeroTitle(module: FormModule, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ApprovalIconTile(
                icon = module.icon,
                tint = colors.accent,
                background = colors.accentSoft,
                ring = colors.accent.copy(alpha = APPROVAL_RING_ALPHA),
                size = HERO_TILE,
            )
            Column {
                MonoLabel(MODULE_GROUP, color = colors.accentText)
                ZillitText(text = str(S.desktop_forms_configuration), style = ZillitTheme.typography.displayLarge)
            }
        }
        ZillitText(
            modifier = Modifier.padding(start = HERO_TILE + ZillitTheme.spacing.md),
            text = buildAnnotatedString {
                append("Configure form fields and validation rules for ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) {
                    append(module.label)
                }
                append(". Defaults apply unless a field is overridden.")
            },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

/**
 * Edits kept after leaving edit mode, said out loud with the two ways out.
 *
 * The web keeps them silently, so a preview can show a form nobody else sees;
 * this says so, because the preview otherwise looks like the saved form.
 */
@Composable
private fun UnsavedBanner(config: FormConfigState, canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.warningSoft)
            .border(1.dp, colors.warning.copy(alpha = APPROVAL_RING_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = ZillitIcons.Warning, tint = colors.warning, size = 16.dp)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = str(S.cs_exit_title),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            FieldHint(str(S.desktop_hub_the_preview_shows_your_edits_nobody_else_sees_them_until))
        }
        if (canEdit) {
            ZillitButton(
                text = str(S.ah_discard),
                onClick = { onEvent(AccountHubEvent.AskDiscardFormChanges) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !config.busy,
            )
            ZillitButton(
                text = if (config.saving) str(S.ah_saving) else str(S.dm_setup_save),
                onClick = { onEvent(AccountHubEvent.SaveFormTemplate) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Save,
                loading = config.saving,
                enabled = !config.busy,
            )
        }
    }
}

/**
 * The green summary card — "Default Form Configuration · all submitters ·
 * Default | N Custom" — with Reset, Set Approver Level and Edit.
 */
@Suppress("LongMethod") // A card, read top to bottom; the order is the reading order.
@Composable
private fun DefaultFormConfigCard(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.formConfig
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.verticalGradient(0f to colors.successSoft, GRADIENT_STOP to colors.surface))
            .border(1.dp, colors.success.copy(alpha = APPROVAL_RING_ALPHA), CardShape)
            .padding(horizontal = ZillitTheme.spacing.lg + ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        ApprovalIconTile(
            icon = ZillitIcons.Shield,
            tint = colors.success,
            background = colors.successSoft,
            ring = colors.success.copy(alpha = APPROVAL_RING_ALPHA),
            size = CARD_TILE,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(text = str(S.desktop_default_form_configuration), style = ZillitTheme.typography.titleSmall)
                ZillitText(text = str(S.desktop_all_submitters), style = APPROVAL_MONO, color = colors.textMuted)
                Pill(
                    if (config.customCount == 0) {
                        str(S.desktop_email_format_default)
                    } else {
                        "${config.customCount} Custom"
                    },
                    tone = StatusTone.Done,
                    dot = true,
                )
            }
            ZillitText(
                text = buildAnnotatedString {
                    append("Baseline fields shown for every ${config.module.label} submission. Add custom fields per ")
                    append("section in ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(str(S.edit)) }
                    append(" mode.")
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        if (state.viewer.canEdit) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitButton(
                    text = if (config.resetting) str(S.desktop_resetting) else str(S.desktop_reset_to_defaults),
                    onClick = { onEvent(AccountHubEvent.AskResetFormTemplate) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    loading = config.resetting,
                    enabled = !config.busy,
                )
                if (state.viewer.canActAsAccountant) {
                    ZillitButton(
                        text = str(S.desktop_set_approver_level),
                        onClick = { onEvent(AccountHubEvent.OpenApproverScope(true)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Shield,
                    )
                }
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(AccountHubEvent.EditForm(true)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                    enabled = !config.busy,
                )
            }
        }
    }
}

/** "SECTIONS ———— 4 · 22 fields". */
@Composable
private fun SectionsDivider(sections: Int, fields: Int) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        MonoLabel(str(S.desktop_sections))
        Box(Modifier.weight(1f).height(1.dp).background(colors.border))
        ZillitText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.textPrimary)) { append("$sections") }
                append(" · $fields fields")
            },
            style = APPROVAL_MONO,
            color = colors.textMuted,
        )
    }
}

/** The web's empty state, word for word. */
@Composable
private fun NoTemplate(module: FormModule) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxl * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = ZillitIcons.Settings, tint = colors.border, size = 40.dp)
        ZillitText(
            text = buildAnnotatedString {
                append("No form template available for ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textSecondary)) {
                    append(module.label)
                }
                append(".")
            },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
    }
}

@Composable
internal fun FormLoadingLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSpinner(size = 16.dp)
        FieldHint(text)
    }
}

internal const val MODULE_GROUP = "Transactions"

private const val GRADIENT_STOP = 0.6f
private val RAIL_WIDTH = 248.dp
private val MODULE_TILE = 32.dp
private val HERO_TILE = 38.dp
private val CARD_TILE = 34.dp
private val HERO_SIDE_BY_SIDE = 620.dp
private val HeroShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
private val CardShape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)
