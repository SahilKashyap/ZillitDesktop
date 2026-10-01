package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import kotlinx.datetime.LocalDate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.DocSceneRow
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.docDayKey
import com.zillit.desktop.feature.costumesetsync.domain.docName
import com.zillit.desktop.feature.costumesetsync.domain.docSceneRows
import com.zillit.desktop.feature.costumesetsync.domain.docSourceKey
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.latestOf
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

/**
 * The project's documents of one kind, newest first, each with a button to read
 * it into the importer — the "no file needed" path of the upload dialogs. The
 * newest is marked Latest, which is the one the user almost always wants.
 */
@Composable
internal fun DocumentPickList(
    docs: List<Rec>,
    loading: Boolean,
    onPick: (Rec) -> Unit,
    busyId: String?,
    actionLabel: String,
    emptyText: String,
) {
    val colors = ZillitTheme.colors
    // `.csync-doclist`: a 10px-radius bordered list, scrolling past 220px.
    val shape = RoundedCornerShape(10.dp)
    val box = Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border, shape)
    if (loading && docs.isEmpty()) {
        Box(box.padding(14.dp), contentAlignment = Alignment.Center) { ZillitSpinner(size = 18.dp) }
        return
    }
    if (docs.isEmpty()) {
        // `.csync-doclist--empty`: 14px padding, centred, muted.
        Box(box.padding(14.dp), contentAlignment = Alignment.Center) { MutedText(emptyText) }
        return
    }
    Column(box.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        docs.forEachIndexed { i, d ->
            DocPickRow(d, actionLabel, busyId, onPick)
            if (i < docs.lastIndex) ZillitDivider()
        }
    }
}

/** One kept document: its icon, name (Latest when it is), source, date and revision, and the pick button. */
@Composable
private fun DocPickRow(d: Rec, actionLabel: String, busyId: String?, onPick: (Rec) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(ZillitIcons.File, tint = colors.danger, size = 20.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    docName(d),
                    Modifier.weight(1f, fill = false),
                    style = ZillitTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    maxLines = 1,
                )
                if (d.bool("latest")) {
                    ZillitText(
                        t("csync_doc_latest"),
                        Modifier.padding(start = 6.dp).clip(RoundedCornerShape(999.dp)).background(
                            colors.accentSoft,
                        ).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.accentText,
                        maxLines = 1,
                    )
                }
            }
            ZillitText(
                listOf(
                    t(docSourceKey(d)),
                    fmtDate(d.long("created")),
                    d.str("revision"),
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitButton(
            actionLabel,
            onClick = { onPick(d) },
            size = ButtonSize.Small,
            variant = if (d.bool("latest")) ButtonVariant.Primary else ButtonVariant.Secondary,
            loading = busyId == d.id,
            enabled = busyId == null || busyId == d.id,
        )
    }
}

private val KIND_LOWER = mapOf("SCRIPT" to "script", "SCHEDULE" to "schedule", "CALLSHEET" to "callsheet")

/** "Fri 06 Mar": weekday, 2-digit day and short month, as the reference's `fmtDate` options. */
private fun dayLabel(key: String): String {
    val d = runCatching { LocalDate.parse(key.take(DATE_KEY_LEN)) }.getOrNull() ?: return key
    val week = d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(SHORT)
    val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(SHORT)
    return "$week %02d $month".format(d.day)
}

private const val DATE_KEY_LEN = 10
private const val SHORT = 3

/**
 * "View uploaded script / schedule / call sheet": the kept versions and the scenes
 * the app holds for the one on screen, to tick off while reading down the file.
 * The ticks are only for this look and are not saved.
 *
 * Laid out as the web's: a version bar over the file pane (left) and the 340dp tick-off list (right).
 * Not ported: the in-app PDF / text rendering — the file pane shows the web's "can't be shown here" panel
 * and the file opens in the system viewer ("Open in new tab", download rights); "Read this" opens the
 * importer on the version on screen (posting rights only, since an import writes).
 */
@Composable
internal fun DocumentViewerDialog(
    open: Boolean,
    kind: String,
    docs: ProjectDocuments,
    scenes: List<Rec>,
    onClose: () -> Unit,
    onRead: (Rec) -> Unit,
) {
    val ctx = LocalSync.current
    val lower = KIND_LOWER[kind] ?: "script"
    var docId by remember(open) { mutableStateOf<String?>(null) }
    var ticked by remember(open) { mutableStateOf(emptySet<String>()) }
    val list = docs.docs
    val latest = latestOf(list)
    val doc = list.firstOrNull { it.id == docId } ?: latest
    val day = docDayKey(doc)
    val rows = docSceneRows(kind, scenes, day)
    val meta = ::docMeta
    // As big as the window allows (the file is read beside the scenes): up to 1360 wide, 24dp from each edge.
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val (winW, winH) = with(density) { window.width.toDp() to window.height.toDp() }
    val paneH = (winH - PANE_CHROME).coerceAtLeast(MIN_PANE)

    SyncDialogShell(
        title = t("csync_uploaded_doc_$lower"),
        icon = ZillitIcons.File,
        onDismiss = onClose,
        visible = open,
        width = (winW - 48.dp).coerceIn(VIEWER_MIN, VIEWER_MAX),
        maxHeight = (winH - 48.dp).coerceAtLeast(VIEWER_MIN),
        actions = { ZillitButton(t("csync_close"), onClick = onClose, variant = ButtonVariant.Secondary) },
    ) {
        if (doc == null) {
            EmptyState(t("csync_doc_kept_none_$lower"), t("csync_doc_kept_hint_$lower"))
            return@SyncDialogShell
        }
        val at = list.indexOfFirst { it.id == doc.id }
        VersionBar(
            VersionBarState(list, doc, latest, at, lower, ctx.canPost, meta),
            onPick = { docId = it },
            onRead = { onRead(doc) },
            onOpen = { ctx.whenDownload { ctx.scope.launch { openDocument(ctx, doc) } } },
            canDownload = ctx.canDownload,
        )
        if (latest != null && doc.id != latest.id) OlderNotice(latest, meta) { docId = latest.id }
        Row(Modifier.fillMaxWidth().height(paneH), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilePane(ctx.canDownload)
            ScenePane(
                kind,
                lower,
                day,
                rows.rows,
                rows.undated,
                ticked,
                { id -> ticked = if (id in ticked) ticked - id else ticked + id },
            )
        }
    }
}

/** `Source · revision · uploaded`: what names a version of a kept document. */
private fun docMeta(d: Rec): String =
    listOf(t(docSourceKey(d)), d.str("revision"), fmtDateTime(d.long("created")))
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

/** The file pane: the web's `.csync-docview__file` showing its "can't be shown here" state. */
@Composable
private fun RowScope.FilePane(canDownload: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        Modifier.weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        CantShow(canDownload)
    }
}

private val VIEWER_MIN = 640.dp
private val VIEWER_MAX = 1360.dp
private val MIN_PANE = 420.dp
private val PANE_CHROME = 330.dp
private val SCENE_PANE_W = 340.dp

private class VersionBarState(
    val list: List<Rec>,
    val doc: Rec,
    val latest: Rec?,
    val at: Int,
    val lower: String,
    val canPost: Boolean,
    val meta: (Rec) -> String,
)

/** The version label (with its count), the older / newer / picker, Read this, and Open in new tab at the end. */
@Composable
private fun VersionBar(
    state: VersionBarState,
    onPick: (String) -> Unit,
    onRead: () -> Unit,
    onOpen: () -> Unit,
    canDownload: Boolean,
) {
    val colors = ZillitTheme.colors
    val list = state.list
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitIcon(ZillitIcons.Copy, tint = colors.textMuted, size = 14.dp)
                ZillitText(
                    t("csync_doc_version").uppercase(),
                    style = ZillitTheme.typography.label.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.48.sp,
                    ),
                    color = colors.textMuted,
                )
                if (list.size > 1) ZillitStatusPill(
                    t("csync_doc_n_versions", "n" to list.size),
                    tone = StatusTone.Progress,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VersionPicker(state, onPick)
                if (state.canPost) ZillitButton(
                    t("csync_doc_read_this"),
                    onClick = onRead,
                    leadingIcon = ZillitIcons.File,
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                )
            }
        }
        if (canDownload) ZillitButton(
            t("csync_open_new_tab"),
            onClick = onOpen,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Download,
        )
    }
}

/** Older / the version picker / newer when there are several versions; the one version's name and meta otherwise. */
@Composable
private fun VersionPicker(state: VersionBarState, onPick: (String) -> Unit) {
    val list = state.list
    if (list.size > 1) {
        ZillitIconButton(
            ZillitIcons.ChevronLeft,
            t("csync_doc_older"),
            onClick = { onPick(list[state.at + 1].id) },
            enabled = state.at < list.lastIndex,
        )
        ZillitSelect(
            value = state.doc.id,
            options = list.map { it.id },
            onSelect = onPick,
            label = { id ->
                list.firstOrNull { it.id == id }
                    ?.let { "${docName(it)} · ${fmtDateTime(it.long("created"))}" }
                    .orEmpty()
            },
            modifier = Modifier.width(VERSION_W),
        )
        ZillitIconButton(
            ZillitIcons.ChevronRight,
            t("csync_doc_newer"),
            onClick = { onPick(list[state.at - 1].id) },
            enabled = state.at > 0,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(
                docName(state.doc),
                style = ZillitTheme.typography.titleSmall.copy(fontSize = 15.sp),
                maxLines = 1,
            )
            MutedText(state.meta(state.doc))
        }
    }
}

private val VERSION_W = 560.dp

/** `.csync-docview__older`: the amber notice naming the newest version, with Show latest. */
@Composable
private fun OlderNotice(latest: Rec, meta: (Rec) -> String, onShow: () -> Unit) {
    ZillitNotice(
        t("csync_doc_viewing_older", "name" to docName(latest), "meta" to meta(latest)),
        tone = StatusTone.Pending,
        icon = ZillitIcons.Clock,
        action = { ZillitButton(
            t("csync_doc_show_latest"),
            onClick = onShow,
            size = ButtonSize.Small,
            variant = ButtonVariant.Secondary,
        ) },
    )
}

/** The web's "{open}" sentence with Open in new tab in bold — or the plain one when download is not allowed. */
@Composable
private fun CantShow(canDownload: Boolean) {
    val colors = ZillitTheme.colors
    val style = ZillitTheme.typography.bodySmall.copy(fontSize = 13.sp)
    if (!canDownload) {
        ZillitText(
            t("csync_doc_cant_show"),
            Modifier.padding(16.dp),
            style = style,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        return
    }
    val parts = t("csync_doc_cant_show_open").split("{open}")
    ZillitText(
        buildAnnotatedString {
            append(parts.firstOrNull().orEmpty())
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t("csync_open_new_tab")) }
            append(parts.getOrNull(1).orEmpty())
        },
        Modifier.padding(16.dp), style = style, color = colors.textMuted, textAlign = TextAlign.Center,
    )
}

/** The 340dp list of scenes the app holds for this file, ticked off while reading down it. */
@Composable
private fun ScenePane(
    kind: String,
    lower: String,
    day: String,
    rows: List<DocSceneRow>,
    undated: Int,
    ticked: Set<String>,
    onToggle: (String) -> Unit,
) {
    val colors = ZillitTheme.colors
    val title = when {
        kind != "CALLSHEET" -> t("csync_doc_list_$lower")
        day.isNotEmpty() -> t("csync_doc_list_callsheet", "day" to dayLabel(day))
        else -> t("csync_doc_list_callsheet_day")
    }
    Column(Modifier.width(SCENE_PANE_W).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                title,
                Modifier.weight(1f, fill = false),
                style = ZillitTheme.typography.titleSmall.copy(fontSize = 13.sp),
                maxLines = 1,
            )
            ZillitText(
                t("csync_doc_ticked", "n" to ticked.size, "total" to rows.size),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitText(
            t("csync_doc_tick_hint"),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
            color = colors.textMuted,
        )
        if (rows.isEmpty()) MutedText(t("csync_doc_none_yet"), maxLines = 3)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEach { (scene, note) -> SceneTickRow(scene, note, kind, scene.id in ticked) { onToggle(scene.id) } }
        }
        if (undated > 0) {
            ZillitText(
                plural("csync_doc_undated", undated, "n" to undated),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = colors.textMuted,
            )
        }
    }
}

/** One scene to tick off: the checkbox, its number in mono, and the note the file gave (a day, for a schedule). */
@Composable
private fun SceneTickRow(scene: Rec, note: String, kind: String, ticked: Boolean, onToggle: () -> Unit) {
        val text = if (kind == "SCHEDULE") dayLabel(note) else note
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InkCheckbox(ticked, { onToggle() })
            ZillitText(
                scene.str("number"),
                Modifier.widthIn(min = 28.dp),
                style = ZillitTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
                maxLines = 1,
            )
            MutedText(text, Modifier.weight(1f))
        }
}

/** Opens a kept file in the system viewer: its own URL, else the stored key resolved against project storage. */
private suspend fun openDocument(ctx: com.zillit.desktop.feature.costumesetsync.ui.SyncCtx, doc: Rec) {
    val attachment = doc.rec("attachment")
    val url = doc.str("url").ifEmpty { null }
        ?: attachment?.str("signed_url")?.ifEmpty { null }
        ?: attachment?.str("public_url")?.ifEmpty { null }
        ?: attachment?.let { ctx.host.resolveUrl(it.str("media"), it.str("bucket"), it.str("region")) }
    if (url.isNullOrEmpty()) ctx.toast(t("csync_doc_preview_failed"), false) else ctx.host.openUrl(url)
}
