plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)

    // Room's annotation processor (docs/decisions.md §1). KSP is applied here and
    // not the Room Gradle plugin, so the schema location below is an explicit KSP
    // argument the build and the schema-export test can both point at.
    alias(libs.plugins.ksp)
}

android {
    // Provisional identity; finalize to a controlled domain before distribution.
    // See docs/decisions.md §1 ("Package identity").
    namespace = "com.voicechat.agent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voicechat.agent"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Instrumented (on-device) tests run through AndroidJUnitRunner; see
        // Tests.md and docs/logging.md. They are compiled here but only executed
        // on a device with `:app:connectedDebugAndroidTest`.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Built-in Kotlin derives kotlin.compilerOptions.jvmTarget from this.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true

        // Generated BuildConfig.DEBUG drives release-safe logging: the log
        // facade is enabled only in debug builds (docs/logging.md).
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest to build an
            // application context for the Room repository tests.
            isIncludeAndroidResources = true
        }
    }
}

// Export the Room schema so migrations have a checked-in contract to review.
// The app has a single schema version today (see docs/persistence.md); the
// schema-export test asserts the JSON is produced and matches version 1.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Bounded event-stream contracts in the domain/contracts packages.
    implementation(libs.kotlinx.coroutines.core)

    // On-device speech-to-text (M08). ML Kit GenAI Speech Recognition is the only
    // STT engine (docs/decisions.md §2.1); it is gated at runtime by
    // checkStatus()/checkFeatureStatus() and kept behind the SpeechToText contract.
    implementation(libs.mlkit.genai.speech.recognition)

    // Durable conversation storage (M05). Room entity/DAO code lives in the
    // persistence package and maps to the platform-free domain types.
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    // Provides the debuggable ComponentActivity that Compose test rules launch on
    // the JVM (M06); the test manifest is merged into the debug variant.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Runs the Room repository tests on the JVM (no device, no live services).
    testImplementation(libs.robolectric)
    // Compose UI tests on the JVM under Robolectric: text send, correction,
    // streaming render, conversation switching, and deletion (M06).
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // Instrumented (on-device) test foundation. Compiled on the host with
    // `:app:assembleDebugAndroidTest`; executed only on a device with
    // `:app:connectedDebugAndroidTest` (see Tests.md).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    // Compose instrumented UI tests reuse the BOM-managed Compose UI test library;
    // ui-test-manifest is already a debugImplementation for both test hosts.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
