package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult

/** What the reader is shown when a body really will not decrypt. */
const val NOTICE_UNREADABLE = "[This message could not be decrypted]"

/**
 * A board body as the reader should see it, wherever it arrived from.
 *
 * Three answers, and the middle one is the whole reason this exists:
 *
 *  - blank stays blank;
 *  - a body that **cannot** be ciphertext ([NoticeBody.isCipherShaped]) is its
 *    own text, and is returned unchanged;
 *  - anything else is decrypted, and a failure is a placeholder rather than a
 *    hex blob the reader cannot tell from a corrupted post.
 *
 * The middle rule was found on the REST path (`2026-10-19`: the Production
 * Report chat's record answered thirteen plain bodies on develop) and fixed
 * there, in a private method. The socket path kept its own two-line version
 * in a lambda in the wiring, so the same post read two different ways
 * depending on how it reached the app: the board drew the words, and the
 * notification raised for it drew "[This message could not be decrypted]"
 * over them — which is what a user sent in a screenshot of
 * (2026-10-22). Not every body on a board was ever encrypted; every
 * web-written caption is plain, and so is everything posted before the bodies
 * were encrypted at all.
 *
 * So the rule lives here, in one place, and both paths call it. A rule about
 * what the wire contains cannot be allowed to exist twice — the copy that is
 * not being looked at is the one that goes stale.
 */
fun noticeBodyText(body: String, decrypt: (String) -> ZillitResult<String>): String {
    if (body.isBlank()) return ""
    if (!NoticeBody.isCipherShaped(body)) return body

    return when (val result = decrypt(body)) {
        is ZillitResult.Success -> result.data
        is ZillitResult.Failure -> {
            // Never the ciphertext, and never the id — just that it happened.
            ZillitLog.w(TAG) { "a notice body did not decrypt: ${result.error.technical}" }
            NOTICE_UNREADABLE
        }
    }
}

private const val TAG = "Home"
