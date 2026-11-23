plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tuxundown.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tuxundown.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 67
        versionName = "7.6.0"
        // v5.6.0 slimming (audit round 41, lever L1): keep only the
        // default resources plus English and Chinese variants. The
        // AndroidX libraries ship accessibility strings (Tab / Dialog /
        // Switch / "%1$d percent." / "Invalid input" ...) translated
        // into dozens of locales; measured on the v5.5.0 release APK,
        // resources.arsc carried 719 non-CJK library translation
        // strings (fa/ja/ka/pa/it/sw/...) that this app can never show
        // — its own UI is hardcoded Chinese and its own strings.xml
        // exists only in the default configuration. Filtering the
        // packaged locales to default + en + zh variants strips those
        // dead translations from the arsc while keeping every string
        // a Chinese or English system can actually resolve.
        resConfigs("en", "zh", "zh-rCN", "zh-rTW", "zh-rHK")
    }

    // Release signing is injected by CI from repository secrets (the
    // keystore is restored to a temp path and passed via environment).
    // Without those variables the release build stays unsigned and local
    // debug builds are unaffected.
    signingConfigs {
        // Pinned TEST signing: without this, every CI runner generates
        // a fresh debug keystore and test builds can never be installed
        // over each other. This key is a throwaway committed on purpose
        // (standard debug alias/password); it is NOT the release key.
        create("testPin") {
            storeFile = rootProject.file("tools/test-signing.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (System.getenv("TUXUNDOWN_KEYSTORE_PATH") != null) {
            create("release") {
                storeFile = file(System.getenv("TUXUNDOWN_KEYSTORE_PATH"))
                storePassword = System.getenv("TUXUNDOWN_STORE_PASSWORD")
                keyAlias = System.getenv("TUXUNDOWN_KEY_ALIAS")
                keyPassword = System.getenv("TUXUNDOWN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("testPin")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release ships only the two ABIs real devices use; debug
            // builds keep every ABI for emulators.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            if (System.getenv("TUXUNDOWN_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
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
    }
    // v2.0.0 slimming: DebugProbesKt.bin is kotlinx-coroutines'
    // debug-agent hook table. It is only ever read when a debugger
    // attaches with the coroutines agent; production code paths
    // never touch it, and excluding it is the library's own
    // documented packaging advice.
    packaging {
        resources {
            excludes += "DebugProbesKt.bin"
            // v5.6.0 slimming (audit round 41, lever L2): the
            // kotlin/*.kotlin_builtins files (~10.5 KB compressed in
            // v5.5.0) hold serialized metadata for the Kotlin standard
            // library's built-in classes. Their ONLY runtime reader is
            // kotlin-reflect's built-in class metadata loader
            // (kotlin.reflect.jvm.internal.impl.builtins
            // .KotlinBuiltIns / BuiltInsLoader), which loads them when
            // reflection materializes stdlib classes. This project has
            // NO kotlin-reflect dependency anywhere in its build files
            // (grep-verified: no "kotlin-reflect" in any *.kts /
            // *.toml), and compiled Kotlin code never reads these
            // resources — calls into the stdlib are plain bytecode.
            // Excluding them is therefore dead-weight removal, not a
            // behaviour change.
            excludes += "**/*.kotlin_builtins"
        }
        // v2.0.0 slimming, the big lever: since AGP 7 the default
        // packaging stores classes.dex UNCOMPRESSED in the APK
        // (page-aligned for direct mmap). That is a runtime
        // nicety this project does not need — its hard constraint
        // is the DOWNLOAD size of the APK (the whole feature set
        // lives in one ~2.2 MB dex that deflates to roughly
        // half). Legacy packaging compresses the dex again; the
        // platform extracts it at install time as it always did
        // pre-AGP-7. Measured effect is recorded in CHECKPOINT.
        dex {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // v1.6 QR scan: zxing CORE only (the decoder) — deliberately NOT
    // the embedded scanner/CameraX/ML-Kit stacks. R8 shrinks it to
    // the QR subset actually used; the release-size red line is
    // re-measured every version.
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // v2.0.0 slimming: ui-tooling-preview used to be an
    // implementation dependency, but no source file uses @Preview
    // (verified by grep — the only "Preview" hits are camera
    // preview APIs in ScanActivity), so it shipped dead weight.
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar stub throws);
    // test-only, never shipped in the APK.
    testImplementation("org.json:json:20210307")
}
