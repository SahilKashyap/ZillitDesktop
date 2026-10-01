import java.util.UUID
import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * The JetBrains Runtime, for compiling *and* running.
 *
 * The call media engine is embedded Chromium, and JBR is the only JDK that
 * ships one: the CEF framework, its helper processes, and the `org.cef`
 * classes as a platform module. That module also takes precedence over
 * anything on the classpath, so a second JCEF added as a dependency cannot
 * win — it merely compiles against classes that a different build then
 * replaces at runtime. Pinning both ends to JBR keeps one JCEF in play.
 */
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jvmToolchain.get()))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

dependencies {
    implementation(project(":core:appupdate"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:datastore"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:shell"))
    implementation(project(":feature:auth"))
    implementation(project(":core:config"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:remoteconfig"))
    implementation(project(":core:permissions"))
    implementation(project(":core:badges"))
    implementation(project(":core:notifications"))
    implementation(project(":core:media"))
    implementation(project(":core:locationpicker"))
    implementation(project(":feature:notifications"))
    implementation(project(":feature:sos"))
    implementation(project(":core:units"))
    implementation(project(":core:database"))
    implementation(project(":core:sync"))
    implementation(project(":core:localization"))
    implementation(project(":core:strings"))
    implementation(project(":core:session"))
    implementation(project(":core:socket"))
    implementation(project(":feature:accounthub"))
    implementation(project(":feature:budget"))
    implementation(project(":feature:castboard"))
    implementation(project(":feature:budgetbuilder"))
    implementation(project(":feature:esignature"))
    implementation(project(":feature:formsignature"))
    implementation(project(":feature:home"))
    implementation(project(":feature:calls"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:email"))
    implementation(project(":feature:cashexpenses"))
    implementation(project(":feature:cardexpenses"))
    implementation(project(":feature:purchaseorder"))
    implementation(project(":feature:timecard"))
    implementation(project(":feature:payroll"))
    implementation(project(":feature:boxschedule"))
    implementation(project(":feature:callsheet"))
    implementation(project(":feature:maps"))
    implementation(project(":feature:permissiongrid"))
    implementation(project(":feature:sides"))
    implementation(project(":feature:dealmemo"))
    implementation(project(":feature:productionreport"))
    implementation(project(":feature:documentdistribution"))
    implementation(project(":feature:drive"))
    implementation(project(":feature:pagedistribution"))
    implementation(project(":feature:assetreport"))
    implementation(project(":feature:crewlist"))
    implementation(project(":feature:distribution"))
    implementation(project(":feature:externalusers"))
    implementation(project(":feature:recce"))
    implementation(project(":feature:location"))
    implementation(project(":feature:draft"))
    implementation(project(":feature:continuity"))
    implementation(project(":feature:costreport"))
    implementation(project(":feature:addashboard"))
    implementation(project(":feature:saportal"))
    implementation(project(":core:forms"))
    implementation(project(":feature:taxfiling"))
    implementation(project(":feature:costumesetsync"))
    implementation(project(":feature:bankrec"))
    implementation(project(":feature:invoices"))
    implementation(project(":feature:transportation"))
    implementation(project(":feature:weather"))

    // The app module had no tests until the single-instance guard, which is
    // logic rather than wiring and worth pinning — particularly its behaviour
    // after a crash, which is the case a naive implementation gets wrong.
    testImplementation(kotlin("test"))
    // The screen-source helper is a process and a flow; testing it needs a
    // scheduler that does not actually sleep.
    testImplementation(libs.kotlinx.coroutines.test)

    implementation(compose.desktop.currentOs)
    // Already on the runtime classpath transitively; declared so the Drive
    // widget's desktop-layer call (DesktopWindowLevel) can compile against it.
    implementation("net.java.dev.jna:jna:5.13.0")
    // Runs the payroll service's published OT-engine bundle — see RhinoScriptHost.
    implementation(libs.rhino)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.datetime)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
}

/**
 * Forwards `-Pzillit.env` / `-Pzillit.config` / `-Pzillit.variant` from the
 * Gradle command line into the **application's** JVM.
 *
 * Without this, `./gradlew :desktopApp:run -Dzillit.env=qa` sets the property on
 * *Gradle's* JVM and the app never sees it — so it silently falls back to
 * production. Which environment you are talking to is not something to discover
 * by accident.
 *
 *   ./gradlew :desktopApp:run -Pzillit.env=qa
 *   ./gradlew :desktopApp:run -Pzillit.env=qa -Pzillit.http=body
 *   ./gradlew :desktopApp:run -Pzillit.env=qa -Pzillit.variant=qa
 */
tasks.withType<JavaExec>().configureEach {
    listOf("zillit.env", "zillit.config", "zillit.http", "zillit.variant").forEach { key ->
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
}

// The runtime the app is launched and packaged with — the same JBR the
// toolchain above compiles against, because the media engine's Chromium comes
// out of it.
val jetbrainsRuntime = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.jvmToolchain.get()))
    vendor.set(JvmVendorSpec.JETBRAINS)
}

/*
 * The Chromium the packaged app is missing.
 *
 * `modules("jcef")` gets jlink to put JCEF's Java classes and `libjcef.dylib`
 * into the runtime image, and that is as far as jlink can go: the Chromium
 * Embedded Framework is not a Java module. It is a macOS framework bundle
 * living beside the runtime in the JBR's own layout, along with the four
 * `jcef Helper` apps Chromium spawns as subprocesses, and jlink has no reason
 * to know about any of it.
 *
 * So a packaged build shipped `libjcef.dylib` with nothing underneath it. The
 * app started, signed in, and roughly thirty seconds later `CefApp.N_Initialize`
 * dlopened a framework that was not there, got null, and dereferenced it:
 *
 *     dlopen …/runtime/Contents/Frameworks/Chromium Embedded Framework… (no such file)
 *     SIGSEGV … C [libjcef.dylib+0x3f918] FindClass(JNIEnv*, char const*)
 *
 * `./gradlew :desktopApp:run` never showed it, because that runs on the whole
 * JBR — where the frameworks are — rather than on the trimmed image. Only the
 * DMG was broken, which is the worst place for a crash to hide.
 *
 * Copied with `ditto` rather than a Gradle `Copy`: these bundles are full of
 * symlinks (`Versions/Current`, `Frameworks/…/Libraries`) that a plain copy
 * flattens, which breaks the bundle and would break its signature with it.
 */
/*
 * Beside the JDK home, not inside it.
 *
 * `installationPath` is `<bundle>/Contents/Home` — the JDK home jpackage wants.
 * The frameworks are its sibling, `<bundle>/Contents/Frameworks`, so this walks
 * up one level rather than down. Resolving "Contents/Frameworks" against the
 * home instead yields `Contents/Home/Contents/Frameworks`, which does not
 * exist, and the silent result is that this whole block is skipped and the
 * crash ships exactly as before.
 */
/**
 * The build's own version, from `zillit.version` in gradle.properties.
 *
 * jpackage stamps it on the installer and into the launcher's
 * `-Djpackage.app-version`, but that property only exists in a packaged
 * launch: under `:desktopApp:run` (or the IDE) there is no `.cfg` and the app
 * had no idea what version it was — Settings could not show one and the
 * update check switched itself off. So the same value is also compiled in,
 * as `com.zillit.desktop.BuildInfo`, and both readers agree by construction.
 *
 * The short git sha rides along for the Settings page: two testers on "1.0.2"
 * are on the same build only if the sha matches, and a bug report that quotes
 * it saves a round trip. Absent (empty) when there is no git — a source zip.
 */
val zillitVersion: String = providers.gradleProperty("zillit.version").getOrElse("0.0.0")

val zillitGitSha: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("")

/**
 * Which build this is — the thing that lets a production, QA and develop
 * install of Zillit sit on one machine at once instead of overwriting each
 * other (build variants).
 *
 * Defaults to `-PzillitEnv`, because anyone packaging a non-production build
 * is already passing that to pick the server it talks to; override the
 * variant on its own with `-PzillitVariant` if the two ever need to differ.
 * Absent, blank, "prod" and "production" all mean production, so a plain
 * `packageDmg` reproduces today's app exactly — same name, same bundle id,
 * same data directory — with no migration for existing installs.
 *
 *   ./gradlew :desktopApp:packageDmg -PzillitEnv=qa
 *   ./gradlew :desktopApp:packageDmg -PzillitEnv=develop
 *   ./gradlew :desktopApp:packageDmg                       # production, unchanged
 */
val zillitVariant: String = providers.gradleProperty("zillitVariant")
    .orElse(providers.gradleProperty("zillitEnv"))
    .getOrElse("")
    .trim()
    .lowercase()
    .let { if (it == "prod" || it == "production") "" else it }

/** A word for a person to read: blank for production, "QA" / "Dev" otherwise. */
val zillitVariantLabel: String = when (zillitVariant) {
    "" -> ""
    "qa" -> "QA"
    "develop" -> "Dev"
    else -> zillitVariant.replaceFirstChar(Char::uppercase)
}

/**
 * This build's macOS bundle id (also its LaunchAgent label and the
 * identifier the notification/screen-share helpers are signed under below).
 * Must agree with `ZillitVariant.bundleId` at runtime (core:common) — both
 * follow the same rule off the same `zillitVariant` rather than one
 * hardcoding what the other computes.
 */
val zillitBundleId: String = if (zillitVariant.isEmpty()) "com.zillit.desktop" else "com.zillit.desktop.$zillitVariant"

val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "Writes com.zillit.desktop.BuildInfo from zillit.version, the git sha and the variant."
    val outDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
    val version = zillitVersion
    val sha = zillitGitSha
    val variant = zillitVariant
    inputs.property("version", version)
    inputs.property("gitSha", sha)
    inputs.property("variant", variant)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/zillit/desktop/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.zillit.desktop
            |
            |/**
            | * Generated by `:desktopApp:generateBuildInfo` — do not edit, do not commit.
            | * The version comes from `zillit.version` in gradle.properties.
            | */
            |internal object BuildInfo {
            |    const val VERSION: String = "$version"
            |    const val GIT_SHA: String = "$sha"
            |    /** "", "qa" or "develop" — see `zillitVariant` in desktopApp/build.gradle.kts. */
            |    const val VARIANT: String = "$variant"
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(generateBuildInfo)

// The product's name everywhere a user sees a file: the .app bundle, the DMG,
// the Windows installer, the dock and the menu bar. Suffixed with the variant
// label so a QA or develop build installs *beside* production rather than
// over it. For production the bundle id stays exactly `com.zillit.desktop` —
// renaming the bundle must not re-identify the app.
val desktopPackageName = "Zillit-Desktop" + if (zillitVariantLabel.isEmpty()) "" else "-$zillitVariantLabel"

/*
 * The packaging metadata, named out here rather than only inside
 * `nativeDistributions`, because `packageSignedMsi` drives jpackage itself and
 * has to pass the *same* values. Two sources of truth for them would not fail
 * any build; the `upgradeUuid` in particular would ship an in-app update that
 * installs *beside* the build it was meant to replace, and the only symptom is
 * two Zillits in the Start menu on someone else's machine.
 */
val desktopDescription = "Zillit Desktop"
val desktopVendor = "Zillit"
val windowsMenuGroup = "Zillit" + if (zillitVariantLabel.isEmpty()) "" else " $zillitVariantLabel"

/*
 * Stable per-variant UUID — required for MSI upgrades to replace rather than
 * install alongside. An upgradeUuid is exactly how WiX/jpackage tells two
 * products apart, so a QA or develop build needs its OWN id: sharing
 * production's would make installing it silently replace production instead of
 * sitting beside it. Do not regenerate any of these.
 */
val windowsUpgradeUuid = when (zillitVariant) {
    "" -> "8F5D2C41-9A3E-4B7C-BE21-6D4A0F3E9C58"
    "qa" -> "B3C6E6F1-4E8A-4E6B-9E36-5B9A6E1F0A2D"
    "develop" -> "0E7D9C2B-1A3F-4C5E-8D2A-7F6B4C9E3A1B"
    // An unrecognised variant still needs a stable id so repackaging it twice
    // upgrades rather than duplicates; deterministic from the variant string
    // rather than hardcoded, since nothing named it in advance.
    else -> UUID.nameUUIDFromBytes("zillit-desktop-variant-$zillitVariant".toByteArray()).toString()
}

val jbrFrameworks = File(jetbrainsRuntime.get().metadata.installationPath.asFile.parentFile, "Frameworks")

// Registered only where there is something to copy — macOS. Decided here at
// configuration time rather than with `onlyIf`, because a task-level predicate
// closes over this script and the configuration cache cannot serialise that.
if (jbrFrameworks.isDirectory) {
    val copyCefFrameworks = tasks.register<Exec>("copyCefFrameworks") {
        dependsOn("createDistributable")
        description = "Copies the Chromium Embedded Framework into the packaged runtime."

        val destination = layout.buildDirectory
            .dir("compose/binaries/main/app/$desktopPackageName.app/Contents/runtime/Contents/Frameworks")

        /*
         * The helpers are re-signed ad hoc with this app's entitlements right
         * after the copy, and not only in the signed build.
         *
         * Chromium captures the camera in a utility process — `jcef Helper` —
         * not in the app. JetBrains ships that helper with the hardened
         * runtime and no `com.apple.security.device.camera`, and a hardened
         * process without the entitlement gets a running capture session
         * that delivers no frames (measured 2026-09-08: 0 frames in 4 s
         * without it, 42 with it). The signed build re-signs these anyway
         * (resignWithFrameworks); this covers the ad-hoc bundle everyone
         * develops against.
         *
         * Necessary, not sufficient: the black video of 2026-09-08 was
         * finally the camera grant itself, which Chromium never requests —
         * see MediaAccess.kt.
         *
         * Without `--options runtime`: a helper with no hardened runtime is
         * not subject to the entitlement check at all, so the camera works
         * either way, and this is the variant verified live. The
         * entitlements are kept so the two builds' helpers read the same.
         *
         * If every launch traps in CrBrowserMain right after "call page
         * loading", suspect Chromium's shared profile before this signing:
         * every JCEF instance on the machine shares
         * ~/Library/Application Support/CEF/User Data, and one that crashed
         * mid-start leaves it in a state the next start dies on. Moving that
         * directory aside cured exactly that on 2026-09-08.
         */
        val entitlements = project.file("entitlements.plist").absolutePath
        commandLine(
            "bash", "-c",
            """
            set -euo pipefail
            src="${'$'}1"; dest="${'$'}2"; entitlements="${'$'}3"
            ditto "${'$'}src" "${'$'}dest"
            for helper in "${'$'}dest"/jcef\ Helper*.app; do
                [ -d "${'$'}helper" ] || continue
                codesign --force --entitlements "${'$'}entitlements" --sign - "${'$'}helper"
            done
            """.trimIndent(),
            "copyCefFrameworks",
            jbrFrameworks.absolutePath,
            destination.get().asFile.absolutePath,
            entitlements,
        )
    }

    /*
     * Everything `createDistributable` could not sign, plus the seals the ditto
     * above broke. All four steps are here because Apple rejected a build for
     * each of them; none are precautionary.
     *
     * The trap is that `codesign --verify --strict` on the .app passes while
     * three of these are still wrong — it checks the outer seal and stops.
     * Apple's notary walks every executable in the archive. Verify with
     * `--deep`, or the first thing that tells you is a rejection email.
     *
     * 1. Native libraries inside jars. Compose's own jar signer matches
     *    `.jnilib` and nothing else, so `osxkeychain.so` inside jkeychain
     *    reached Apple carrying the linker's ad-hoc signature: "The binary is
     *    not signed", for both architectures. jna, skiko and sqlite-jdbc ship
     *    the same problem in `.dylib` form.
     *
     * 2. The JBR frameworks the ditto just copied are signed by JetBrains.
     *    Every executable in the archive has to carry our Developer ID.
     *
     * 3. `Contents/runtime` is a bundle, and the ditto wrote into it, so its
     *    own seal broke along with the app's. A `--deep` sign of the .app does
     *    NOT reach it: --deep walks the standard nested-code locations, and
     *    jpackage's runtime is not one of them. Signing it explicitly is the
     *    whole fix for "libjli.dylib: the signature of the binary is invalid".
     *
     * 4. The outer bundle last, because steps 1-3 all invalidated it.
     *
     * Moving the copy before `createDistributable` would avoid 2-4 and is not
     * available: that is the cycle noted below.
     */
    /*
     * The notification helper.
     *
     * Compose posts notifications through `java.awt.TrayIcon.displayMessage`,
     * which the JDK implements on macOS with `NSUserNotificationCenter` —
     * deprecated in 10.14 and inert since. Nothing throws and nothing arrives,
     * which is why the Windows build notifies (AWT calls `Shell_NotifyIcon`
     * there) and this one does not. `ZillitNotify.swift` is the same job on the
     * API that replaced it; TrayNotifier runs it instead.
     *
     * Compiled into `Contents/MacOS/` because a binary there inherits the app's
     * bundle identity, which `UNUserNotificationCenter` requires and which also
     * decides whose name appears on the banner.
     *
     * Skipped when there is no Swift compiler, rather than failing the build: a
     * machine without Xcode can still produce a DMG, and TrayNotifier falls
     * back to the tray path when the helper is absent.
     */
    val notifyHelperSource = project.file("src/main/native/ZillitNotify.swift")
    val swiftCompiler = File("/usr/bin/swiftc")

    /*
     * The architecture the Swift helpers are built for.
     *
     * `swiftc` with no `-target` builds for the machine doing the building, and
     * that is right for every ordinary build. It is wrong for a cross build: an
     * Intel DMG produced on Apple silicon would carry arm64 helpers inside an
     * x86_64 app, so screen sharing and notifications would be missing on the
     * only machines that DMG is for — and the bundle would not even be signable
     * as one architecture.
     *
     *     -PzillitSwiftTarget=x86_64-apple-macos13.0
     *
     * Absent by default, so nothing changes for a native build.
     */
    val swiftTarget = providers.gradleProperty("zillitSwiftTarget").orNull
    val swiftArchArgs = swiftTarget?.let { listOf("-target", it) }.orEmpty()
    if (swiftTarget != null) {
        logger.lifecycle("Building the Swift helpers for $swiftTarget")
    }

    val notifyHelper = if (swiftCompiler.canExecute() && notifyHelperSource.isFile) {
        tasks.register<Exec>("compileNotifyHelper") {
            dependsOn("createDistributable")
            description = "Compiles the macOS notification helper into the app bundle."

            val destination = layout.buildDirectory
                .dir("compose/binaries/main/app/$desktopPackageName.app/Contents/MacOS")
                .get().asFile

            commandLine(
                swiftCompiler.absolutePath,
                "-O",
                "-o", File(destination, "zillit-notify").absolutePath,
                notifyHelperSource.absolutePath,
                "-framework", "UserNotifications",
                "-framework", "AVFoundation",
                "-framework", "AppKit",
                *swiftArchArgs.toTypedArray(),
            )
        }
    } else {
        logger.lifecycle("No Swift compiler; packaging without the notification helper")
        null
    }

    /*
     * The screen-share source helper.
     *
     * The call page is embedded Chromium, and embedded Chromium has no
     * "Choose what to share" dialog — that lives in the browser shell, not in
     * the content layer. So the app draws its own picker, and a picker needs a
     * list of screens and windows with pictures. ScreenCaptureKit is the only
     * API that still provides them: `CGWindowListCreateImage`, which JNA could
     * have reached, is gone from the macOS 26 SDK.
     *
     * In `Contents/MacOS/` for the same reason as the notification helper — a
     * binary there inherits the app's bundle identity, so the Screen Recording
     * permission it needs is asked for, and remembered, as Zillit's.
     *
     * Skipped without a Swift compiler, like the other one: the picker then
     * has nothing to list and the app shares the whole screen, which is what
     * it did before the picker existed.
     */
    val captureHelperSource = project.file("src/main/native/ZillitCapture.swift")

    val captureHelper = if (swiftCompiler.canExecute() && captureHelperSource.isFile) {
        tasks.register<Exec>("compileCaptureHelper") {
            dependsOn("createDistributable")
            description = "Compiles the macOS screen-share source helper into the app bundle."

            val destination = layout.buildDirectory
                .dir("compose/binaries/main/app/$desktopPackageName.app/Contents/MacOS")
                .get().asFile

            commandLine(
                swiftCompiler.absolutePath,
                "-O",
                "-o", File(destination, "zillit-capture").absolutePath,
                captureHelperSource.absolutePath,
                "-framework", "ScreenCaptureKit",
                "-framework", "AppKit",
                *swiftArchArgs.toTypedArray(),
            )
        }
    } else {
        logger.lifecycle("No Swift compiler; packaging without the screen-share source helper")
        null
    }

    /*
     * Skia's native library, for a cross build.
     *
     * Compose extracts `libskiko-macos-<arch>.dylib` beside the jars and points
     * the app at it with `-Dskiko.library.path=$APPDIR`. It picks the arch from
     * the machine running Gradle, so a cross build extracts nothing usable and
     * the app looks in $APPDIR and nowhere else: the JVM comes up, the config
     * loads, and then the first Path throws ExceptionInInitializerError. The
     * launcher reports only "Failed to launch JVM", which says nothing about
     * Skia — this cost an afternoon, hence the comment.
     *
     * The runtime jar carries every architecture, so the fix is to unpack the
     * one this build is actually for.
     */
    val skikoArch = providers.gradleProperty("zillitMacArch").orNull?.let {
        if (it == "x64") "x64" else "arm64"
    }
    val stageSkikoNative = if (skikoArch != null) {
        tasks.register("stageSkikoNative") {
            dependsOn("createDistributable")
            description = "Unpacks the $skikoArch Skia library the packaged app loads at runtime."

            val appDir = layout.buildDirectory
                .dir("compose/binaries/main/app/$desktopPackageName.app/Contents/app")

            doLast {
                val dir = appDir.get().asFile
                val jar = dir.listFiles()
                    ?.firstOrNull { it.name.startsWith("skiko-awt-runtime-") && it.name.endsWith(".jar") }
                    ?: error("stageSkikoNative: no skiko runtime jar in ${dir.path}")

                val wanted = listOf(
                    "libskiko-macos-$skikoArch.dylib",
                    "libskiko-macos-$skikoArch.dylib.sha256",
                )
                ZipFile(jar).use { zip ->
                    wanted.forEach { name ->
                        val entry = zip.getEntry(name)
                            ?: error("stageSkikoNative: $name is not in ${jar.name}")
                        zip.getInputStream(entry).use { input ->
                            File(dir, name).outputStream().use { input.copyTo(it) }
                        }
                    }
                }
                logger.lifecycle("Staged libskiko-macos-$skikoArch.dylib into the app bundle")
            }
        }
    } else {
        null
    }

    val resignIdentity = providers.gradleProperty("zillitSigningIdentity")

    // Unsigned builds keep the old graph exactly — nothing to sign.
    val distributableReady = if (resignIdentity.isPresent) {
        tasks.register<Exec>("resignWithFrameworks") {
            dependsOn(copyCefFrameworks)
            notifyHelper?.let { dependsOn(it) }
            captureHelper?.let { dependsOn(it) }
            // Before the re-seal, so the staged library is inside the signature.
            stageSkikoNative?.let { dependsOn(it) }
            description = "Signs what createDistributable missed, then re-seals the bundle."

            val app = layout.buildDirectory
                .dir("compose/binaries/main/app/$desktopPackageName.app").get().asFile.absolutePath

            // Paths arrive as positional arguments rather than interpolated, so
            // a space in the build directory cannot split a word.
            commandLine(
                "bash", "-c",
                """
                set -euo pipefail
                app="${'$'}1"; identity="${'$'}2"; entitlements="${'$'}3"; bundleId="${'$'}4"

                staging="${'$'}(mktemp -d)"
                trap 'rm -rf "${'$'}staging"' EXIT

                for jar in "${'$'}app"/Contents/app/*.jar; do
                    entries="${'$'}(unzip -Z1 "${'$'}jar" '*.so' '*.dylib' '*.jnilib' 2>/dev/null || true)"
                    [ -n "${'$'}entries" ] || continue

                    rm -rf "${'$'}{staging:?}"/*
                    printf '%s\n' "${'$'}entries" | while IFS= read -r entry; do
                        [ -n "${'$'}entry" ] || continue
                        # One entry per call. Handing unzip all three patterns at
                        # once makes it exit 11 whenever one of them matches
                        # nothing — true of every jar here — and `set -e` turns
                        # that into a build failure on a jar that extracted fine.
                        ( cd "${'$'}staging" && unzip -qo "${'$'}jar" "${'$'}entry" )
                        codesign --force --options runtime --timestamp \
                            --sign "${'$'}identity" "${'$'}staging/${'$'}entry"
                    done

                    # Replaces the entries in place, keeping their paths.
                    ( cd "${'$'}staging" && printf '%s\n' "${'$'}entries" | zip -q "${'$'}jar" -@ )
                done

                for bundle in "${'$'}app"/Contents/runtime/Contents/Frameworks/*; do
                    [ -e "${'$'}bundle" ] || continue
                    codesign --force --deep --options runtime --timestamp \
                        --entitlements "${'$'}entitlements" --sign "${'$'}identity" "${'$'}bundle"
                done

                # Signed under the app's own identifier, not its own: the
                # notification daemon checks the caller's code identity against
                # the bundle whose identity it claims, and refuses the request
                # outright when they disagree.
                # Both are signed under the app's own identifier, not their
                # own: a daemon checks the caller's code identity against the
                # bundle whose identity it claims and refuses when they
                # disagree. For zillit-notify that decides whether the banner
                # is delivered; for zillit-capture it decides whether the
                # Screen Recording grant the user gave Zillit counts as this
                # helper's grant too. `bundleId` is THIS variant's id — a QA
                # or develop build signed under production's identifier would
                # pass codesign but fail both checks at runtime, because the
                # daemon compares against the bundle actually on disk.
                for helper in zillit-notify zillit-capture; do
                    path="${'$'}app/Contents/MacOS/${'$'}helper"
                    [ -f "${'$'}path" ] || continue
                    codesign --force --identifier "${'$'}bundleId" --options runtime \
                        --timestamp --sign "${'$'}identity" "${'$'}path"
                done

                codesign --force --options runtime --timestamp \
                    --entitlements "${'$'}entitlements" --sign "${'$'}identity" "${'$'}app/Contents/runtime"

                codesign --force --options runtime --timestamp \
                    --entitlements "${'$'}entitlements" --sign "${'$'}identity" "${'$'}app"

                codesign --verify --deep --strict "${'$'}app"
                """.trimIndent(),
                "resignWithFrameworks",
                app,
                resignIdentity.get(),
                project.file("entitlements.plist").absolutePath,
                zillitBundleId,
            )
        }
    } else {
        copyCefFrameworks
    }

    // Everything that consumes the distributable needs the frameworks in it
    // first. `createDistributable` must not depend on this — that is a cycle.
    listOf("packageDmg", "packageDistributionForCurrentOS", "runDistributable", "notarizeDmg")
        .forEach { consumer ->
            tasks.matching { it.name == consumer }.configureEach { dependsOn(distributableReady) }
        }
}

/*
 * The same hole on Windows, in Windows' layout.
 *
 * Windows has no framework bundle — CEF ships flat in the JBR's `bin`, so the
 * DLLs sit beside `jcef.dll` and jlink brings them along. That is the whole of
 * what it brings: a helper *executable*, a `.dat`, three `.pak`s, a `.bin` and
 * the `locales` directory are not libraries, so the trimmed runtime gets the
 * loader and none of what it loads. `libcef.dll` is present and every check
 * that looks for it passes.
 *
 * The failure is the macOS one wearing different clothes — `N_Initialize`
 * reaching for what is not there — and it aborts the process rather than
 * throwing, so the `runCatching` around `CefApp.startup` never sees it:
 *
 *     Internal Error (os_windows_x86.cpp:144)
 *     guarantee(result == EXCEPTION_CONTINUE_EXECUTION) failed
 *     j org.cef.CefApp.N_Initialize(...)+0 jcef
 *
 * `./gradlew :desktopApp:run` cannot show this either: it runs on the whole
 * JBR, where the payload is. Only the packaged app is broken — and it starts,
 * signs in and renders before dying, which reads as a runtime fault rather
 * than a packaging one.
 *
 * A plain `Copy` is enough here; there are no symlinks to flatten and no
 * signature to break, which is why this is not the `ditto` dance above.
 */
val jbrBin = File(jetbrainsRuntime.get().metadata.installationPath.asFile, "bin")

// Registered only where there is something to copy, decided at configuration
// time for the same reason as the macOS block: an `onlyIf` predicate closes
// over this script and the configuration cache cannot serialise that.
if (File(jbrBin, "jcef_helper.exe").isFile) {
    /*
     * Into jlink's runtime image, not the app directory beside it.
     *
     * `createDistributable` and `packageExe` are siblings, not a chain: each
     * takes this image and lays out its own copy, and jpackage builds the
     * installer's payload itself. Patching `binaries/main/app/…` therefore
     * fixes the directory `runDistributable` uses and nothing that ships —
     * the app image runs, the installer carries `libcef.dll` with none of its
     * payload, and the crash comes back on the installed copy alone.
     */
    val runtimeImageBin = layout.buildDirectory.dir("compose/tmp/main/runtime/bin")

    val copyCefResources = tasks.register<Copy>("copyCefResources") {
        dependsOn("createRuntimeImage")
        description = "Copies CEF's data files and subprocess helper into the jlink runtime image."

        // Named rather than globbed: a wildcard over `bin` would also sweep in
        // the JDK tooling jlink deliberately left out.
        from(jbrBin) {
            include(
                "jcef_helper.exe",
                "cef_server.exe",
                "icudtl.dat",
                "v8_context_snapshot.bin",
                "resources.pak",
                "chrome_100_percent.pak",
                "chrome_200_percent.pak",
            )
        }
        // Chromium resolves these relative to the process, so they land beside
        // the DLLs rather than in a directory of their own.
        from(File(jbrBin, "locales")) { into("locales") }

        into(runtimeImageBin)

        // jlink rewrites this image, so a copy Gradle considers up to date can
        // have had its output deleted underneath it.
        outputs.upToDateWhen { false }

        // `Copy` creates whatever destination it is given, so pointing at the
        // wrong directory succeeds and packages nothing — which is exactly how
        // the first version of this shipped broken. `libcef.dll` is jlink's
        // own output and marks the image this must land in.
        doFirst {
            val marker = runtimeImageBin.get().file("libcef.dll").asFile
            check(marker.isFile) {
                "copyCefResources: no libcef.dll in ${marker.parent}. The runtime image " +
                    "is not where this expects it, so CEF's payload would be copied somewhere " +
                    "nothing packages and the installed app would crash in N_Initialize."
            }
        }
    }

    // Everything that lays out or wraps the runtime needs the payload in it
    // first, `createDistributable` included — it is a consumer here, not the
    // producer this hangs off.
    listOf(
        "createDistributable",
        "packageExe",
        "packageMsi",
        "packageDistributionForCurrentOS",
        "runDistributable",
    ).forEach { consumer ->
        tasks.matching { it.name == consumer }.configureEach { dependsOn(copyCefResources) }
    }
}

/*
 * Authenticode signing, Windows.
 *
 * Opt-in exactly as the macOS identity above is: with no certificate named,
 * the task graph is untouched and a developer without one packages as before.
 * Name one and every installer this build writes is signed and timestamped
 * before it can be handed to anybody.
 *
 * ## What this buys, and what it does not
 *
 * `WindowsInstaller` (core:appupdate) refuses any package that
 * `Get-AuthenticodeSignature` does not call `Valid`, so signing the installer
 * is what makes an in-app update possible at all. Unsigned, the app can only
 * download the file and ask the person to run it themselves.
 *
 * `packageMsi` and `packageExe` sign the installer and nothing else. The
 * launcher *inside* those packages stays unsigned, so the second half of the
 * updater's check — the incoming package's signer subject against the
 * signature on the running build's launcher — never engages, and installs fall
 * back to accepting any valid signature whose digest matches the one published
 * in Remote Config. The reason is that neither task consumes
 * `createDistributable`: Compose hands jpackage an `--app-image` only on
 * macOS, and on Windows it lays out the payload from the runtime image
 * instead — the same fact `copyCefResources` above exists for.
 *
 * `packageSignedMsi` below is the way out, and the task a Windows release
 * people receive in-app should use: it signs the launcher in the app image and
 * then runs jpackage over that image with `--app-image`, so the signature
 * ships inside the installer and the subject comparison engages.
 *
 * ## Naming the certificate
 *
 * Since June 2023 the CA/Browser Forum has required code-signing keys to be
 * generated on FIPS 140-2 Level 2 hardware, so a public CA no longer issues a
 * .pfx at all: an OV certificate arrives on a USB token, an HSM-backed one is
 * reached over the network. Each shape below is what `signtool` wants for one
 * of those, and a .pfx remains for the self-signed certificate used to
 * exercise the update path before a real one is bought.
 *
 *     -PzillitAzureSigningEndpoint=https://eus.codesigning.azure.net
 *     -PzillitAzureSigningAccount=<account> -PzillitAzureSigningProfile=<profile>
 *                                                    Azure Artifact Signing
 *     -PzillitWindowsSigningCert=3A7F…               by thumbprint, 40 hex
 *     -PzillitWindowsSigningCert="Zillit Pvt Ltd"    by subject
 *     -PzillitWindowsSigningCert=C:\zillit.pfx       a file, with …Password
 *     -PzillitWindowsSigningDlib=… -PzillitWindowsSigningDmdf=…
 *                                                    any dlib, named by hand
 *
 * Prefer Azure Artifact Signing, and otherwise the subject: the updater
 * compares subjects rather than thumbprints — precisely so that renewing the
 * certificate, which every one of them needs yearly, does not strand every
 * install on the release before it — and a build pinned here to a thumbprint
 * would hand out packages those installs no longer recognise. Azure Artifact
 * Signing is the easiest of them to keep stable that way: its certificates are
 * reissued every three days, but the subject comes from the one-time identity
 * validation and does not move with them.
 *
 * No secret belongs in the repo's gradle.properties. A password, where the
 * shape needs one at all, goes in ~/.gradle/gradle.properties or the
 * environment — and note that one given here is written into Gradle's
 * configuration cache under `build/`, while the thumbprint, subject and Azure
 * forms need no password and leave nothing behind. Azure authenticates through
 * `DefaultAzureCredential`, which is to say `az login` on a workstation or
 * AZURE_CLIENT_ID/AZURE_TENANT_ID/AZURE_CLIENT_SECRET on a runner; none of the
 * three properties above is itself a secret.
 */
val windowsSigningCert = providers.gradleProperty("zillitWindowsSigningCert")
val windowsSigningDlib = providers.gradleProperty("zillitWindowsSigningDlib")
val azureSigningEndpoint = providers.gradleProperty("zillitAzureSigningEndpoint")
val azureSigningAccount = providers.gradleProperty("zillitAzureSigningAccount")
val azureSigningProfile = providers.gradleProperty("zillitAzureSigningProfile")

// All three, or none: a half-named account cannot sign, and finding that out
// from signtool's 403 after a twenty-minute package is the worst place to.
val azureSigning = azureSigningEndpoint.isPresent || azureSigningAccount.isPresent ||
    azureSigningProfile.isPresent
if (azureSigning) {
    require(azureSigningEndpoint.isPresent && azureSigningAccount.isPresent && azureSigningProfile.isPresent) {
        "Azure Artifact Signing needs all three of -PzillitAzureSigningEndpoint, " +
            "-PzillitAzureSigningAccount and -PzillitAzureSigningProfile. " +
            "Given: " + listOf(
            "Endpoint" to azureSigningEndpoint, "Account" to azureSigningAccount,
            "Profile" to azureSigningProfile,
        ).filter { it.second.isPresent }.joinToString { it.first }.ifEmpty { "none" }
    }
}

if (windowsSigningCert.isPresent || windowsSigningDlib.isPresent || azureSigning) {
    /*
     * The dlib, for the Azure path.
     *
     * `signtool` cannot reach the service on its own: the signing happens
     * inside `Azure.CodeSigning.Dlib.dll`, which is not part of Windows and
     * arrives either with the Artifact Signing Client Tools installer
     * (`winget install -e --id Microsoft.Azure.ArtifactSigningClientTools`) or
     * as the `Microsoft.ArtifactSigning.Client` NuGet package. Named by hand
     * with -PzillitWindowsSigningDlib; otherwise found by looking where those
     * two put it, matched on the directory *name* rather than a guessed exact
     * path, since the installer's own has moved with the service's rename.
     *
     * The client tools install **per user**, which is why LOCALAPPDATA is
     * searched and not only Program Files: as of version 0.1.128 the dll lands
     * in `%LOCALAPPDATA%\Microsoft\MicrosoftArtifactSigningClientTools`, loose
     * in the directory rather than under the `bin\x64\` that the NuGet package
     * and Microsoft's own documentation describe.
     */
    // Deep enough for `<root>\<version>\bin\x64\`, shallow enough that a wrong
    // root costs nothing: this walks Program Files, and an unbounded descent
    // there is a visible pause on every configure.
    val dlibSearchDepth = 6
    val azureDlib: File? = if (azureSigning && !windowsSigningDlib.isPresent) {
        val home = File(System.getProperty("user.home"))
        val local = System.getenv("LOCALAPPDATA")
        val installed = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            local,
            local?.let { "$it\\Microsoft" },
        )
            .map(::File)
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isDirectory && it.name.contains("signing", ignoreCase = true) }
        val nuget = listOf("microsoft.artifactsigning.client", "microsoft.trusted.signing.client")
            .map { File(home, ".nuget\\packages\\$it") }
            .filter(File::isDirectory)
        (installed + nuget)
            .asSequence()
            .flatMap { root -> root.walkTopDown().maxDepth(dlibSearchDepth) }
            .filter { it.name.equals("Azure.CodeSigning.Dlib.dll", ignoreCase = true) && it.isFile }
            // The dlib has to match signtool's architecture, and we run the x64
            // one — an x86 dll beside it in the same package loads into nothing
            // and says so obscurely. Path order second, so that where several
            // versions of the NuGet package are unpacked the newest wins.
            .sortedWith(
                compareByDescending<File> { it.path.contains("x64", ignoreCase = true) }
                    .thenByDescending { it.path },
            )
            .firstOrNull()
    } else {
        null
    }
    if (azureSigning) {
        requireNotNull(windowsSigningDlib.orNull ?: azureDlib?.path) {
            "Azure Artifact Signing was asked for but Azure.CodeSigning.Dlib.dll was not found. " +
                "Install the client tools with `winget install -e --id " +
                "Microsoft.Azure.ArtifactSigningClientTools`, or name the dll with " +
                "-PzillitWindowsSigningDlib=<path to bin\\x64\\Azure.CodeSigning.Dlib.dll>."
        }
    }

    val namedSigntool = providers.gradleProperty("zillitSigntool").orNull?.let(::File)
    val signtool = namedSigntool
        ?: File("C:\\Program Files (x86)\\Windows Kits\\10\\bin")
            .listFiles { entry: File -> entry.isDirectory && entry.name.startsWith("10.") }
            .orEmpty()
            // Newest SDK first. Every one of these is `10.0.<five digits>.0`,
            // so plain string order is version order.
            .sortedByDescending { it.name }
            .firstNotNullOfOrNull { File(it, "x64\\signtool.exe").takeIf(File::isFile) }
    requireNotNull(signtool) {
        "Windows signing was asked for but signtool.exe was not found. Install the Windows " +
            "SDK's Signing Tools feature, or name it with -PzillitSigntool=<path>."
    }
    require(signtool.isFile) { "-PzillitSigntool does not name a file: ${signtool.path}" }

    /*
     * The dlib will not load into an old signtool.
     *
     * Certificate-store signing has worked with every signtool ever shipped,
     * so this only gates the Azure path — but there it is worth failing on
     * early, because what an old signtool actually does is refuse the dlib
     * with a message about neither Azure nor versions. Microsoft's floor is
     * the Windows 11 SDK, and the 20348 SDK specifically does not work. An
     * explicitly named signtool is taken on trust: someone pointing at one
     * knows what they have, and a build tool should not be the thing standing
     * in the way.
     */
    val azureMinSdkBuild = 22621
    if (azureSigning && namedSigntool == null) {
        val sdkBuild = signtool.parentFile.parentFile.name.split('.').getOrNull(2)?.toIntOrNull()
        require(sdkBuild != null && sdkBuild >= azureMinSdkBuild) {
            "Azure Artifact Signing needs signtool.exe from the Windows 11 SDK " +
                "(10.0.$azureMinSdkBuild or newer); the newest one installed is " +
                "${signtool.path}, which the signing dlib will not load. Install a current " +
                "Windows SDK, or name a newer signtool with -PzillitSigntool=<path>."
        }
    }

    /*
     * Where the account lives, as signtool wants it: a JSON file, not flags.
     *
     * Generated rather than kept in the repo because the endpoint is
     * region-specific and the account and profile differ per environment, and
     * because there is nothing secret in it to protect — the credential comes
     * from `DefaultAzureCredential` at signing time, not from this file. A
     * hand-written one (-PzillitWindowsSigningDmdf) wins, for the fields this
     * does not cover: CorrelationId, or an ExcludeCredentials list that stops
     * DefaultAzureCredential trying every mechanism in turn on a runner.
     */
    val azureMetadata = layout.buildDirectory.file("signing/azure-artifact-signing.json")
    val writeAzureMetadata = if (azureSigning && !providers.gradleProperty("zillitWindowsSigningDmdf").isPresent) {
        val endpoint = azureSigningEndpoint.get()
        val account = azureSigningAccount.get()
        val profile = azureSigningProfile.get()
        tasks.register("writeAzureSigningMetadata") {
            description = "Writes the Azure Artifact Signing account metadata signtool's dlib reads."
            outputs.file(azureMetadata)
            // The three values are inputs, so changing region or profile
            // rewrites the file instead of signing against the old one.
            inputs.property("endpoint", endpoint)
            inputs.property("account", account)
            inputs.property("profile", profile)

            val destination = azureMetadata
            doLast {
                val file = destination.get().asFile
                file.parentFile.mkdirs()
                file.writeText(
                    """
                    {
                      "Endpoint": "$endpoint",
                      "CodeSigningAccountName": "$account",
                      "CertificateProfileName": "$profile"
                    }
                    """.trimIndent() + "\n",
                )
            }
        }
    } else {
        null
    }

    val credential: List<String> = if (windowsSigningDlib.isPresent || azureSigning) {
        // The generated file is named only on the Azure path. A dlib named by
        // hand keeps the old behaviour of passing /dmdf only when asked:
        // pointing it at a file this build never writes would break the one
        // case — a dlib that needs no metadata — that used to work.
        val metadata = providers.gradleProperty("zillitWindowsSigningDmdf").orNull
            ?: azureMetadata.get().asFile.absolutePath.takeIf { azureSigning }
        listOf("/dlib", windowsSigningDlib.orNull ?: azureDlib!!.absolutePath) +
            metadata?.let { listOf("/dmdf", it) }.orEmpty()
    } else {
        val named = windowsSigningCert.get()
        when {
            // A thumbprint as both the certificate console and `Get-ChildItem
            // Cert:\CurrentUser\My` print it.
            named.matches(Regex("[0-9a-fA-F]{40}")) -> listOf("/sha1", named)
            named.endsWith(".pfx", ignoreCase = true) || named.endsWith(".p12", ignoreCase = true) ->
                listOf("/f", named) +
                    providers.gradleProperty("zillitWindowsSigningPassword").orNull
                        ?.let { listOf("/p", it) }.orEmpty()
            else -> listOf("/n", named)
        }
    }

    /*
     * RFC 3161, and not optional. An untimestamped signature stops verifying
     * the day the certificate expires, and every install in the field would
     * then refuse its next update rather than merely showing its age.
     *
     * On the Azure path that is not a distant worry but a three-day fuse: an
     * Artifact Signing certificate is reissued every three days, so an
     * untimestamped package is trusted over a weekend and refused by every
     * install from Monday. Microsoft's own timestamping authority is the
     * default there, as its documentation asks.
     */
    val timestampUrl = providers.gradleProperty("zillitWindowsSigningTimestampUrl")
        .getOrElse(if (azureSigning) "http://timestamp.acs.microsoft.com" else "http://timestamp.digicert.com")

    // Captured as plain values for the execution bodies below — a String, a
    // List<String> and a Provider all serialise; reaching back into the
    // project from a task action is what the configuration cache rejects.
    val signtoolPath = signtool.absolutePath
    val signArgs = listOf("sign", "/fd", "SHA256", "/tr", timestampUrl, "/td", "SHA256") + credential

    val binaries = layout.buildDirectory.dir("compose/binaries/main")

    // `finalizedBy` rather than a task to remember: a `packageMsi` that quietly
    // produced an unsigned installer is exactly the accident this exists to
    // design out. Keyed by format because `packageSignedMsi` below reaches for
    // the .msi signer: it writes the same file, and signing it is the same job.
    val installerSigners = listOf(
        Triple("packageMsi", "signWindowsMsi", "msi"),
        Triple("packageExe", "signWindowsExe", "exe"),
    ).associate { (packager, signerName, format) ->
        val target = binaries.map { it.file("$format/$desktopPackageName-$zillitVersion.$format") }
        val signer = tasks.register(signerName) {
            description = "Authenticode-signs the packaged .$format."
            writeAzureMetadata?.let { dependsOn(it) }
            // jpackage rewrites the installer, so an up-to-date verdict here
            // can be about a file that is already gone.
            outputs.upToDateWhen { false }

            val label = signerName
            val tool = signtoolPath
            val arguments = signArgs

            doLast {
                val file = target.get().asFile
                if (!file.isFile) {
                    // Reached when the packaging task this finalises failed.
                    // That failure is the error worth reading, not a second
                    // one stacked on top of it.
                    logger.lifecycle("$label: no ${file.name} — packaging did not produce it.")
                    return@doLast
                }
                listOf(
                    arguments + file.absolutePath,
                    listOf("verify", "/pa", file.absolutePath),
                ).forEach { step ->
                    val process = ProcessBuilder(listOf(tool) + step).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText().trim()
                    val code = process.waitFor()
                    // signtool's own output and nothing else: Gradle prints an
                    // Exec task's entire command line when it fails, and
                    // `/p <password>` has no business in a build log or a CI
                    // transcript. That is why this is not an Exec task.
                    check(code == 0) { "signtool ${step.first()} failed on ${file.name} (exit $code):\n$output" }
                }
                logger.lifecycle("$label: signed and verified ${file.name}")
            }
        }
        tasks.matching { it.name == packager }.configureEach { finalizedBy(signer) }
        format to signer
    }

    /*
     * The signature that reaches the installed machine.
     *
     * Everything above signs an installer; this signs the launcher that the
     * installer *contains*, which is the only signature `WindowsInstaller` can
     * read once Zillit is running. Without it the updater has nothing to
     * compare an incoming package against and falls back to the digest alone;
     * with it, a package signed by anybody else is refused before it is run,
     * however well its digest matches a Remote Config entry someone managed to
     * edit.
     *
     * Only the launcher. The DLLs beside it come from the JetBrains Runtime
     * already signed by JetBrains, and re-signing several hundred of them
     * would be several hundred round trips to a signing service for no
     * question anybody asks.
     */
    val appImage = binaries.map { it.dir("app/$desktopPackageName") }
    val signWindowsLauncher = tasks.register("signWindowsLauncher") {
        description = "Authenticode-signs the launcher inside the app image, before it is packaged."
        dependsOn("createDistributable")
        writeAzureMetadata?.let { dependsOn(it) }
        // createDistributable lays the image out again from the runtime image,
        // so a signature from a previous run is not evidence about this one.
        outputs.upToDateWhen { false }

        val target = appImage.map { it.file("$desktopPackageName.exe") }
        val tool = signtoolPath
        val arguments = signArgs

        doLast {
            val file = target.get().asFile
            check(file.isFile) {
                "signWindowsLauncher: no ${file.name} in ${file.parent}. createDistributable did " +
                    "not produce an app image, so there is no launcher to sign."
            }
            // `createDistributable` leaves the launcher read-only, and signtool
            // embeds the signature by rewriting the file in place — so without
            // this it fails with a bare "SignTool Error: Access is denied."
            // that says nothing about a file attribute. Cleared rather than
            // restored afterwards: jpackage copies the contents into the
            // installer and the bit means nothing to what ships.
            if (!file.canWrite()) {
                check(file.setWritable(true)) {
                    "signWindowsLauncher: ${file.name} is read-only and the attribute could not " +
                        "be cleared, so signtool cannot rewrite it with a signature."
                }
            }
            listOf(
                arguments + file.absolutePath,
                listOf("verify", "/pa", file.absolutePath),
            ).forEach { step ->
                val process = ProcessBuilder(listOf(tool) + step).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val code = process.waitFor()
                check(code == 0) {
                    "signtool ${step.first()} failed on ${file.name} (exit $code):\n$output"
                }
            }
            logger.lifecycle("signWindowsLauncher: signed and verified ${file.name}")
        }
    }

    /*
     * The .msi a Windows release is made of.
     *
     * jpackage over `--app-image`, rather than Compose's `packageMsi`, for one
     * reason: Compose passes an app image to jpackage on macOS only, and on
     * Windows builds the payload from the jlink runtime image instead — so
     * whatever is done to the app image, including signing its launcher, never
     * reaches the installer `packageMsi` writes. This takes the image
     * `createDistributable` produced, signed launcher and all, and wraps that.
     *
     * The flags are jpackage's spellings of the `windows { }` block, which is
     * why `windowsMenuGroup` and `windowsUpgradeUuid` are declared beside
     * `desktopPackageName` instead of there: the UUID in particular has to be
     * byte-for-byte what every previous release used, or this installs beside
     * the build it should replace. `perUserInstall = false` is the absence of
     * `--win-per-user-install`, and the icon, the jvm arguments and the bundled
     * configuration all ride along inside the image rather than being named
     * again here.
     */
    val packageSignedMsi = tasks.register("packageSignedMsi") {
        description = "Builds the .msi from a signed app image — the installer in-app updates accept."
        group = "compose desktop"
        dependsOn(signWindowsLauncher)

        val jpackage = File(jetbrainsRuntime.get().metadata.installationPath.asFile, "bin\\jpackage.exe")
            .absolutePath
        val image = appImage
        val destination = binaries.map { it.dir("msi") }
        // jpackage insists on writing its scratch directory itself and fails if
        // it already exists, so it is ours to clear rather than Gradle's.
        val scratch = layout.buildDirectory.dir("jpackage/signedMsi")
        val name = desktopPackageName
        val version = zillitVersion
        val summary = desktopDescription
        val publisher = desktopVendor
        val menuGroup = windowsMenuGroup
        val upgradeUuid = windowsUpgradeUuid
        // What `windows { iconFile }` gives packageMsi: the icon Add/Remove
        // Programs and the installer itself show. The launcher inside the image
        // already carries it, but the installer is a separate binary.
        val icon = project.file("icons/zillit.ico")

        doLast {
            val imageDir = image.get().asFile
            // `app\.jpackage.xml`, not the image root: that is where jpackage
            // writes it on Windows, beside the .cfg and the jars. (On macOS it
            // is `Contents/app/`, which is why the path is worth naming rather
            // than guessing.) Checked only so a missing or hand-made image
            // fails with a sentence instead of jpackage's own terse version.
            check(File(imageDir, "app\\.jpackage.xml").isFile) {
                "packageSignedMsi: ${imageDir.path} is not a jpackage app image " +
                    "(app\\.jpackage.xml is missing). jpackage reads that file to learn what it " +
                    "is packaging."
            }
            val destinationDir = destination.get().asFile.apply { mkdirs() }
            val scratchDir = scratch.get().asFile.apply { deleteRecursively() }
            // jpackage overwrites its output, but a stale .msi left behind by a
            // failed run would otherwise be signed and shipped by the finalizer.
            File(destinationDir, "$name-$version.msi").delete()

            val command = listOf(
                jpackage,
                "--type", "msi",
                "--app-image", imageDir.absolutePath,
                "--name", name,
                "--app-version", version,
                "--description", summary,
                "--vendor", publisher,
                "--icon", icon.absolutePath,
                "--dest", destinationDir.absolutePath,
                "--temp", scratchDir.absolutePath,
                "--win-menu",
                "--win-menu-group", menuGroup,
                "--win-shortcut",
                "--win-dir-chooser",
                "--win-upgrade-uuid", upgradeUuid,
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            check(code == 0) {
                "jpackage failed building the .msi (exit $code). WiX 3.x must be on PATH — see " +
                    "docs/WINDOWS_BUILD.md.\n$output"
            }
            logger.lifecycle("packageSignedMsi: wrote $name-$version.msi from the signed app image")
        }
    }
    // The same signer `packageMsi` uses: same path, same file, same job.
    packageSignedMsi.configure { finalizedBy(installerSigners.getValue("msi")) }

    logger.lifecycle("Windows packages will be Authenticode-signed and timestamped at $timestampUrl")
}

/*
 * Shipping the configuration inside the app.
 *
 * `JvmConfigLoader` looks for `zillit.properties` beside the executable, but
 * that candidate is `user.dir` — the working directory, which for a
 * double-clicked .app is `/`, not the bundle. There is therefore nowhere to
 * *put* a config file inside the app that the loader would find on its own.
 * `-Dzillit.config` pointed at jpackage's `$APPDIR` is what makes it findable;
 * Compose already uses the same expansion for its own resources directory.
 *
 * ## This embeds a secret in the artefact
 *
 * The file carries `<PREFIX>_ENCRYPTION_KEY` and `<PREFIX>_IV_ENCRYPTION_KEY`,
 * the AES pair behind the moduledata and bodyhash headers. Bundled, they sit in
 * plain text at `Zillit.app/Contents/app/resources/zillit.properties`, readable
 * by anyone holding the DMG. That is the accepted trade for a build a tester can
 * install with nothing to copy — chosen deliberately, not by default.
 *
 * Which is why it is opt-in and takes a path rather than reading one from the
 * repo: no key is ever committed, and a release build cannot pick one up by
 * accident. The app has a keyless route too — leave those two lines blank and
 * `ApiKeySetup` asks the user once, storing them in their keychain.
 *
 *   ./gradlew :desktopApp:packageDmg -PzillitEnv=develop -PzillitBundleConfig
 *   ./gradlew :desktopApp:packageDmg -PzillitEnv=develop -PzillitBundleConfig=/path/to/zillit.properties
 */
val bundleConfig = providers.gradleProperty("zillitBundleConfig")
val appResourcesDir = layout.buildDirectory.dir("appResources")

if (bundleConfig.isPresent) {
    // A bare `-PzillitBundleConfig` arrives as an empty string; Gradle gives
    // "true" only for `-PzillitBundleConfig=true`. Either means "the usual one".
    val requested = bundleConfig.get().takeIf { it.isNotBlank() && it != "true" }
    val configFile = File(requested ?: "${System.getProperty("user.home")}/.zillit/zillit.properties")

    // Fails the build rather than packaging an app that cannot start: a DMG
    // whose whole point is carrying its configuration, without it, is worse
    // than no DMG.
    require(configFile.isFile) {
        "-PzillitBundleConfig: no configuration at ${configFile.path}. " +
            "Pass the path explicitly, or create ~/.zillit/zillit.properties."
    }

    // `Sync`, not `Copy`: the staging directory must hold this file and nothing
    // else. A `Copy` leaves whatever a previous build put there, and a stale
    // config is a stale key shipping in a build that never asked for one.
    val stageBundledConfig = tasks.register<Sync>("stageBundledConfig") {
        description = "Stages zillit.properties for packaging inside the app bundle."
        from(configFile)
        // `common` is the platform-agnostic slot; Compose flattens it into
        // $APPDIR/resources for every target.
        into(appResourcesDir.map { it.dir("common") })
    }

    tasks.matching { it.name == "prepareAppResources" }
        .configureEach { dependsOn(stageBundledConfig) }

    // Declaring the config as an INPUT of the image-producing tasks, not just a
    // dependency edge. Staging it is not enough: when the config is the only
    // thing that changed, both of these judged themselves up to date and
    // `packageDmg` re-packaged an app image built around the PREVIOUS config —
    // a green build whose DMG carried the wrong URLs. Naming the file as an
    // input is what makes a config-only edit invalidate the image.
    tasks.matching { it.name == "prepareAppResources" || it.name == "createDistributable" }
        .configureEach { inputs.file(configFile).withPropertyName("bundledZillitConfig") }

    // Defence in depth for the same failure. The input wiring above should keep
    // the image fresh, but any future path that rebuilds the app without the
    // config — a cached image, a hand-run task, a plugin upgrade that drops the
    // input — puts the wrong endpoints in front of users with nothing failing.
    // So the packaged bundle is compared with the file it claims to carry, and
    // a mismatch stops the build instead of shipping.
    val verifyBundledConfig = tasks.register("verifyBundledConfig") {
        description = "Fails if the config inside the app image is not the one that was staged."
        dependsOn("createDistributable")

        // Captured at configuration time: the execution body must not reach
        // back into the project, or the configuration cache rejects it.
        val source = configFile
        val imageDir = layout.buildDirectory.dir("compose/binaries/main/app")
        inputs.file(source).withPropertyName("stagedZillitConfig")

        doLast {
            val image = imageDir.get().asFile
            val packaged = image.walkTopDown().filter { it.name == source.name }.toList()

            check(packaged.isNotEmpty()) {
                "verifyBundledConfig: -PzillitBundleConfig was requested but no ${source.name} " +
                    "is inside the app image at ${image.path}. The DMG would start with no " +
                    "configuration at all."
            }

            val wanted = source.readBytes()
            val stale = packaged.filter { !it.readBytes().contentEquals(wanted) }
            check(stale.isEmpty()) {
                buildString {
                    appendLine("verifyBundledConfig: the app image carries a STALE ${source.name}.")
                    appendLine("Packaging it would ship a build aimed at the wrong servers.")
                    appendLine()
                    appendLine("  wanted (${wanted.size} bytes): ${source.path}")
                    stale.forEach { appendLine("  found  (${it.length()} bytes): ${it.path}") }
                    appendLine()
                    append("Delete build/compose/binaries/main/app and package again.")
                }
            }
        }
    }

    // Every way the image becomes something a person can run or hand over.
    // `packageSignedMsi` belongs here and `packageMsi` does not: the signed
    // route wraps this very image, so checking the image checks the installer,
    // while `packageMsi` builds its payload from the runtime image and a verdict
    // about the image would say nothing about what it shipped.
    listOf("packageDmg", "packageSignedMsi", "packageDistributionForCurrentOS", "runDistributable", "notarizeDmg")
        .forEach { consumer ->
            tasks.matching { it.name == consumer }.configureEach { dependsOn(verifyBundledConfig) }
        }

    logger.lifecycle("Bundling ${configFile.path} inside the app — it will carry its own header key")
}

compose.desktop {
    application {
        mainClass = "com.zillit.desktop.MainKt"

        javaHome = jetbrainsRuntime.get().metadata.installationPath.asFile.absolutePath

        // JCEF (the call media engine's embedded Chromium) reaches into AWT's
        // platform internals; without these opens macOS dies with SIGABRT at
        // libjawt load. Harmless everywhere else.
        jvmArgs += listOf(
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )

        // Which server a packaged build talks to.
        //
        // Everywhere else the environment is chosen at runtime — `-Dzillit.env`
        // or `$ZILLIT_ENV` — but a `.dmg` a tester double-clicks has no shell to
        // set one in, so it has to be baked in at package time.
        //
        // Absent by default, deliberately. `JvmConfigLoader` resolves an unset
        // environment to prod (its own comment: "a misconfigured launch fails
        // onto the strictest settings rather than silently pointing at QA"), and
        // a release build must not be able to ship aimed at a test server just
        // because someone forgot a flag. Aiming one at dev is the explicit act:
        //
        //     ./gradlew :desktopApp:packageDmg -PzillitEnv=develop
        // Reads the bundled copy in preference to anything on the machine:
        // `-Dzillit.config` is the loader's second candidate, ahead of both
        // `user.dir` and ~/.zillit.
        if (bundleConfig.isPresent) {
            jvmArgs += "-Dzillit.config=${'$'}APPDIR/resources/zillit.properties"
        }

        providers.gradleProperty("zillitEnv").orNull?.let { environment ->
            logger.lifecycle("Packaging Zillit against the '$environment' environment")
            jvmArgs += "-Dzillit.env=$environment"
        }

        // This build's own identity for `ZillitVariant` (core:common) to read
        // at runtime — the data directory, Keychain service and LaunchAgent
        // label it computes from this must match what this same variant was
        // packaged with below (`bundleID`, `packageName`, `upgradeUuid`), or a
        // signed helper's `--identifier` above, or the two disagree about
        // which app they are. Omitted for production, so a plain package
        // keeps using `~/.zillit` with no flag needed.
        if (zillitVariant.isNotEmpty()) {
            jvmArgs += "-Dzillit.variant=$zillitVariant"
        }

        nativeDistributions {
            // Dmg → macOS, Msi + Exe → Windows, Deb → ChromeOS/Crostini + Linux
            // (plan §1). Signing and notarization are configured in M12; these
            // formats build unsigned today. jpackage builds only the host OS's
            // formats: `packageExe`/`packageMsi` must run ON Windows (with WiX
            // 3.x installed) — see docs/WINDOWS_BUILD.md.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = desktopPackageName
            // From gradle.properties — see `zillitVersion` above. jpackage
            // wants `x.y.z` with each part numeric; on macOS the first must
            // be non-zero.
            packageVersion = zillitVersion

            // Set only when bundling was asked for. Pointing at the staging
            // directory unconditionally means a build with no flag still ships
            // whatever the last flagged build left there — which is exactly the
            // accident the opt-in exists to prevent, and it is silent.
            //
            // Generated, never a source directory: the staged file is a secret
            // and has no business inside the repo.
            if (bundleConfig.isPresent) appResourcesRootDir.set(appResourcesDir)
            // Shared with `packageSignedMsi`, which passes them to jpackage
            // itself — see the values beside `desktopPackageName`.
            description = desktopDescription
            vendor = desktopVendor

            // `jlink`-trimmed runtime (plan §10, risk 11). Modules listed are
            // those the current dependency set needs; extend as core modules
            // land rather than falling back to the full JRE.
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                // A static dependency of ktor-utils (per jdeps over every
                // shipped jar, 2026-08-10). The first trimmed runtime lacked
                // it, and packaged installs failed on exactly the code paths
                // that exercised it — uploads — while JSON traffic sailed.
                // jcef happens to pull it transitively today; named so the
                // app never depends on a bystander module for its own needs.
                "java.management",
                "java.naming",
                "java.net.http",
                // Budget Builder's loopback gateway is a com.sun.net.httpserver
                // — absent from a trimmed runtime unless named, and absent only
                // shows up as the packaged app failing where `run` works.
                "jdk.httpserver",
                "java.prefs",
                "java.sql",
                // pdfbox reaches for XML support on some documents; cheap
                // insurance against a runtime change dropping it.
                "java.xml",
                "jdk.crypto.ec",
                "jdk.unsupported",
                // The media engine IS this module — a trimmed runtime without
                // it packages an app whose calls have no audio.
                "jcef",
            )

            macOS {
                // Distinct per variant (see `zillitBundleId` above) — this is
                // what lets Launch Services, Spotlight and the Dock treat a
                // QA or develop install as a different app from production
                // rather than the same one living in a second folder.
                bundleID = zillitBundleId

                // The wordmark every other client wears — sourced from the
                // iOS app icon set (1024px master in desktopApp/icons).
                iconFile.set(project.file("icons/zillit.icns"))

                /*
                 * Why a build installs elsewhere, or does not.
                 *
                 * jpackage ad-hoc signs on Apple Silicon, which is enough to
                 * launch on the machine that produced the build and nowhere
                 * else: a copy that arrives by download or AirDrop carries
                 * `com.apple.quarantine`, and Gatekeeper then asks for
                 * notarization it cannot find. The tester sees "Apple could not
                 * verify Zillit is free of malware" and their only offered
                 * choice is Move to Bin.
                 *
                 * Signing is therefore opt-in, not on: `zillitSigningIdentity`
                 * absent leaves the build exactly as it was, so a developer
                 * without a certificate is not blocked from packaging. Supply
                 * the identity and the app is signed; supply the notarization
                 * credentials too and `notarizeDmg` will submit it.
                 *
                 *   ./gradlew :desktopApp:packageDmg \
                 *     -PzillitSigningIdentity="Developer ID Application: … (TEAMID)"
                 *
                 * No secret belongs in this file or in gradle.properties in the
                 * repo. The identity names a certificate the keychain holds;
                 * the Apple ID password must be an app-specific password, and
                 * belongs in ~/.gradle/gradle.properties or the environment.
                 */
                val signingIdentity = providers.gradleProperty("zillitSigningIdentity")
                signing {
                    sign.set(signingIdentity.isPresent)
                    identity.set(signingIdentity)
                }

                // Required by notarization, and the reason entitlements.plist
                // exists — the runtime blocks JCEF outright without it.
                entitlementsFile.set(project.file("entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("entitlements.plist"))

                notarization {
                    appleID.set(providers.gradleProperty("zillitAppleId"))
                    password.set(providers.gradleProperty("zillitAppleIdPassword"))
                    teamID.set(providers.gradleProperty("zillitTeamId"))
                }

                /*
                 * macOS denies the microphone and camera *silently* when these
                 * strings are missing — no prompt, no error, a call that simply
                 * has no audio. They are user-facing: this exact text is what
                 * the permission dialog shows.
                 */
                // CFBundleURLSchemes is deliberately NOT suffixed per variant:
                // it is what WidgetLaunch's `zillit://` shortcuts and the
                // server's deep links target, and both only know the one
                // scheme. With two variants installed, macOS hands a
                // `zillit://` URL to whichever last registered it — a variant
                // opened directly from its own window/tray is unaffected;
                // only a `zillit://…` link arriving from outside the app may
                // land on the wrong copy.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Zillit uses your microphone for production calls.</string>
                        <key>NSCameraUsageDescription</key>
                        <string>Zillit uses your camera for production video calls.</string>
                        <key>NSDownloadsFolderUsageDescription</key>
                        <string>Zillit saves app updates and files you download to your Downloads folder.</string>
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>$zillitBundleId</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>zillit</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
            windows {
                // Both from the shared values beside `desktopPackageName`:
                // `packageSignedMsi` passes these same two to jpackage itself,
                // and they must not be able to drift apart.
                menuGroup = windowsMenuGroup
                upgradeUuid = windowsUpgradeUuid
                iconFile.set(project.file("icons/zillit.ico"))
                // Start-menu entry, desktop shortcut, and a folder chooser —
                // the installer people expect on Windows rather than a silent
                // per-user drop into AppData.
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = false
            }
            linux {
                packageName = "zillit-desktop" + if (zillitVariant.isEmpty()) "" else "-$zillitVariant"
                iconFile.set(project.file("icons/zillit-512.png"))
            }
        }
    }
}
