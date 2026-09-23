package com.zillit.desktop.core.strings

import com.zillit.desktop.core.common.ZillitLog
import java.util.IllegalFormatException
import java.util.Locale

@Suppress("SpreadOperator") // The copy is the price of a vararg bridge; the arrays are two or three items.
internal actual fun formatTemplate(template: String, args: Array<out Any?>): String =
    try {
        String.format(Locale.ROOT, template, *args)
    } catch (@Suppress("SwallowedException") broken: IllegalFormatException) {
        // A translator mangled a placeholder — `%1$s` became `%1 $s` — which
        // is a defect in one language's file, not a reason to crash the app
        // in that language. The template is still readable; the value is not
        // in it, and the log says which key to fix.
        ZillitLog.w("Strings") { "placeholders in '$template' do not match ${args.size} argument(s)" }
        template
    }
