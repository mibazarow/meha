import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.dagger.hilt.android)
    kotlin("kapt")
    alias(libs.plugins.detekt)
}

// Non-null when release signing is misconfigured (missing keystore, or the "release" config
// pointing at the debug keystore). Enforced by the `validateReleaseSigning` task below, so only
// release artifacts fail: `assembleDebug` keeps working on machines without the release key.
var releaseSigningError: String? = null

android {
    namespace = "com.andharness.ru"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andharness.ru"
        minSdk = 31
        targetSdk = 35
        versionCode = 309
        versionName = "1.23.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The bundled on-device llama.cpp server is arm64 only.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // One distribution channel (see UPDATER.md): the project website. KEEPS the in-app
    // self-updater; its engine lives in `src/sideload`, so it is physically absent from any
    // build that does not include that source set. The applicationId stays `com.andharness.ru`.
    flavorDimensions += "channel"
    productFlavors {
        create("sideload") {
            dimension = "channel"
        }
    }

    // REAL release signing key. Credentials live in keystore.properties at the repo root
    // (this repository is private and intentionally stores them in the clear; see keystore.md).
    // The config stays empty when that file or the keystore is absent, so builds on machines
    // without the key still configure successfully.
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                val store = props.getProperty("storeFile")?.let { rootProject.file(it) }
                if (store != null && store.exists()) {
                    storeFile = store
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        debug {
            // WHY: debug and release must be signed with the SAME certificate so that installing one
            // over the other updates the app in place (`adb install -r`) and PRESERVES user data.
            // Android only accepts an update when the new APK's signing certificate matches the
            // installed app's certificate; on a mismatch it treats the APK as a different app,
            // refuses the update (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and the only way to install is
            // to uninstall first — which erases ALL app data (chats, memory, settings, the encrypted
            // Room DB and its Keystore passphrase) and forces onboarding again.
            // https://developer.android.com/studio/publish/app-signing (app upgrade: certificates must match)
            // https://developer.android.com/google/play/app-updates (mismatch => uninstall erases app data)
            //
            // By default the debug build type is signed with the machine-local debug keystore
            // (`CN=Android Debug`), which never matches the release key; that mismatch is exactly the
            // problem this block removes. Only adopt the real release signing config when it is
            // actually configured (keystore.properties + keystore/meha-release.jks present), so a
            // machine without the release key still builds debug with the default debug signing.
            // This does NOT weaken release: `release` below still has NO debug fallback and still
            // fails fast through `validateReleaseSigning`.
            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null) {
                signingConfig = releaseConfig
            }
        }

        release {
            // R8: shrink + obfuscate the dex and shrink unused resources. Release only —
            // the debug build is intentionally left unminified (isMinifyEnabled defaults to false).
            // The default optimize profile enables R8 full-mode optimizations; keep rules that the
            // app needs on top of that live in proguard-rules.pro.
            // https://developer.android.com/build/shrink-code
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign release ONLY with the real Meha key. There is deliberately NO fallback to the
            // debug keystore: a debug-signed release APK cannot update an already-installed
            // release build (signature mismatch => Android refuses the update and forces an
            // uninstall, wiping user data). A missing/misconfigured key instead fails the release
            // build via the `validateReleaseSigning` task below (see keystore.md for the key).
            // https://developer.android.com/studio/publish/app-signing
            val releaseConfig = signingConfigs.getByName("release")
            val releaseStore = releaseConfig.storeFile
            val debugStore = signingConfigs.getByName("debug").storeFile
            when {
                releaseStore == null -> {
                    releaseSigningError = "Release signing is not configured: keystore.properties " +
                        "is missing or keystore/meha-release.jks does not exist."
                }
                debugStore != null && releaseStore.absolutePath == debugStore.absolutePath -> {
                    releaseSigningError = "Release signing resolves to the DEBUG keystore " +
                        "(${debugStore.absolutePath}); refusing to build a debug-signed release."
                }
                else -> {
                    signingConfig = releaseConfig
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_CODE is generated per the AGP `buildConfig` feature:
        // https://developer.android.com/build/build-variants
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        jniLibs {
            // libllamaserver.so is actually an executable: it must be EXTRACTED to
            // nativeLibraryDir so we can spawn it at runtime (not mapped from the APK).
            useLegacyPackaging = true
        }
        resources {
            // jsch (com.github.mwiede) and jspecify (transitive via security-crypto)
            // both ship META-INF/versions/9/OSGI-INF/MANIFEST.MF with DIFFERENT
            // contents, so pickFirst is unsafe: exclude the conflicting path.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    androidResources {
        // Keep the bundled .gguf model uncompressed (faster install and first-run copy).
        noCompress += "gguf"
        // Same for the OCR models: OcrRecognizer copies them out of the APK on first use and
        // reads their uncompressed length to verify the copy.
        noCompress += "traineddata"
    }
}

// Release builds must be signed with the real Meha key, never the debug key. The check runs in a
// task instead of at configuration time so that a machine without keystore.properties can still
// run `assembleDebug` (and any other non-release task). Only release artifacts depend on it, so a
// missing/misconfigured key fails `assembleRelease`/`bundleRelease` fast with an actionable message.
// https://docs.gradle.org/8.11.1/userguide/task_configuration_avoidance.html
val validateReleaseSigning by tasks.registering {
    group = "verification"
    description =
        "Fails release builds unless the real release keystore is present and is not the debug keystore."
    doLast {
        val error = releaseSigningError
        if (error != null) {
            throw GradleException(
                "$error Restore keystore.properties and keystore/meha-release.jks " +
                    "(see keystore.md) before building a release artifact."
            )
        }
    }
}

tasks.matching { task ->
    // Matches the plain release tasks AND their per-flavor variants (assembleSideloadRelease,
    // installSideloadRelease, packageSideloadRelease, …).
    val name = task.name
    val releaseVerb = name.startsWith("assemble") || name.startsWith("bundle") ||
        name.startsWith("install") || name.startsWith("package")
    releaseVerb && name.endsWith("Release")
}.configureEach {
    dependsOn(validateReleaseSigning)
}

kapt {
    correctErrorTypes = true
}

// Static analysis in REPORT-ONLY mode: detekt surfaces unused imports / unused private members
// and a few correctness smells so they can be fixed incrementally. It is deliberately NOT wired
// into `check`/`build` and never fails the build (`ignoreFailures = true`).
// The rule set is self-contained in config/detekt/detekt.yml because `buildUponDefaultConfig` is
// false: only the rules listed there run (a rule's `active` defaults to false).
// https://detekt.dev/docs/1.23.8/gettingstarted/gradle/
detekt {
    buildUponDefaultConfig = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoreFailures = true
    // Baseline of the findings that existed when detekt was introduced (overwhelmingly unused
    // imports). Future runs report only NEW findings, so the tool is usable for incremental
    // cleanup. Regenerate with `./gradlew detektBaseline` after fixing issues.
    baseline = file("$rootDir/config/detekt/detekt-baseline.xml")
}

// Keep detekt out of `check` so CI/build output is unaffected while findings are triaged.
// https://detekt.dev/docs/1.23.8/gettingstarted/gradle/#disabling-detekt-from-the-check-task
tasks.named("check").configure {
    setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name == "detekt" })
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.mlkit.text.recognition)
    // Image extras (bug-item 10): SVG rasterisation + ML Kit object detection (bundled) + subject
    // segmentation (unbundled, Play-services-backed).
    implementation(libs.android.svg)
    implementation(libs.mlkit.objectdetection)
    implementation(libs.mlkit.subject.segmentation)
    // Geofencing API (GeofencingClient / GeofencingRequest / Geofence) for geofence_* tools.
    // https://developer.android.com/develop/sensors-and-location/location/geofencing
    implementation(libs.play.services.location)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    implementation(libs.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.fragment)
    // Per-app language preferences: AppCompatDelegate.setApplicationLocales() for the in-app RU/EN
    // switch. On API 33+ it delegates to the framework LocaleManager; on API 31–32 it provides the
    // backward-compatible implementation (requires AppCompat 1.6.0+).
    // https://developer.android.com/guide/topics/resources/app-languages
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)
    implementation(libs.material)
    implementation(libs.markwon)
    // Bare-URL autolinking in chat bubbles (https://noties.io/Markwon/docs/v4/linkify/).
    implementation(libs.markwon.linkify)
    implementation(libs.commons.compress)
    // Runtime XZ/LZMA codec for commons-compress (7z create/extract). commons-compress declares
    // org.tukaani:xz as OPTIONAL, so it is not pulled transitively; without it SevenZFile/
    // SevenZOutputFile throw NoClassDefFoundError. Version matches the commons-compress 1.27.1 POM.
    // https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.27.1/commons-compress-1.27.1.pom
    implementation(libs.xz)
    implementation(libs.junrar)
    implementation(libs.pdfbox.android)
    implementation(libs.rtfparserkit)
    implementation(libs.commons.csv)
    // SSH exec + SFTP (update.md §5.1). Pure-Java JSch fork; the jar is self-contained
    // (it bundles its own JZlib and crypto providers), so no extra runtime dependency is needed.
    // https://github.com/mwiede/jsch
    implementation(libs.jsch)

    // Home-screen widget (update.md idea #10): Glance AppWidget (app name + «Новый чат» + recent
    // chat titles). Reference: https://developer.android.com/develop/ui/compose/glance/create-app-widget
    implementation(libs.androidx.glance.appwidget)

    // Encryption of the personal Room database at rest (update.md idea #7). SQLCipher for Android
    // plugs into Room through SupportOpenHelperFactory; the key comes from the Android Keystore.
    // https://github.com/sqlcipher/sqlcipher-android
    implementation(libs.sqlcipher.android)

    // Offline OCR engine with Cyrillic support (ML Kit ships Latin/Chinese/Devanagari/Japanese/
    // Korean only). Native libs + the bundled tessdata_fast models are what `OcrRecognizer` uses.
    // https://github.com/adaptech-cz/Tesseract4Android
    implementation(libs.tesseract4android)

    // On-device embeddings (OFF by default; wired in a later task). ONNX Runtime Mobile runs the
    // bundled int8 `rubert-tiny-turbo` ONNX model from assets/models/embedding entirely offline, so
    // semantic memory search needs neither a provider endpoint nor the `embeddingModel` setting.
    // Reference: https://onnxruntime.ai/docs/build/custom.html
    implementation(libs.onnxruntime.android)

    // Audio/video editing tools (bug-items 11 & 12): trim/concat/convert/volume/speed/resize/
    // bitrate/compress/rotate/subtitles/extract/tag-write. Community-maintained ffmpeg-kit fork
    // (LGPL v3, no GPL) — same `com.arthenica.ffmpegkit` API as the retired upstream; arm64-v8a
    // .so verified 16 KB-aligned. See gradle/libs.versions.toml.
    implementation(libs.ffmpeg.kit.full)

    kapt(libs.hilt.compiler)
    kapt(libs.room.compiler)

    testImplementation("junit:junit:4.13.2")
    // JVM XmlPull provider for unit tests: Android supplies XmlPullParserFactory at runtime, a bare
    // JVM does not. Reference: https://kxml.sourceforge.net/
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
}
