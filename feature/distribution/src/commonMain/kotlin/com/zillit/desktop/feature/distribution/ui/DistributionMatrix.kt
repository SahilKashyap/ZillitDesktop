package com.zillit.desktop.feature.distribution.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.distribution.domain.DISTRIBUTION_PAGE_SIZES
import com.zillit.desktop.feature.distribution.domain.DistributionColumn
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import com.zillit.desktop.feature.distribution.domain.displayName
import com.zillit.desktop.feature.distribution.domain.subtitle

/**
 * The matrix: a frozen column of people on the left, a unit or tool per
 * column across, one checkbox per cell. The columns scroll sideways under a
 * header that scrolls with them, as the web's `scroll={{ x }}` table does,
 * because a production runs more units than a window is wide.
 */
@Composable
internal fun DistributionMatrix(
    state: DistributionUiState,
    copy: DistributionCopy,
    faces: suspend (String) -> ImageBitmap?,
    onEvent: (DistributionEvent) -> Unit,
) {
    val columns = remember(state.users, state.section, state.unitFilter, copy) { state.visibleColumns(copy::label) }
    val page = state.currentPage
    val across = rememberScrollState()
    val down = rememberLazyListState()

    // A new page starts at its top; the sideways position is kept, so paging
    // through the crew does not lose the column you were ticking.
    LaunchedEffect(page.page, state.pageSize) { down.scrollToItem(0) }

    Column(Modifier.fillMaxSize().padding(start = PAGE_PADDING, end = PAGE_PADDING, bottom = ZillitTheme.spacing.md)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.surface),
        ) {
            MatrixHeader(columns, copy, across)
            if (columns.isEmpty()) {
                NoColumns(state)
            } else {
                ZillitLazyColumn(state = down, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(page.rows, key = { _, row -> row.userId }) { index, row ->
                        PersonRow(row, index, columns, state, copy, faces, across, onEvent)
                    }
                }
            }
        }
        Footer(state, onEvent)
    }
}

/** The card's top band: "USER" on the frozen side, a unit per column after. */
@Composable
private fun MatrixHeader(columns: List<DistributionColumn>, copy: DistributionCopy, across: ScrollState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = CARD_PADDING)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(PERSON_WIDTH).height(HEADER_HEIGHT), Alignment.CenterStart) {
            ZillitText(
                text = copy.user.uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        FreezeLine()
        Row(Modifier.horizontalScroll(across)) {
            columns.forEach { column ->
                val name = copy.label(column.unitName)
                ZillitTooltip(name) {
                    Box(Modifier.width(CELL_WIDTH).height(HEADER_HEIGHT), Alignment.Center) {
                        ZillitText(
                            text = name,
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
                ColumnLine()
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
}

/** A section with nothing in it — every unit filtered out, or none on the wire. */
@Composable
private fun NoColumns(state: DistributionUiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = if (state.unitFilter.isEmpty()) {
                str(S.desktop_dist_no_section_distributions_yet, state.section.label.lowercase())
            } else {
                str(S.desktop_dist_every_column_filtered_out)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** The frozen column's edge — what says "this side stays put". */
@Composable
private fun FreezeLine() {
    Box(
        Modifier
            .padding(end = ZillitTheme.spacing.sm)
            .width(HAIRLINE)
            .fillMaxHeight()
            .background(ZillitTheme.colors.border),
    )
}

/** The faint rule between columns, so a wide grid still reads as one. */
@Composable
private fun ColumnLine() {
    Box(
        Modifier
            .width(HAIRLINE)
            .fillMaxHeight()
            .padding(vertical = ZillitTheme.spacing.sm)
            .background(ZillitTheme.colors.border.copy(alpha = COLUMN_LINE_ALPHA)),
    )
}

@Composable
private fun PersonRow(
    row: DistributionUser,
    index: Int,
    columns: List<DistributionColumn>,
    state: DistributionUiState,
    copy: DistributionCopy,
    faces: suspend (String) -> ImageBitmap?,
    across: ScrollState,
    onEvent: (DistributionEvent) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    hovered -> ZillitTheme.colors.surfaceHover
                    index % 2 == 1 -> ZillitTheme.colors.surfaceSunken.copy(alpha = ZEBRA_ALPHA)
                    else -> ZillitTheme.colors.surface
                },
            )
            .hoverable(interaction)
            .padding(horizontal = CARD_PADDING)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonCell(row, state, copy, faces)
        FreezeLine()
        Row(Modifier.horizontalScroll(across)) {
            columns.forEach { column ->
                val cell = row.cell(column.unitId, state.section)
                Cell(
                    checked = cell?.toEnabled == true,
                    present = cell != null,
                    busy = state.isBusy(row.userId, column.unitId),
                    enabled = cell?.toUpdatable != false && !state.isHeld(row.userId, column.unitId),
                    onToggle = { onEvent(DistributionEvent.Toggle(row.userId, column.unitId, it)) },
                    modifier = Modifier.testTag(cellTag(row.userId, column.unitId)),
                )
                ColumnLine()
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(HAIRLINE)
            .background(ZillitTheme.colors.border.copy(alpha = COLUMN_LINE_ALPHA)),
    )
}

/** The frozen half of a row: the face, the name with its Outsider tag, the designation. */
@Composable
private fun PersonCell(
    row: DistributionUser,
    state: DistributionUiState,
    copy: DistributionCopy,
    faces: suspend (String) -> ImageBitmap?,
) {
    val person = state.people[row.userId]
    val name = row.displayName(person)
    val external = row.isExternal || person?.isExternal == true
    val face by produceState<ImageBitmap?>(null, row.userId) { value = faces(row.userId) }

    Row(
        modifier = Modifier.width(PERSON_WIDTH).padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, size = PERSON_AVATAR, image = face, userId = row.userId)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = name,
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (external) ZillitTag(copy.outsider, tone = TagTone.Success)
            }
            ZillitText(
                text = row.subtitle(person, copy::label, copy.noDetails),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * One cell. A unit the server did not send for this person renders as a dash
 * rather than an unchecked box that invites a click going nowhere; a cell
 * with a save in flight shows the spinner where the box was.
 */
@Composable
private fun Cell(
    checked: Boolean,
    present: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.width(CELL_WIDTH).padding(vertical = ZillitTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !present -> ZillitText(
                text = "—",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            busy -> ZillitSpinner(size = SPINNER_SIZE)
            else -> ZillitCheckbox(checked = checked, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

/** The semantics tag a cell carries — for the render tests, which have no text to find a box by. */
internal fun cellTag(userId: String, unitId: String): String = "distribution-cell:$userId:$unitId"

/** "1–10 of 43", the page size, and the two steps between pages — the web's pager. */
@Composable
private fun Footer(state: DistributionUiState, onEvent: (DistributionEvent) -> Unit) {
    val page = state.currentPage
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = str(S.desktop_range_of_total, page.firstIndex, page.lastIndex, page.total),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        Spacer(Modifier.weight(1f))
        ZillitText(
            text = str(S.desktop_rows_per_page),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitSelect(
            value = state.pageSize,
            options = DISTRIBUTION_PAGE_SIZES,
            onSelect = { onEvent(DistributionEvent.PageSize(it)) },
            label = { it.toString() },
            modifier = Modifier.width(PAGE_SIZE_WIDTH),
        )
        ZillitText(
            text = str(S.docusign_page_of, page.page, page.pageCount),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitButton(
            text = str(S.docusign_tour_prev),
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ChevronLeft,
            enabled = page.canGoBack,
            onClick = { onEvent(DistributionEvent.GoToPage(page.page - 1)) },
        )
        ZillitButton(
            text = str(S.next),
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            trailingIcon = ZillitIcons.ChevronRight,
            enabled = page.canGoForward,
            onClick = { onEvent(DistributionEvent.GoToPage(page.page + 1)) },
        )
    }
}

private val CARD_PADDING = 16.dp
private val PERSON_WIDTH = 260.dp
private val PERSON_AVATAR = 36.dp
private val CELL_WIDTH = 160.dp
private val HEADER_HEIGHT = 56.dp
private val SPINNER_SIZE = 18.dp
private val PAGE_SIZE_WIDTH = 88.dp
private const val ZEBRA_ALPHA = 0.4f
private const val COLUMN_LINE_ALPHA = 0.5f
