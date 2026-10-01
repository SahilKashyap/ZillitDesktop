package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitQrCode
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.AutoFillGrid
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.FilterSelect
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.Page
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.Resource
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.enumOptions
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.rememberRows
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LABEL_PAGE_SIZE = 200
private const val SEARCH_DEBOUNCE_MS = 250L
private val QR_PREVIEW = 84.dp

/** The costume page for the filters, searched after the typing settles. */
@Composable
private fun rememberLabelRows(q: String, characterId: String, status: String): Resource<List<Rec>> {
    var debouncedQ by remember { mutableStateOf("") }
    LaunchedEffect(q) {
        delay(SEARCH_DEBOUNCE_MS)
        debouncedQ = q
    }
    return rememberRows(debouncedQ, characterId, status) {
        api.get(
            "/costumes",
            mapOf(
                "pageSize" to LABEL_PAGE_SIZE,
                "q" to debouncedQ,
                "characterId" to characterId,
                "status" to status,
                "includeRetired" to true,
            ),
        )
    }
}

/**
 * QR labels: pick costumes, then print garment tags and wrap-box labels. Each label carries the
 * asset number, description, source and character. `?ids=a,b` arrives preselected (from a costume
 * page), and `&print=1` prints straight away. The QR is drawn here from the asset number (what the
 * service's own `qr.png` carries); Print writes a label sheet and opens it in the browser, which
 * shows its print dialog.
 */
@Composable
fun LabelsScreen() {
    val ctx = LocalSync.current
    val route = ctx.nav.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var characterId by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(route.arg("ids").split(',').filter { it.isNotBlank() }.toSet()) }
    // Every costume seen, so a selection survives a narrower search.
    var known by remember { mutableStateOf(emptyMap<String, Rec>()) }
    val characters = rememberResource { api.get("/characters").mapRows() }
    val items = rememberLabelRows(q, characterId, status)
    val listed = items.value.orEmpty()
    LaunchedEffect(listed) { known = known + listed.associateBy { it.id } }
    // A costume page's pre-selection may sit beyond the first 200: fetch what the list did not carry.
    LaunchedEffect(selected.size) {
        selected.filter { it !in known }.forEach { id ->
            (ctx.api.get("/costumes/$id") as? ZillitResult.Success)?.data?.rec?.let { known = known + (it.id to it) }
        }
    }
    val printable = selected.mapNotNull { known[it] }
    val print: () -> Unit = {
        ctx.whenDownload {
            scope.launch {
                ctx.host.open("qr-labels.html", labelSheetHtml(t("csync_qr_labels"), printable).encodeToByteArray())
            }
        }
    }
    AutoPrintOnArrival(printable.size, selected.size, print)

    Page {
        LabelsHeader(printable.size, print)
        LabelFilters(
            q,
            { q = it },
            characterId,
            { characterId = it },
            status,
            { status = it },
            characters.value.orEmpty(),
            listed.size,
            onSelectAll = { selected = selected + listed.map { it.id } },
            onClear = { selected = emptySet() },
        )
        LabelPickList(items, selected) { id, on -> selected = if (on) selected + id else selected - id }
        ZillitText(
            t("csync_preview_n", "n" to printable.size),
            style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        )
        // `.csync-labelsheet`: auto-fill columns of at least 200, 12 apart.
        AutoFillGrid(printable.size, 200.dp, 12.dp, stretch = false) { i, cell -> LabelPreview(printable[i], cell) }
    }
}

/** Arriving with ?print=1: print once the labels are known, and only with download rights. */
@Composable
private fun AutoPrintOnArrival(printableCount: Int, selectedCount: Int, print: () -> Unit) {
    val ctx = LocalSync.current
    val route = ctx.nav.current
    var printedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(printableCount, ctx.canDownload) {
        val arrivedToPrint = route.arg("print") == "1" && !printedOnce
        val labelsKnown = printableCount > 0 && printableCount == selectedCount
        if (ctx.canDownload && arrivedToPrint && labelsKnown) {
            printedOnce = true
            print()
        }
    }
}

@Composable
private fun LabelsHeader(printableCount: Int, print: () -> Unit) {
    PageHead(
        title = t("csync_qr_labels"),
        sub = t("csync_qr_labels_sub"),
        actions = {
            val label = if (printableCount == 1) t("csync_print_one_label") else t(
                "csync_print_n_labels",
                "n" to printableCount,
            )
            ZillitButton(label, onClick = print, enabled = printableCount > 0, leadingIcon = ZillitIcons.Print)
        },
        bottomPadding = 0.dp,
    )
}

@Composable
private fun LabelFilters(
    q: String,
    onQ: (String) -> Unit,
    characterId: String,
    onCharacter: (String) -> Unit,
    status: String,
    onStatus: (String) -> Unit,
    characters: List<Rec>,
    listedCount: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    val ctx = LocalSync.current
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SearchWithButton(q, onQ, t("csync_search"), Modifier.width(340.dp))
        FilterSelect(
            characterId,
            characters.map { it.id to it.str("name") },
            t("csync_any_character"),
            onCharacter,
            Modifier.width(170.dp),
        )
        FilterSelect(
            status,
            enumOptions(ctx.metaList("costume_statuses")),
            t("csync_any_status"),
            onStatus,
            Modifier.width(150.dp),
        )
        ZillitButton(
            t("csync_select_all_n", "n" to listedCount),
            onClick = onSelectAll,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            t("csync_clear"),
            onClick = onClear,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
    }
}

@Composable
private fun LabelPickList(items: Resource<List<Rec>>, selected: Set<String>, onToggle: (String, Boolean) -> Unit) {
    SectionCard(Modifier.fillMaxWidth(), flush = true) {
        Await(items) { rows ->
            // `.csync-labelpick`: rows of 8/12 padding with a hairline under each, scrolling after 320.
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ZillitCheckbox(
                            c.id in selected,
                            { on -> onToggle(c.id, on) },
                        )
                        ZillitText(
                            c.str("asset_number"),
                            style = ZillitTheme.typography.bodyLarge.copy(
                                fontSize = 12.9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                        )
                        ZillitText(
                            c.str("name"),
                            Modifier.weight(1f, fill = false),
                            style = ZillitTheme.typography.bodyLarge.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                        )
                        ZillitText(
                            c.rec("character")?.str("name").orEmpty(),
                            style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = ZillitTheme.colors.textMuted,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    ZillitDivider()
                }
            }
        }
    }
}

/**
 * `.csync-qrlabel`: a dashed 6dp-cornered tag, always black on white (it is a printed label), the QR beside its text.
 */
@Composable
private fun LabelPreview(c: Rec, cell: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        cell.clip(shape).background(Color.White).dashedBorder().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitQrCode(qrPayload(c), size = QR_PREVIEW)
        Column(Modifier.weight(1f)) {
            val lines = labelLines(c).filter { it.isNotBlank() }
            ZillitText(
                c.str("asset_number"),
                style = ZillitTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = Color.Black,
                maxLines = 1,
            )
            lines.forEachIndexed { i, line ->
                if (i == 0) {
                    ZillitText(
                        line,
                        style = ZillitTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.Black,
                        maxLines = 3,
                    )
                } else {
                    ZillitText(
                        line,
                        style = ZillitTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = Color.DarkGray,
                        maxLines = 3,
                    )
                }
            }
        }
    }
}

private val RADIUS = 6.dp

private fun Modifier.dashedBorder(): Modifier = drawBehind {
    val stroke = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    drawRoundRect(Color.Gray, cornerRadius = CornerRadius(RADIUS.toPx()), style = stroke)
}

