package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.security.ApiKeySetup
import kotlinx.coroutines.launch

/**
 * One-time entry of the API header key and IV.
 *
 * ## Why this screen exists
 *
 * The header scheme needs an AES key and IV. The Android client compiles them
 * into `BuildConfig`, so they ship inside every APK; a desktop JAR decompiles
 * more easily still, and a config file beside the app is no better. So the user
 * supplies them once and they go straight to the OS keychain — never to disk,
 * never to a log, never echoed back on screen.
 *
 * This is a stopgap for the shared-key scheme, not a design to be proud of.
 * Once per-device request signing lands (plan §8.3), keys are fetched at runtime
 * after device attestation and this screen disappears.
 *
 * Values are masked as they are typed: they are credentials, and someone is
 * usually looking over your shoulder in a production office.
 */
@Composable
fun ApiKeySetupScreen(
    setup: ApiKeySetup,
    onConfigured: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var iv by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(CARD_WIDTH)
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .padding(ZillitTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Intro()

            SecretField("API encryption key", "32 characters", key, !busy) { key = it; error = null }
            SecretField("API IV", "16 characters", iv, !busy) { iv = it; error = null }

            error?.let {
                ZillitText(it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
            }

            ZillitButton(
                text = "Save to keychain",
                onClick = {
                    busy = true
                    scope.launch {
                        save(setup, key, iv) { outcome ->
                            busy = false
                            when (outcome) {
                                null -> {
                                    // Drop the plaintext from composition state
                                    // the moment it is safely stored.
                                    key = ""
                                    iv = ""
                                    onConfigured()
                                }
                                else -> error = outcome
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = key.isNotBlank() && iv.isNotBlank() && !busy,
                loading = busy,
            )

            ZillitText(
                text = "Your administrator has these. They're the same values the Android app " +
                    "uses for this environment.",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun Intro() {
    ZillitText("One-time setup", style = ZillitTheme.typography.titleLarge)
    ZillitText(
        text = "Zillit needs its API key and IV to talk to the server. They're stored in " +
            "your system keychain and never written to disk.",
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
    )
}

/** Stores the pair; reports null on success or a user-facing message on failure. */
private suspend fun save(
    setup: ApiKeySetup,
    key: String,
    iv: String,
    onResult: (String?) -> Unit,
) {
    when (val stored = setup.store(key, iv)) {
        is ZillitResult.Success -> onResult(null)
        is ZillitResult.Failure -> onResult(stored.error.userMessage)
    }
}

/** A masked field — these are credentials, and offices are open-plan. */
@Composable
private fun SecretField(
    label: String,
    helper: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    ZillitTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        helperText = helper,
        visualTransformation = PasswordVisualTransformation(),
        enabled = enabled,
    )
}

private val CARD_WIDTH = 440.dp
