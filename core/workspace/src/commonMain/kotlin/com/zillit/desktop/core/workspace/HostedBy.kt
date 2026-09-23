package com.zillit.desktop.core.workspace

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The path of the tool rendering this content inside its own shell, or null
 * when the content is a tool standing on its own.
 *
 * The Account Hub renders Purchase Orders, Invoices, the expense tools and the
 * rest inside its console, as the web's nested routes do. The web tells the two
 * entries apart — `?entry=tool` from a Film Tools tile versus the hub's sidebar
 * — because they are different views of one module: an accountant arriving
 * through the hub gets the accountant's console, and the same accountant
 * opening the tile gets the crew view, where they file their own receipts and
 * claims. A tool reads this to make the same choice.
 */
val LocalHostedBy = staticCompositionLocalOf<String?> { null }
