@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.zillit.desktop.core.designsystem.component.onBackdropTap
import com.zillit.desktop.core.designsystem.component.swallowPresses
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The web's modal card (`.stk-mm`), inside the tool rather than over the whole
 * app: in a tool window an overlay has to stay inside its window.
 *
 * Escape and a click on the backdrop close it.
 *
 * @param head replaces the heading (the member card puts the name field there)
 */
@Composable
internal fun SDialog(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    busy: Boolean = false,
    head: (@Composable () -> Unit)? = null,
    footer: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val k = StillsTheme.c
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxSize()
            .background(k.scrim)
            .then(if (busy) Modifier else Modifier.onBackdropTap(onClose))
            // A dialog owns its keys: whatever is underneath (the lightbox's
            // arrows, K and D) must not hear them.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !busy) {
                    onClose()
                    true
                } else {
                    event.type == KeyEventType.KeyDown
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = if (wide) 720.dp else 560.dp)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(shape)
                .background(k.panel)
                .border(BorderStroke(1.dp, k.line), shape)
                // A press that lands on the card — including the strip beside
                // a button, where a miss lands — is not a press outside it.
                .swallowPresses()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { head?.invoke() ?: SText(title, 19, androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1) }
                SBtn("✕", onClose, small = true, enabled = !busy, tooltip = str(S.close))
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = BODY_MAX).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            footer?.let { foot ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { foot(this) }
            }
        }
    }
}

/** "Are you sure?" — for the few actions that cannot be undone. */
@Composable
internal fun SConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    SDialog(
        title = title,
        onClose = onClose,
        modifier = modifier,
        busy = busy,
        footer = {
            SBtn(str(S.cancel), onClose, kind = SBtnKind.Ghost, small = true, enabled = !busy)
            SBtn(confirmLabel, onConfirm, kind = SBtnKind.Danger, small = true, enabled = !busy)
        },
    ) {
        SText(message, 15)
    }
}

private val BODY_MAX: Dp = 520.dp
