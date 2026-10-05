package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.feature.costumesetsync.domain.PickedFile
import com.zillit.desktop.feature.costumesetsync.domain.SetupRules
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private val CARD_WIDTH = 760.dp
private val CARD_SHAPE = RoundedCornerShape(18.dp)

/** What the first-run form holds, and what Create does with it. */
@Stable
internal class FirstRunState(val askType: Boolean) {
    var type by mutableStateOf("")
    var revision by mutableStateOf("")
    var file by mutableStateOf<PickedFile?>(null)
    var dates by mutableStateOf<Map<String, String>>(emptyMap())
    var withPrep by mutableStateOf(false)
    var withWrap by mutableStateOf(false)
    var saving by mutableStateOf(false)

    /** The file in the script review, once Create has saved the rest. */
    var reviewing by mutableStateOf<PickedFile?>(null)

    // What was last saved, so going back into the review doesn't save (and toast) it twice.
    private var saved: String? = null

    /** Why Create is greyed out, as the string key shown beside it; null once the form can be created. */
    val missing: String?
        get() = when {
            file == null -> "csync_setup_need_script"
            askType && type.isEmpty() -> "csync_setup_need_type"
            else -> null
        }

    fun date(key: String): String = dates[key].orEmpty()

    fun setDate(key: String, value: String) {
        dates = dates + (key to value)
    }

    /** Unticking a section drops what was typed in it, as the web does. */
    fun toggle(on: Boolean, keys: List<String>, set: (Boolean) -> Unit) {
        set(on)
        if (!on) dates = dates + keys.associateWith { "" }
    }

    /** Create: save the type and dates first (nothing, when blank), then open the script review. */
    fun create(ctx: SyncCtx) {
        val chosen = file
        if (missing != null || chosen == null || saving) return
        val body = SetupRules.payload(type.takeIf { askType }, dates)
        val key = body.toString()
        if (body.isEmpty() || saved == key) {
            reviewing = chosen
            return
        }
        saving = true
        ctx.scope.launch {
            val done = ctx.write { ctx.api.patch("", body) }
            saving = false
            if (done != null) {
                saved = key
                reviewing = chosen
            }
        }
    }
}

/**
 * The tool's first screen for a Zillit project with nothing in Costumes & Set Sync yet (the web's `FirstRun`): one
 * form, no tabs and no search. The script (required), the estimated dates under it (optional), then Create. Feature /
 * TV Series is asked above them only when Zillit doesn't already say it.
 *
 * Create saves the type and dates (`SetupRules.payload`; nothing when blank), then opens the usual script review on
 * the chosen file. Importing finishes the setup and [onDone] opens Scene Breakdown; cancelling the review comes back
 * here with everything still filled in. Anyone with posting rights gets the form; someone without them sees who can
 * set it up, and the way to ask for posting access. [onChanged] re-reads the production record after the import.
 */
@Composable
fun FirstRun(onChanged: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalSync.current
    val project = ctx.project
    val state = remember { FirstRunState(SetupRules.needsType(project.rec?.str("type"))) }
    val typeKey = when (project.rec?.str("type")) {
        "EPISODIC" -> "csync_setup_series"
        "FEATURE" -> "csync_setup_feature"
        else -> null
    }
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (project.canSetUp(ctx.canPost)) {
            FirstRunCard(state, project.name, typeKey)
        } else {
            LockedCard(project.name, typeKey)
        }
    }
    ScriptReview(state, project.rec?.rec("counts")?.long("scenes")?.toInt() ?: 0, onChanged, onDone)
}

/** The form card: intro, the numbered steps scrolling in it, and Create pinned at its foot. */
@Composable
private fun FirstRunCard(state: FirstRunState, name: String, typeKey: String?) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.widthIn(max = CARD_WIDTH).fillMaxWidth().clip(CARD_SHAPE).background(colors.surface)
            .border(1.dp, colors.border, CARD_SHAPE),
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            FirstRunIntro(name, typeKey)
            ZillitText(
                t("csync_setup_sub"),
                style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
                color = colors.textSecondary,
            )
            FirstRunSteps(state)
        }
        FirstRunFooter(state)
    }
}

@Composable
private fun FirstRunIntro(name: String, typeKey: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        MarkTile(48.dp, 24.dp, 14.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(
                t("csync_setup_title"),
                style = ZillitTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            ProjectLine(name, typeKey)
        }
    }
}

/** The project's name, bold, and its type as a muted pill. */
@Composable
private fun ProjectLine(name: String, typeKey: String?) {
    if (name.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            name,
            style = ZillitTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
        typeKey?.let { StatusBadge("MUTED", t(it)) }
    }
}

/** The tool's mark: a hanger on a rounded accent tile. */
@Composable
private fun MarkTile(size: Dp, icon: Dp, radius: Dp) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier.size(size).background(colors.accentSoft, shape).border(1.dp, colors.accent.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(FirstRunIcons.CoatHanger, tint = colors.accentText, size = icon) }
}

/** Create, with why it is greyed out beside it; pinned under the scrolling form. */
@Composable
private fun FirstRunFooter(state: FirstRunState) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            state.missing?.let { t(it) }.orEmpty(),
            Modifier.weight(1f),
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        ZillitButton(
            t("csync_first_run_create"),
            onClick = { state.create(ctx) },
            enabled = state.missing == null,
            loading = state.saving,
            modifier = Modifier.defaultMinSize(minWidth = 200.dp, minHeight = 40.dp),
        )
    }
}

/** Nobody without posting rights sets it up: who can, and the way to ask. */
@Composable
private fun LockedCard(name: String, typeKey: String?) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    Column(
        Modifier.widthIn(max = CARD_WIDTH).fillMaxWidth().clip(CARD_SHAPE).background(colors.surface)
            .border(1.dp, colors.border, CARD_SHAPE).padding(start = 40.dp, end = 40.dp, top = 36.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MarkTile(64.dp, 30.dp, 18.dp)
            ZillitText(
                t("csync_first_run_title"),
                style = ZillitTheme.typography.titleLarge.copy(
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                ),
                textAlign = TextAlign.Center,
            )
            ProjectLine(name, typeKey)
        }
        ZillitNotice(
            t("csync_first_run_no_rights"),
            Modifier.fillMaxWidth(),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Info,
            action = {
                ZillitButton(
                    t("csync_request_posting_access"),
                    onClick = { ctx.askRights(RightsKind.Post) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
            },
        )
    }
}

/** The usual script review on the chosen file; importing it finishes the setup. */
@Composable
private fun ScriptReview(state: FirstRunState, sceneCount: Int, onChanged: () -> Unit, onDone: () -> Unit) {
    val open = state.reviewing != null
    val docs = rememberProjectDocuments("SCRIPT", enabled = open)
    val upload = rememberScriptUpload(
        open = open,
        docs = docs,
        existingScenes = sceneCount,
        onImported = {
            state.reviewing = null
            onChanged()
            onDone()
        },
        onClose = { state.reviewing = null },
        initialRevision = state.revision.trim(),
    )
    LaunchedEffect(state.reviewing) { state.reviewing?.let(upload::pickFile) }
    ScriptUploadDialog(open, upload, docs) { state.reviewing = null }
}
