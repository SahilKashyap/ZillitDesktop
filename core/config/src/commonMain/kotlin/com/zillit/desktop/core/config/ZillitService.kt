package com.zillit.desktop.core.config

/**
 * The backend is ~30 separate services, each with its own base URL per
 * environment — not one host. Transcribed from the Android app's
 * `local.properties` key set (`{STG,QA,PROD}_<SERVICE>_BASE_URL`).
 *
 * Modelling them as an enum rather than 30 loose strings means a feature asks
 * for `ZillitService.Budget` and cannot typo a hostname, and adding a service
 * is one entry plus one config line.
 */
enum class ZillitService(val configKey: String) {
    Core("BASE_URL"),
    AccountHub("ACCOUNT_HUB_BASE_URL"),
    Budget("BUDGET_BASE_URL"),

    /**
     * The Budget Builder service (`zillit_budget`) — the API behind the
     * embedded budget application. A different service from [Budget], which
     * is the older budget-chat backend; the two share nothing but a word.
     */
    BudgetBuilder("BUDGET_BUILDER_BASE_URL"),

    /**
     * The web deployment that serves the Budget Builder *page*.
     *
     * The application itself ships with the web client (its
     * `public/budget-builder/`), not with this app and not from the service —
     * the copy the service hosts is a stale standalone build with no Zillit
     * handshake in it. Loading the page from the web deployment keeps the
     * desktop on exactly the build the web ships, with nothing bundled here
     * to fall behind. For develop this is `https://dev.zillit.com/`.
     */
    BudgetBuilderWeb("BUDGET_BUILDER_WEB_URL"),

    Calendar("CALENDAR_BASE_URL"),
    Calling("CALLING_BASE_URL"),
    CallSheet("CALLSHEET_BASE_URL"),
    CardExpenses("CARD_EXPENSES_BASE_URL"),
    CashExpenses("CASH_EXPENSES_BASE_URL"),
    Casting("CASTING_BASE_URL"),
    Chat("CHAT_BASE_URL"),
    Continuity("CONTINUITY_BASE_URL"),

    /**
     * The AD dashboard service: the production side of supporting artistes —
     * the register, shoot days, attendance, rate config and AD reports.
     *
     * Serves `/api/v2/{artistes,artiste-queries,supporting-artist-days,
     * ad-shoot-days,ad-rate-config,ad-report,ad-agencies}` (the web's
     * `SERVICE_DEFS` `ad-dashboard` prefix). A different service from
     * [SupportingArtists], which is the artiste's own side.
     */
    AdDashboard("AD_DASHBOARD_BASE_URL"),

    /** The cost-report service (`cost-report-server`): live cost report, posted snapshots, ledger drill-down. */
    CostReport("COST_REPORT_BASE_URL"),
    DealMemo("DEAL_MEMO_BASE_URL"),
    DocDistribution("DOC_DISTRIBUTION_BASE_URL"),
    Drive("DRIVE_BASE_URL"),
    Email("EMAIL_BASE_URL"),

    // Spelled as in the Android config; kept verbatim so the same properties
    // file works for both clients. Do not "fix" without changing both.
    ESignature("E_SIGNATUTE_BASE_URL"),

    Forms("FORMS_BASE_URL"),
    Integrations("INTEGRATIONS_BASE_URL"),
    Invoices("INVOICES_BASE_URL"),
    Location("LOCATION_BASE_URL"),
    Map("MAP_BASE_URL"),
    Media("MEDIA_BASE_URL"),
    Notification("NOTIFICATION_BASE_URL"),
    Payroll("PAYROLL_BASE_URL"),
    PreAndProduction("PRE_AND_PRODUCTION_BASE_URL"),
    ProductionReport("PRODUCTION_REPORT_BASE_URL"),
    PurchaseOrder("PURCHASE_ORDER_BASE_URL"),
    Recce("RECCE_BASE_URL"),
    ScheduleDistribution("SCHEDULE_DIST_BASE_URL"),
    ScriptDistribution("SCRIPT_DIST_BASE_URL"),
    ScriptNotes("SCRIPT_NOTES_BASE_URL"),
    Sides("SIDES_BASE_URL"),

    /**
     * The supporting-artiste self-service portal (`sae-server`), serving
     * every route under `/api/v2/sa-portal` — the artiste's own vouchers,
     * pay and queries.
     *
     * Its envelope differs from the rest of the estate: **success carries no
     * `status` field**, only `{message, data}`; failure is the usual
     * `{status: 0, message, data}`. Reading success as `status == 1` here
     * would refuse every good answer.
     */
    SupportingArtists("SUPPORTING_ARTISTS_BASE_URL"),
    Transportation("TRANSPORTATION_BASE_URL"),
    Units("UNITS_BASE_URL"),
    Wardrobe("WARDROBE_BASE_URL"),
    ;

    companion object {
        fun fromConfigKey(key: String): ZillitService? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}

/**
 * Realtime endpoints, which are websockets rather than REST bases.
 */
enum class ZillitRealtimeEndpoint(val configKey: String) {
    Socket("URL"),
    LiveKit("LIVEKIT_URL"),
    Call("CALL_URL"),
    ;

    companion object {
        fun fromConfigKey(key: String): ZillitRealtimeEndpoint? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}
