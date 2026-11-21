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
        versionCode = 7
        versionName = "1.6.0"
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
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar stub throws);
    // test-only, never shipped in the APK.
    testImplementation("org.json:json:20210307")
}
