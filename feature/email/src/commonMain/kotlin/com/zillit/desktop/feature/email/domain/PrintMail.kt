package com.zillit.desktop.feature.email.domain

/**
 * A conversation as a print-ready page — the web's `handlePrint`
 * (`EmailDetailToolbar.jsx`), message by message, oldest first: a subject
 * heading, then each message's From/To/Cc/Bcc/Date block and its body,
 * separated by a rule and kept whole across page breaks.
 *
 * Built here rather than in the screen so a test can pin the shape, and so
 * the host only has to know how to put HTML in front of a printer.
 */
fun printableHtml(subject: String, messages: List<EmailMessage>, printScriptNonce: String? = null): String {
    val title = subject.ifBlank { "(no subject)" }
    val blocks = messages.sortedBy { it.receivedAtMillis }.joinToString("") { message ->
        buildString {
            append("<div class=\"email-block\">")
            metaLine("From", message.from)
            metaLine("To", message.to.joinToString(", "))
            if (message.cc.isNotEmpty()) metaLine("Cc", message.cc.joinToString(", "))
            if (message.bcc.isNotEmpty()) metaLine("Bcc", message.bcc.joinToString(", "))
            metaLine("Date", mailFullTimeLabel(message.receivedAtMillis))
            append("<div class=\"body\">")
            val body = if (message.isHtml) {
                message.body.withoutScripts()
            } else {
                message.body.escapeHtml().replace("\n", "<br/>")
            }
            append(body)
            append("</div></div>")
        }
    }

    // A mail body is untrusted markup. The page only ever runs the one
    // script it was given a nonce for — the print call — and the policy
    // refuses every other, including any the body smuggles in.
    val policy = printScriptNonce?.let { "script-src 'nonce-$it'" } ?: "script-src 'none'"
    val printCall = printScriptNonce
        ?.let { "<script nonce=\"$it\">window.addEventListener('load', () => window.print());</script>" }
        .orEmpty()

    return """<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta http-equiv="Content-Security-Policy" content="$policy">
  <title>${title.escapeHtml()}</title>
  <style>
    body { font-family: Arial, sans-serif; margin: 20px; color: #333; }
    .subject { font-size: 18px; font-weight: bold; margin-bottom: 16px; }
    .email-block { border-bottom: 1px solid #ddd; padding: 20px 0; page-break-inside: avoid; }
    .meta { font-size: 12px; color: #555; margin-bottom: 4px; }
    .body { margin-top: 16px; font-size: 13px; }
    img { max-width: 100%; }
    @media print { body { margin: 0; } }
  </style>
</head>
<body>
  <div class="subject">${title.escapeHtml()}</div>
  $blocks
  $printCall
</body>
</html>"""
}

/** `<script>` and `<iframe>` blocks dropped whole — belt and braces beside the policy. */
private fun String.withoutScripts(): String =
    replace(SCRIPT_BLOCK, "").replace(IFRAME_BLOCK, "")

private val SCRIPT_BLOCK = Regex("(?is)<script\\b[^>]*>.*?</script\\s*>|<script\\b[^>]*/?>")
private val IFRAME_BLOCK = Regex("(?is)<iframe\\b[^>]*>.*?</iframe\\s*>|<iframe\\b[^>]*/?>")

private fun StringBuilder.metaLine(label: String, value: String) {
    append("<div class=\"meta\"><strong>").append(label).append(":</strong> ")
    append(value.escapeHtml()).append("</div>")
}
