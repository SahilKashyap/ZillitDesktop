package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The icon a File Cabinet row carries — the web's `UNIT_ICON_MAP`, in the
 * design system's own set. A module with no entry of its own gets the grid,
 * as the web falls back to its app-store glyph.
 */
internal fun cabinetIcon(identifier: String): ImageVector = ICONS[identifier] ?: ZillitIcons.Grid

private val ICONS: Map<String, ImageVector> = mapOf(
    "accounting" to ZillitIcons.Calculator,
    "account_hub" to ZillitIcons.Bank,
    "document_distribution" to ZillitIcons.Folder,
    "main_budget" to ZillitIcons.Wallet,
    "department_budget" to ZillitIcons.BarChart,
    "casting_background" to ZillitIcons.Users,
    "casting_main" to ZillitIcons.User,
    "catering" to ZillitIcons.Receipt,
    "confidential_info" to ZillitIcons.Lock,
    "continuity" to ZillitIcons.Camera,
    "script_notes" to ZillitIcons.Draft,
    "group_chat" to ZillitIcons.Chat,
    "home" to ZillitIcons.Home,
    "info" to ZillitIcons.Info,
    "location" to ZillitIcons.Globe,
    "production_report" to ZillitIcons.File,
    "reports" to ZillitIcons.BarChart,
    "purchase_order" to ZillitIcons.CreditCard,
    "dod" to ZillitIcons.Calendar,
    "schedule_distribution" to ZillitIcons.Clock,
    "script_distribution" to ZillitIcons.File,
    "wardrobe_background" to ZillitIcons.Tag,
    "wardrobe_main" to ZillitIcons.Tag,
    "transportation" to ZillitIcons.Transport,
    "recce" to ZillitIcons.Eye,
)
