rootProject.name = "Zillit"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// ---------------------------------------------------------------------------
// Application entry point
// ---------------------------------------------------------------------------
include(":desktopApp")

// ---------------------------------------------------------------------------
// core — infrastructure shared by every feature.
// Modules arrive as the roadmap reaches them (DESKTOP_KMP_PLAN.md §2.1). Empty
// modules still cost configuration time, so core:filestore and core:webview
// land in M6/M8 rather than now.
// ---------------------------------------------------------------------------
include(":core:common")
include(":core:config")
include(":core:security")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":core:localization")
include(":core:socket")
include(":core:remoteconfig")
include(":core:permissions")
include(":core:badges")
include(":core:notifications")
include(":core:units")
include(":core:session")
include(":core:designsystem")
include(":core:mvvm")
include(":core:workspace")

// ---------------------------------------------------------------------------
// feature — one module per product area. `shell` is the app frame that hosts
// the workspace; the rest arrive per roadmap phase.
// ---------------------------------------------------------------------------
include(":feature:accounthub")
include(":feature:auth")
include(":feature:boxschedule")
include(":feature:budgetbuilder")
include(":feature:calls")
include(":feature:cardexpenses")
include(":feature:cashexpenses")
include(":feature:chat")
include(":feature:callsheet")
include(":feature:dealmemo")
include(":feature:documentdistribution")
include(":feature:drive")
include(":feature:home")
include(":feature:maps")
include(":feature:payroll")
include(":feature:productionreport")
include(":feature:purchaseorder")
include(":feature:email")
include(":feature:esignature")
include(":feature:formsignature")
include(":feature:settings")
include(":feature:sides")
include(":feature:timecard")
include(":feature:shell")
