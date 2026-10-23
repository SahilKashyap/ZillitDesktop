package com.zillit.desktop.feature.scriptsupervision.ui.screens.looks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.data.emptyRows
import com.zillit.desktop.feature.scriptsupervision.domain.Looks
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.lookStatusTone
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.NumberChip
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoStrip
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * One department's look board — the web's `LookBoardScreen`, mounted three
 * times (costume, hair, makeup).
 *
 * ONE screen for three menu rows, as on the web: the three departments are one
 * folder on the service told apart by `department`, and the only differences
 * on screen are which words the cards use and two department-specific blocks —
 * costume looks carry their GARMENTS, hair and make-up looks their PRODUCTS.
 *
 * The character filter is a deep link on the web (`?character=<id>`), which is
 * how a character's section opens its department here; the desktop carries the
 * same filter as the screen's own state, set when that jump happens.
 */
@Suppress("LongMethod") // Header, the character filter, the grid and three dialogs.
@Composable
internal fun LookBoardScreen(department: String, onOpen: (SsPage) -> Unit) {
    val ctx = LocalSs.current
    val navState = nav()
    val castState = cast()
    var characterId by remember(department) { mutableStateOf("") }
    var editing by remember(department) { mutableStateOf<Rec?>(null) }
    var creating by remember(department) { mutableStateOf(false) }
    var deleting by remember(department) { mutableStateOf<Rec?>(null) }
    val costume = department == "costume"
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "looks") }
    val rows = rememberRows(department, characterId) {
        crud.list(
            buildMap {
                put("department", department)
                if (characterId.isNotBlank()) put("character_id", characterId)
            },
        )
    }
    // Only costume looks have garments, so only that board fetches them.
    val garments = rememberRows(department) {
        if (costume) ContinuityCrud(ctx.api, "garments").list() else emptyRows()
    }

    SsPageHeader(
        title = t("${department}_looks"),
        actions = {
            ZillitButton(
                text = t("add_${department}_look"),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
            )
        },
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val options = castState.options
        ZillitSearchSelect(
            value = options.firstOrNull { it.first == characterId },
            options = options,
            onSelect = { characterId = it.first },
            label = { it.second },
            placeholder = t("all_characters"),
            onClear = { characterId = "" }.takeIf { characterId.isNotBlank() },
            modifier = Modifier.width(FILTER_WIDTH),
        )
    }
    Await(rows) { looks ->
        if (looks.isEmpty()) {
            ZillitEmptyState(
                title = t("no_looks"),
                message = t("no_looks_help", "label" to t("${department}_looks"), "name" to t("this_character")),
                action = {
                    ZillitButton(
                        text = t("add_${department}_look"),
                        onClick = { ctx.whenPost { creating = true } },
                        variant = ButtonVariant.Secondary,
                    )
                },
            )
        } else {
            CardGrid(looks.sortedWith(Looks.byNatural { it.str("look_number") }), minWidth = LOOK_CARD) { look ->
                LookCard(
                    look = look,
                    department = department,
                    garments = garments.value.orEmpty().filter { it.str("look_id") == look.id },
                    onOpen = {
                        navState.go(SsView.Look(look.id, look.str("character_id"), department))
                    },
                    onOpenCharacter = {
                        navState.goOn(SsPage.Characters, SsView.Character(look.str("character_id")), onOpen)
                    },
                    onEdit = { editing = look },
                    onDelete = { deleting = look },
                )
            }
        }
    }

    if (creating || editing != null) {
        LookForm(
            look = editing,
            department = department,
            presetCharacter = characterId,
            onClose = { creating = false; editing = null },
            onSaved = { rows.reload(silent = true) },
        )
    }
    deleting?.let { look ->
        DeleteDialog(
            title = t("delete_look_title"),
            name = look.str("name"),
            onClose = { deleting = null },
            onDone = { rows.reload(silent = true) },
            run = { crud.remove(look.id) },
        )
    }
}

/**
 * One look's card.
 *
 * The order is the web's, and it is the order a reader scans: what look it is,
 * where it stands, whose it is, what it looks like, where it appears, and only
 * then the department's own detail.
 */
@Composable
private fun LookCard(
    look: Rec,
    department: String,
    garments: List<Rec>,
    onOpen: () -> Unit,
    onOpenCharacter: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sceneState = scenes()
    val castState = cast()
    val status = look.str("status").ifBlank { "planned" }
    // Only the days and scenes this production still HAS: a look keeps the id
    // of a scene somebody deleted, and a pill for it would open nothing.
    val days = look.strings("story_day_ids").mapNotNull { id -> castState.storyDays.firstOrNull { it.id == id } }
    val lookScenes = look.strings("scene_ids").mapNotNull { id -> sceneState.scenes.firstOrNull { it.id == id } }

    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        action = { RowActions(onEdit = onEdit, onDelete = onDelete) },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberChip(
                caption = t("dept_$department"),
                value = look.str("look_number").takeIf { it.isNotBlank() }?.let { "#$it" } ?: "—",
            )
            ZillitText(
                text = look.str("name").ifBlank { t("unnamed") },
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f).clickable(onClick = onOpen),
            )
            SsTag(t("look_status_$status"), lookStatusTone(status))
        }
        Field(t("description"), look.str("description"))

        LookLinks(look, onOpenCharacter)

        PhotoStrip(look.strings("reference_image_urls"))

        if (days.isNotEmpty()) {
            PillRow(t("story_days_colon")) { days.forEach { SsTag(it.str("label")) } }
        }
        if (lookScenes.isNotEmpty()) {
            PillRow(t("scenes_colon")) {
                lookScenes.forEach { SsTag("${t("sc")} ${it.str("scene_number")}") }
            }
        }

        LookDepartmentBlock(look, department, garments)
    }
}

/** Whose look it is, and whether it carries on from another one. */
@Composable
private fun LookLinks(look: Rec, onOpenCharacter: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        look.str("character_name").takeIf { it.isNotBlank() }?.let { name ->
            SsTag(
                label = name,
                modifier = if (look.str("character_id").isNotBlank()) {
                    Modifier.clickable(onClick = onOpenCharacter)
                } else {
                    Modifier
                },
            )
        }
        // This look carries on from another one, so continuity matches it
        // rather than building it again.
        if (look.str("continues_from_look_id").isNotBlank()) SsTag(t("continues"), StatusTone.Progress)
    }
}

/**
 * What the department adds to the card: hair and make-up looks are PRODUCTS,
 * costume looks are GARMENTS.
 *
 * The note-or-state line is in here too, because it reads as part of the same
 * block: a continuity note is a warning and the web shows it INSTEAD of the
 * wear state when there is one.
 */
@Composable
private fun LookDepartmentBlock(look: Rec, department: String, garments: List<Rec>) {
    if (department != "costume") {
        val products = look.recs("products")
        if (products.isNotEmpty()) {
            MutedText(t("products"))
            products.forEach { product ->
                MutedText(
                    "• " + listOf(product.str("name"), product.str("brand"), product.str("shade"))
                        .filter { it.isNotBlank() }
                        .joinToString(" — "),
                )
            }
        }
    }
    look.str("continuity_notes").takeIf { it.isNotBlank() }?.let { MutedText("⚠ $it") }
        ?: look.str("wear_state").takeIf { it.isNotBlank() }?.let { MutedText(it) }

    if (department == "costume") {
        ZillitDivider()
        MutedText("${t("garments")} · ${garments.size}")
        garments.forEach { garment ->
            MutedText("• ${garment.str("name")} — ${garmentMeta(garment)}")
        }
    }
}

/** A garment in one line: what it is, its size, and its condition when it is not new. */
@Composable
private fun garmentMeta(garment: Rec): String = listOf(
    t("garment_category_${garment.str("category").ifBlank { "other" }}"),
    garment.str("size"),
    // "new" is the default and says nothing worth a word.
    garment.str("condition").takeIf { it.isNotBlank() && it != "new" }
        ?.let { t("garment_condition_$it") }
        .orEmpty(),
).filter { it.isNotBlank() }.joinToString(", ")

/** A caption and the pills under it — the web's "Story days:" / "Scenes:" rows. */
@Composable
private fun PillRow(caption: String, pills: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MutedText(caption)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            modifier = Modifier.weight(1f),
        ) { pills() }
    }
}

private val FILTER_WIDTH = 260.dp
private val LOOK_CARD = 340.dp
