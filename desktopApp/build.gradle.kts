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
    implementation(project(":core:units"))
    implementation(project(":core:database"))
    implementation(project(":core:localization"))
    implementation(project(":core:session"))
    implementation(project(":core:socket"))
    implementation(project(":feature:accounthub"))
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
    implementation(project(":feature:sides"))
    implementation(project(":feature:dealmemo"))
    implementation(project(":feature:productionreport"))
    implementation(project(":feature:documentdistribution"))
    implementation(project(":feature:drive"))

    // The app module had no tests until the single-instance guard, which is
    // logic rather than wiring and worth pinning — particularly its behaviour
    // after a crash, which is the case a naive implementation gets wrong.
    testImplementation(kotlin("test"))

    implementation(compose.desktop.currentOs)
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
val jbrFrameworks = File(jetbrainsRuntime.get().metadata.installationPath.asFile.parentFile, "Frameworks")

// Registered only where there is something to copy — macOS. Decided here at
// configuration time rather than with `onlyIf`, because a task-level predicate
// closes over this script and the configuration cache cannot serialise that.
if (jbrFrameworks.isDirectory) {
    val copyCefFrameworks = tasks.register<Exec>("copyCefFrameworks") {
        dependsOn("createDistributable")
        description = "Copies the Chromium Embedded Framework into the packaged runtime."

        val destination = layout.buildDirectory
            .dir("compose/binaries/main/app/Zillit.app/Contents/runtime/Contents/Frameworks")

        commandLine("ditto", jbrFrameworks.absolutePath, destination.get().asFile.absolutePath)
    }

    // Everything that consumes the distributable needs the frameworks in it
    // first. `createDistributable` must not depend on this — that is a cycle.
    listOf("packageDmg", "packageDistributionForCurrentOS", "runDistributable", "notarizeDmg")
        .forEach { consumer ->
            tasks.matching { it.name == consumer }.configureEach { dependsOn(copyCefFrameworks) }
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
            // Dmg → macOS, Msi → Windows, Deb → ChromeOS/Crostini + Linux
            // (plan §1). Signing and notarization are configured in M12; these
            // formats build unsigned today.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Zillit"
            packageVersion = "1.0.0"

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
                    """.trimIndent()
                }
            }
            windows {
                menuGroup = "Zillit"
                iconFile.set(project.file("icons/zillit.ico"))
                // Stable UUID — required for MSI upgrades to replace rather
                // than install alongside. Do not regenerate.
                upgradeUuid = "8F5D2C41-9A3E-4B7C-BE21-6D4A0F3E9C58"
            }
            linux {
                packageName = "zillit-desktop"
                iconFile.set(project.file("icons/zillit-512.png"))
            }
        }
    }
}
