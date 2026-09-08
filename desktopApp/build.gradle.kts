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
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.datetime)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
}

/**
 * Forwards `-Pzillit.env` / `-Pzillit.config` from the Gradle command line into
 * the **application's** JVM.
 *
 * Without this, `./gradlew :desktopApp:run -Dzillit.env=qa` sets the property on
 * *Gradle's* JVM and the app never sees it — so it silently falls back to
 * production. Which environment you are talking to is not something to discover
 * by accident.
 *
 *   ./gradlew :desktopApp:run -Pzillit.env=qa
 *   ./gradlew :desktopApp:run -Pzillit.env=qa -Pzillit.http=body
 */
tasks.withType<JavaExec>().configureEach {
    listOf("zillit.env", "zillit.config", "zillit.http").forEach { key ->
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
// The product's name everywhere a user sees a file: the .app bundle, the DMG,
// the Windows installer, the dock and the menu bar. The bundle id stays
// `com.zillit.desktop` — renaming the bundle must not re-identify the app.
val desktopPackageName = "Zillit-Desktop"

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
         * runtime and no `com.apple.security.device.camera`, and on macOS 26
         * a hardened process without the entitlement is not refused the
         * camera: it gets a running capture session that delivers no frames,
         * ever. getUserMedia succeeds, the track reads live at 1280x720, the
         * camera light stays off and every tile stays black. The microphone
         * works throughout because the audio service runs in the app
         * process, which jpackage signed with the audio-input entitlement.
         * Measured 2026-09-08: a hardened capture process without the
         * entitlement got 0 frames in 4 s; the same binary with it got 42.
         *
         * The signed build re-signs these anyway (resignWithFrameworks);
         * this covers the ad-hoc bundle everyone develops against.
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
            )
        }
    } else {
        logger.lifecycle("No Swift compiler; packaging without the screen-share source helper")
        null
    }

    val resignIdentity = providers.gradleProperty("zillitSigningIdentity")

    // Unsigned builds keep the old graph exactly — nothing to sign.
    val distributableReady = if (resignIdentity.isPresent) {
        tasks.register<Exec>("resignWithFrameworks") {
            dependsOn(copyCefFrameworks)
            notifyHelper?.let { dependsOn(it) }
            captureHelper?.let { dependsOn(it) }
            description = "Signs what createDistributable missed, then re-seals the bundle."

            val app = layout.buildDirectory
                .dir("compose/binaries/main/app/$desktopPackageName.app").get().asFile.absolutePath

            // Paths arrive as positional arguments rather than interpolated, so
            // a space in the build directory cannot split a word.
            commandLine(
                "bash", "-c",
                """
                set -euo pipefail
                app="${'$'}1"; identity="${'$'}2"; entitlements="${'$'}3"

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
                # helper's grant too.
                for helper in zillit-notify zillit-capture; do
                    path="${'$'}app/Contents/MacOS/${'$'}helper"
                    [ -f "${'$'}path" ] || continue
                    codesign --force --identifier com.zillit.desktop --options runtime \
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

        nativeDistributions {
            // Dmg → macOS, Msi + Exe → Windows, Deb → ChromeOS/Crostini + Linux
            // (plan §1). Signing and notarization are configured in M12; these
            // formats build unsigned today. jpackage builds only the host OS's
            // formats: `packageExe`/`packageMsi` must run ON Windows (with WiX
            // 3.x installed) — see docs/WINDOWS_BUILD.md.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = desktopPackageName
            packageVersion = "1.0.1"

            // Set only when bundling was asked for. Pointing at the staging
            // directory unconditionally means a build with no flag still ships
            // whatever the last flagged build left there — which is exactly the
            // accident the opt-in exists to prevent, and it is silent.
            //
            // Generated, never a source directory: the staged file is a secret
            // and has no business inside the repo.
            if (bundleConfig.isPresent) appResourcesRootDir.set(appResourcesDir)
            description = "Zillit Desktop"
            vendor = "Zillit"

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
                bundleID = "com.zillit.desktop"

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
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Zillit uses your microphone for production calls.</string>
                        <key>NSCameraUsageDescription</key>
                        <string>Zillit uses your camera for production video calls.</string>
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>com.zillit.desktop</string>
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
                menuGroup = "Zillit"
                iconFile.set(project.file("icons/zillit.ico"))
                // Stable UUID — required for MSI upgrades to replace rather
                // than install alongside. Do not regenerate.
                upgradeUuid = "8F5D2C41-9A3E-4B7C-BE21-6D4A0F3E9C58"
                // Start-menu entry, desktop shortcut, and a folder chooser —
                // the installer people expect on Windows rather than a silent
                // per-user drop into AppData.
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = false
            }
            linux {
                packageName = "zillit-desktop"
                iconFile.set(project.file("icons/zillit-512.png"))
            }
        }
    }
}
