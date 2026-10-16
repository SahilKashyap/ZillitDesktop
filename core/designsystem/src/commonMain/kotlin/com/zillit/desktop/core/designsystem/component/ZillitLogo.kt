package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.generated.resources.Res
import com.zillit.desktop.core.designsystem.generated.resources.zillit_logo
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import org.jetbrains.compose.resources.painterResource

/**
 * The app's own mark, as the Dock and the window title bar show it.
 *
 * The sign-in surfaces drew a lettermark — an orange tile with a "Z" — because
 * "the desktop build ships no image resources yet". The design system carries
 * its fonts as compose resources, so it can carry this one too, and a screen
 * that wants the logo no longer has to approximate it. Modules outside
 * `desktopApp` cannot reach `icons/zillit-icon.png` at all, which is why the
 * same file lives here.
 *
 * The artwork is square and already rounded, so it needs no clip of its own.
 */
@Composable
fun ZillitLogo(modifier: Modifier = Modifier, size: Dp = DEFAULT_SIZE) {
    Image(
        painter = painterResource(Res.drawable.zillit_logo),
        // Named rather than null: on the splash the logo is the only thing on
        // the screen, so it is what a screen reader has to announce.
        contentDescription = str(S.app_name),
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

/** Big enough to read the wordmark inside the mark at 1x. */
private val DEFAULT_SIZE = 96.dp
