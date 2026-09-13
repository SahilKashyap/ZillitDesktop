package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BudgetFigures
import com.zillit.desktop.feature.accounthub.domain.BudgetImports
import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.domain.ParsedCode
import com.zillit.desktop.feature.accounthub.domain.SetupUpload
import com.zillit.desktop.feature.accounthub.domain.VersionHint
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.BudgetImportState
import com.zillit.desktop.feature.accounthub.ui.ImportStep
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel

/**
 * Importing a budget file — the web's `ImportBudgetWizard`.
 *
 * Upload, Preview, Commit, with the step strip across the top. The middle
 * step is the reason the other two exist: the parse is a guess at somebody
 * else's spreadsheet, and committing it writes codes into the chart of
 * accounts that every other tool codes against. So the preview shows the
 * counts, the file it read, every warning, the version it will become, what
 * happens to the chart, and the whole extracted structure — before anything
 * is written. Errors stay inside the wizard, next to the button that failed.
 */
@Composable
internal fun BudgetImportDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val import = state.budget.import
    ZillitDialogShell(
        title = "Import Budget",
        visible = import.open,
        // A commit in flight is not abandoned by a stray click outside.
        onDismiss = { if (!import.committing) onEvent(AccountHubEvent.CloseBudgetImport) },
        icon = ZillitIcons.Upload,
        width = WIZARD_WIDTH,
        scrollable = import.step == ImportStep.Preview,
        actions = { WizardActions(import, onEvent) },
    ) {
        StepStrip(import.step)
        when (import.step) {
            ImportStep.Upload -> UploadStep(import, onEvent)
            ImportStep.Preview -> PreviewStep(state, onEvent)
            ImportStep.Done -> DoneStep(import)
        }
    }
}

/** Back, Cancel and the step's own button — the web's footer. */
@Composable
private fun WizardActions(import: BudgetImportState, onEvent: (AccountHubEvent) -> Unit) {
    val cancel = @Composable { enabled: Boolean ->
        ZillitButton(
            text = "Cancel",
            onClick = { onEvent(AccountHubEvent.CloseBudgetImport) },
            variant = ButtonVariant.Secondary,
            enabled = enabled,
        )
    }
    when (import.step) {
        ImportStep.Upload -> {
            cancel(true)
            ZillitButton(
                text = if (import.uploading) "Parsing…" else "Parse file",
                onClick = { onEvent(AccountHubEvent.ParseBudgetFile) },
                loading = import.uploading,
                enabled = import.canParse,
            )
        }
        ImportStep.Preview -> {
            ZillitButton(
                text = "Back",
                onClick = { onEvent(AccountHubEvent.BackToBudgetUpload) },
                variant = ButtonVariant.Secondary,
                enabled = !import.committing,
            )
            cancel(!import.committing)
            ZillitButton(
                text = if (import.committing) "Importing…" else "Import",
                onClick = { onEvent(AccountHubEvent.CommitBudgetImport) },
                loading = import.committing,
                enabled = import.canCommit,
            )
        }
        ImportStep.Done -> ZillitButton(
            text = "Done",
            onClick = { onEvent(AccountHubEvent.CloseBudgetImport) },
        )
    }
}

/** ① Upload · ② Preview · ③ Commit — done steps ticked, the current one in the accent. */
@Composable
private fun StepStrip(current: ImportStep) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ImportStep.entries.forEachIndexed { index, step ->
                val done = index < current.ordinal
                val active = step == current
                Box(
                    modifier = Modifier
                        .size(STEP_DOT)
                        .clip(CircleShape)
                        .background(
                            when {
                                active -> colors.accent
                                done -> colors.success
                                else -> colors.surfaceSunken
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        ZillitIcon(icon = ZillitIcons.Check, tint = Color.White, size = STEP_TICK)
                    } else {
                        ZillitText(
                            text = "${index + 1}",
                            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (active) colors.textOnAccent else colors.textMuted,
                        )
                    }
                }
                ZillitText(
                    text = step.label,
                    style = ZillitTheme.typography.label.copy(
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = when {
                        active -> colors.textPrimary
                        done -> colors.success
                        else -> colors.textMuted
                    },
                )
                if (index < ImportStep.entries.lastIndex) {
                    ZillitText(text = "·", style = ZillitTheme.typography.label, color = colors.textMuted)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

// -- step 1: upload -------------------------------------------------------------

@Composable
private fun UploadStep(import: BudgetImportState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = "Upload a budget file", style = ZillitTheme.typography.titleSmall)
            ZillitText(
                text = (if (import.acceptsDrops) "Drag in" else "Choose") + " a PDF, Excel (.xlsx/.xls), or CSV. " +
                    "The file is parsed automatically to extract Chart of Accounts codes and budget amounts; " +
                    "you review the result on the next screen before anything is saved.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        DropZone(import, onEvent)
        if (import.uploading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSpinner(size = SMALL_SPINNER)
                FieldHint("Parsing your budget — this typically takes 10–30 seconds for a ~50-page budget.")
            }
        }
        import.parseError?.let { reason ->
            ErrorBanner(
                title = null,
                message = reason,
                // A refused pick has nothing to retry; a failed parse does.
                onRetry = if (import.picked != null) {
                    { onEvent(AccountHubEvent.ParseBudgetFile) }
                } else {
                    null
                },
            )
        }
    }
}

/** The dashed zone: click to browse, or drop a file on it — the chosen file shown inside. */
@Composable
private fun DropZone(import: BudgetImportState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var hovering by remember { mutableStateOf(false) }
    val picked = import.picked
    val lit = hovering || picked != null
    val edge = if (lit) colors.accent else colors.borderStrong
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (lit) colors.accentSoft else colors.surfaceSunken)
            .drawBehind {
                drawRoundRect(
                    color = edge,
                    style = Stroke(
                        width = DASH_WIDTH.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP)),
                    ),
                    cornerRadius = CornerRadius(ZONE_RADIUS.toPx()),
                )
            }
            .then(
                if (import.acceptsDrops) {
                    Modifier.budgetFileDrop(
                        enabled = !import.uploading,
                        maxBytes = SetupUpload.BudgetImport.maxBytes,
                        onHover = { hovering = it },
                        onFile = { name, bytes -> onEvent(AccountHubEvent.DropBudgetFile(name, bytes)) },
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !import.uploading, onClickLabel = "Choose a budget file") {
                onEvent(AccountHubEvent.PickBudgetFile)
            }
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZONE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        DropZoneContent(import, hovering)
    }
}

/** The chosen file's name, size and kind — or how to choose one. */
@Composable
private fun DropZoneContent(import: BudgetImportState, hovering: Boolean) {
    val colors = ZillitTheme.colors
    val picked = import.picked
    if (picked != null) {
        ZillitIcon(icon = ZillitIcons.File, tint = colors.accent, size = ZONE_ICON)
        ZillitText(
            text = picked.name,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FieldHint("${kilobytes(picked.bytes)} KB · ${kindOf(picked.name)}")
        FieldHint("Click to pick a different file")
    } else {
        ZillitIcon(
            icon = ZillitIcons.Upload,
            tint = if (hovering) colors.accent else colors.textMuted,
            size = ZONE_ICON,
        )
        ZillitText(
            text = if (import.acceptsDrops) "Drop file here, or click to browse" else "Click to browse",
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        FieldHint("PDF · XLSX · XLS · CSV — up to 20 MB")
    }
}

// -- step 2: preview ------------------------------------------------------------

@Composable
private fun PreviewStep(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val import = state.budget.import
    val parsed = import.parsed ?: return
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        ParsedStats(parsed)
        SourceLine(import)
        if (parsed.warnings.isNotEmpty()) Warnings(parsed.warnings)
        if (parsed.isEmpty) {
            ZillitNotice(
                text = "Nothing was found to import. That usually means the file is laid out in a way the " +
                    "parser does not recognise.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }
        VersionFields(state, onEvent)
        ChartMode(import, onEvent)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(text = "Extracted structure", style = ZillitTheme.typography.titleSmall)
            StructurePreview(parsed)
        }
        import.commitError?.let { ErrorBanner(title = "Import failed", message = it, onRetry = null) }
    }
}

/** Sections, headers, nominals, uncoded, and the total — the web's stats strip. */
@Composable
private fun ParsedStats(parsed: ParsedBudget) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        ZillitStatTile(label = "Sections", value = "${parsed.sections.size}", modifier = tile)
        ZillitStatTile(label = "Headers", value = "${parsed.headers.size}", modifier = tile)
        ZillitStatTile(label = "Nominals", value = "${parsed.nominals.size}", modifier = tile)
        ZillitStatTile(
            label = "Uncoded",
            value = "${parsed.uncoded.size}",
            tone = if (parsed.uncoded.isEmpty()) null else StatusTone.Pending,
            modifier = tile,
        )
        ZillitStatTile(
            label = parsed.currency.ifBlank { "Total" },
            value = BudgetFigures.money(parsed.total, Money.symbol(parsed.currency)),
            modifier = Modifier.weight(TOTAL_TILE_WEIGHT).fillMaxHeight(),
        )
    }
}

/** "Parsed from budget.xlsx · template hint: … · format: xlsx". */
@Composable
private fun SourceLine(import: BudgetImportState) {
    val colors = ZillitTheme.colors
    val upload = import.upload
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.File, tint = colors.textMuted, size = SMALL_ICON)
        ZillitText(
            text = buildAnnotatedString {
                append("Parsed from ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) {
                    append(upload?.fileName ?: import.picked?.name.orEmpty())
                }
                upload?.sourceTemplate?.takeIf { it.isNotBlank() }?.let { append(" · template hint: $it") }
                upload?.detectedFormat?.takeIf { it.isNotBlank() }?.let {
                    append(" · format: ")
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(it) }
                }
            },
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

/** The server's warnings, folded until asked for — the web's `<details>`. */
@Composable
private fun Warnings(warnings: List<String>) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.warningSoft)
            .border(1.dp, colors.warning.copy(alpha = WARNING_RING), ZillitTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Hide warnings" else "Show warnings") { open = !open }
                .padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.Warning, tint = colors.warning, size = SMALL_ICON)
            ZillitText(
                text = "${warnings.size} warning${if (warnings.size == 1) "" else "s"} — " +
                    if (open) "click to hide" else "click to review",
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.warning,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(
                icon = if (open) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                tint = colors.warning,
                size = SMALL_ICON,
            )
        }
        if (open) {
            Column(
                modifier = Modifier.padding(
                    start = ZillitTheme.spacing.md,
                    end = ZillitTheme.spacing.md,
                    bottom = ZillitTheme.spacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                warnings.forEach {
                    ZillitText(text = "· $it", style = ZillitTheme.typography.bodySmall, color = colors.warning)
                }
            }
        }
    }
}

/** Version, Label and Description, with the web's guidance under Version. */
@Composable
private fun VersionFields(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val import = state.budget.import
    val meta = import.meta
    val existing = import.existing ?: state.budget.versions
    val hint = BudgetImports.versionHint(existing, meta)
    val edit = { next: com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta ->
        onEvent(AccountHubEvent.EditBudgetImportMeta(next))
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(text = "New budget version", style = ZillitTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel("Version", required = true)
                ZillitTextField(
                    value = meta.version,
                    onValueChange = { edit(meta.copy(version = it)) },
                    placeholder = "v1",
                    enabled = !import.committing,
                    modifier = Modifier.fillMaxWidth(),
                )
                VersionGuidance(hint)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel("Label", required = true)
                ZillitTextField(
                    value = meta.label,
                    onValueChange = { edit(meta.copy(label = it)) },
                    placeholder = "Original Greenlight — Sep 2024",
                    enabled = !import.committing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel("Description")
            ZillitTextField(
                value = meta.description,
                onValueChange = { edit(meta.copy(description = it)) },
                placeholder = "Approved by board on…",
                enabled = !import.committing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Where the suggested number came from, or why the typed one will be refused.
 * Versions are unique per production, so "v4" for a brand-new budget reads
 * as a bug unless the field says so.
 */
@Composable
private fun VersionGuidance(hint: VersionHint) {
    val colors = ZillitTheme.colors
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val text = buildAnnotatedString {
        when (hint) {
            is VersionHint.Taken -> {
                withStyle(bold) { append(hint.version) }
                append(" already exists")
                if (hint.by.name.isNotBlank()) append(" — \"${hint.by.name}\"")
                append(" (${hint.by.status.wire}). Importing will be rejected unless you change it. Next free: ")
                withStyle(bold) { append(hint.nextFree) }
                append(".")
            }
            is VersionHint.Free -> {
                when {
                    hint.latest != null -> {
                        append("Next free version in this project (latest: ")
                        withStyle(bold) { append(hint.latest) }
                        append(").")
                    }
                    hint.hasVersions -> append("Next free version in this project.")
                    else -> append("First budget version in this project.")
                }
                hint.continues?.let {
                    append(" Continues “${it.name.trim()}” — its latest is ")
                    withStyle(bold) { append(it.version) }
                    append(".")
                }
            }
        }
    }
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = if (hint is VersionHint.Taken) colors.warning else colors.textMuted,
    )
}

/** Append or Override — what the import does to the chart of accounts. */
@Composable
private fun ChartMode(import: BudgetImportState, onEvent: (AccountHubEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(text = "Chart of accounts", style = ZillitTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            CoaImportMode.entries.forEach { mode ->
                ModeCard(
                    mode = mode,
                    active = mode == import.mode,
                    enabled = !import.committing,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onEvent(AccountHubEvent.SetCoaImportMode(mode)) }
            }
        }
        if (import.mode == CoaImportMode.Override) {
            ZillitNotice(
                text = "Existing chart codes not in this budget will be deactivated (hidden) — they aren't " +
                    "deleted, so anything already referencing them stays intact.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }
    }
}

@Composable
private fun ModeCard(
    mode: CoaImportMode,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (active) colors.accentSoft else colors.surface)
            .border(
                if (active) ACTIVE_CARD_RING else 1.dp,
                if (active) colors.accent else colors.border,
                ZillitTheme.shapes.medium,
            )
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(RADIO)
                    .clip(CircleShape)
                    .border(
                        if (active) RADIO_ACTIVE_RING else RADIO_RING,
                        if (active) colors.accent else colors.borderStrong,
                        CircleShape,
                    ),
            )
            ZillitText(
                text = mode.label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (mode == CoaImportMode.Default) FieldHint("(default)")
        }
        ZillitText(text = mode.detail, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
    }
}

/** Sections, their headers, the nominals under each, and what still awaits a code. */
@Suppress("LongMethod") // A tree, read top to bottom; the order is the reading order.
@Composable
private fun StructurePreview(parsed: ParsedBudget) {
    val colors = ZillitTheme.colors
    val symbol = Money.symbol(parsed.currency)
    val headersBySection = remember(parsed) { parsed.headers.groupBy { it.sectionId } }
    val nominalsByHeader = remember(parsed) { parsed.nominals.groupBy { it.parentCode } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .background(colors.surface),
    ) {
        parsed.sections.forEachIndexed { index, section ->
            val headers = headersBySection[section.id].orEmpty()
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceSunken)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = section.name.uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (section.id.isNotBlank()) {
                    ZillitText(text = "[${section.id}]", style = PREVIEW_MONO, color = colors.textMuted)
                }
                Spacer(Modifier.weight(1f))
                ZillitText(
                    text = BudgetFigures.money(headers.sumOf { it.amount }, symbol),
                    style = PREVIEW_MONO,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            headers.forEach { header ->
                CodeRow(header, symbol, nominal = false)
                nominalsByHeader[header.code].orEmpty().forEach { CodeRow(it, symbol, nominal = true) }
            }
        }
        if (parsed.uncoded.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().background(colors.warningSoft)) {
                Row(
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = "AWAITING CODE",
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.warning,
                    )
                    ZillitText(
                        text = "Accountant assigns codes after import",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.warning,
                    )
                }
                parsed.uncoded.forEach { line ->
                    PreviewRow(
                        code = "—",
                        name = line.name.ifBlank { "—" },
                        amount = BudgetFigures.money(line.amount, symbol),
                        tint = colors.warning,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeRow(code: ParsedCode, symbol: String, nominal: Boolean) {
    PreviewRow(
        code = code.code,
        name = code.name,
        amount = BudgetFigures.money(code.amount, symbol),
        tint = null,
        nominal = nominal,
    )
}

@Composable
private fun PreviewRow(code: String, name: String, amount: String, tint: Color?, nominal: Boolean = false) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (nominal) NOMINAL_INDENT else ZillitTheme.spacing.md,
                end = ZillitTheme.spacing.md,
                top = if (nominal) ZillitTheme.spacing.xxs else ZillitTheme.spacing.xs,
                bottom = if (nominal) ZillitTheme.spacing.xxs else ZillitTheme.spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = code,
            style = PREVIEW_MONO,
            color = tint ?: colors.textMuted,
            maxLines = 1,
            modifier = Modifier.width(PREVIEW_CODE),
        )
        ZillitText(
            text = name,
            style = ZillitTheme.typography.bodySmall.copy(
                fontWeight = if (nominal) FontWeight.Normal else FontWeight.SemiBold,
            ),
            color = tint ?: if (nominal) colors.textSecondary else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = amount,
            style = PREVIEW_MONO,
            color = tint ?: if (nominal) colors.textMuted else colors.textSecondary,
            maxLines = 1,
        )
    }
}

// -- step 3: commit -------------------------------------------------------------

@Composable
private fun DoneStep(import: BudgetImportState) {
    val colors = ZillitTheme.colors
    val meta = import.meta
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier.size(DONE_TILE).clip(CircleShape).background(colors.successSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Check, tint = colors.success, size = DONE_ICON)
        }
        ZillitText(
            text = "Budget imported",
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        ZillitText(
            text = buildAnnotatedString {
                val mono = SpanStyle(fontFamily = FontFamily.Monospace)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.textPrimary)) {
                    append(meta.label.trim().ifBlank { import.created?.name.orEmpty() })
                }
                val version = meta.version.trim().ifBlank { import.created?.version.orEmpty() }
                append(" (version $version) is now in your ")
                append("project as a ")
                withStyle(mono) { append("DRAFT") }
                append(" budget. Promote it to ")
                withStyle(mono) { append("LIVE") }
                append(" from the Budgets tab when ready.")
            },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = DONE_TEXT_WIDTH),
        )
    }
}

/** A red strip with the reason — and a Retry where retrying means something. */
@Composable
private fun ErrorBanner(title: String?, message: String, onRetry: (() -> Unit)?) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.dangerSoft)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Warning, tint = colors.danger, size = BANNER_ICON)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            title?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.danger,
                )
            }
            ZillitText(text = message, style = ZillitTheme.typography.bodySmall, color = colors.danger)
        }
        onRetry?.let {
            ZillitButton(text = "Retry", onClick = it, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
        }
    }
}

private fun kilobytes(bytes: Long): String {
    val tenths = (bytes * TENTHS + KILOBYTE / 2) / KILOBYTE
    return "${tenths / TENTHS}.${tenths % TENTHS}"
}

/** What a picked file is, in words rather than a MIME type. */
private fun kindOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "PDF document"
    "xlsx", "xls" -> "Excel workbook"
    "csv" -> "CSV file"
    else -> "Unknown type"
}

private val PREVIEW_MONO
    @Composable get() = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

private const val TOTAL_TILE_WEIGHT = 1.6f
private const val WARNING_RING = 0.35f
private const val DASH = 10f
private const val GAP = 7f
private const val TENTHS = 10L
private const val KILOBYTE = 1024L
private val WIZARD_WIDTH = 780.dp
private val STEP_DOT = 22.dp
private val STEP_TICK = 11.dp
private val SMALL_ICON = 13.dp
private val SMALL_SPINNER = 14.dp
private val BANNER_ICON = 16.dp
private val ZONE_ICON = 32.dp
private val ZONE_PADDING = 36.dp
private val ZONE_RADIUS = 12.dp
private val DASH_WIDTH = 1.5.dp
private val RADIO = 14.dp
private val RADIO_ACTIVE_RING = 4.5.dp
private val RADIO_RING = 1.5.dp
private val ACTIVE_CARD_RING = 1.5.dp
private val PREVIEW_CODE = 72.dp
private val NOMINAL_INDENT = 40.dp
private val DONE_TILE = 56.dp
private val DONE_ICON = 28.dp
private val DONE_TEXT_WIDTH = 440.dp
