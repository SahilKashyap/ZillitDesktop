package com.zillit.desktop.core.common

import io.github.aakira.napier.Napier

/**
 * Logging front-end with redaction built in (plan §8.4).
 *
 * The Android client runs Ktor at `LogLevel.ALL`, so bearer tokens, encrypted
 * headers and full message bodies land in logcat. Every log here passes through
 * [redact] first, and there is no unredacted escape hatch — if you need raw
 * output while debugging, change [redact], don't bypass it.
 */
object ZillitLog {

    fun d(tag: String, message: () -> String) = Napier.d(tag = tag, message = redact(message()))

    fun i(tag: String, message: () -> String) = Napier.i(tag = tag, message = redact(message()))

    fun w(tag: String, message: () -> String) = Napier.w(tag = tag, message = redact(message()))

    fun e(tag: String, throwable: Throwable? = null, message: () -> String) =
        Napier.e(tag = tag, throwable = throwable, message = redact(message()))

    /**
     * Masks anything that looks like a credential.
     *
     * Conservative by design: over-redacting costs debugging convenience,
     * under-redacting writes secrets to disk.
     */
    fun redact(raw: String): String =
        SENSITIVE_PATTERNS.fold(raw) { acc, pattern -> pattern.replace(acc) { "${it.groupValues[1]}$MASK" } }

    private const val MASK = "***"

    private const val SENSITIVE_NAMES =
        "authorization|token|password|passwd|secret|api[_-]?key|" +
            "refresh[_-]?token|device[_-]?id|otp|pin|confirm[_-]?code|recover[_-]?code"

    private val SENSITIVE_PATTERNS = listOf(
        // Bearer tokens FIRST. The name=value rule below stops at whitespace,
        // so on `authorization=Bearer abc.def` it would mask the word "Bearer"
        // and leave the token in the clear. Order is load-bearing here.
        Regex("""((?i)bearer\s+)[A-Za-z0-9\-._~+/]+=*"""),
        // key="value" / key: value / key=value, for known-sensitive names.
        // The `["']?` after the name matters: a JSON body reads
        // `{"token":"abc"}`, where a closing quote sits between the name and
        // the colon — without it the whole JSON case goes unredacted.
        Regex("""((?i)\b(?:$SENSITIVE_NAMES)\b["']?\s*[:=]\s*["']?)[^\s"',}]+"""),
        // Config property names for the header key: `PROD_ENCRYPTION_KEY=…`,
        // `QA_IV_ENCRYPTION_KEY=…`. Deliberately no `\b` before the name: `_`
        // is a word character, so there is no boundary between `PROD_` and
        // `ENCRYPTION`, and a `\b` form silently never matches.
        Regex("""((?i)[a-z0-9_]*encryption_key["']?\s*[:=]\s*["']?)[^\s"',}]+"""),
        // A bare `code` field. This is the QR sign-in credential: anyone who
        // reads it during its window can link their own device, so it must not
        // survive in a log file even when body logging is switched on.
        //
        // The lookaround is load-bearing. `project_code`, `country_code`,
        // `language_code` and `enterprise_client_code` are NOT secrets, and
        // masking them would gut the logs for exactly the debugging body
        // logging exists to serve.
        Regex("""((?i)(?<![a-z_])code["']?\s*[:=]\s*["']?)[^\s"',}]+"""),
        // The app's encrypted header blobs — long hex runs
        Regex("""(\b)[0-9a-fA-F]{64,}"""),
    )
}
