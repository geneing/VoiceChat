import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * The API key and workspace details live in a git-ignored CSV so they are never
 * committed. We only read them at build time and inject them into BuildConfig.
 *
 * secrets/qwen-apiKey-*.csv rows are simple "name,value" pairs:
 *   id,ws-...
 *   apiKey,sk-...
 *   apiHost,ws-....ap-southeast-1.maas.aliyuncs.com
 *   openAiCompatible,https://.../compatible-mode/v1
 *   dashScope,https://.../api/v1
 */
val secrets: Map<String, String> = run {
    val candidates = rootProject.file("secrets").listFiles()
        ?.filter { it.isFile && it.name.startsWith("qwen-apiKey") && it.extension == "csv" }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
    val file = candidates.firstOrNull()
    if (file == null) {
        logger.lifecycle("VoiceChat: no secrets/qwen-apiKey-*.csv found; API key left empty.")
        emptyMap()
    } else {
        logger.lifecycle("VoiceChat: reading credentials from ${file.name}")
        file.readLines()
            .mapNotNull { line ->
                val index = line.indexOf(',')
                if (index <= 0) null
                else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
    }
}

fun secret(name: String, fallback: String = ""): String = secrets[name] ?: fallback

fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

android {
    namespace = "com.voicechat.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voicechat.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "QWEN_API_KEY", quoted(secret("apiKey")))
        buildConfigField("String", "QWEN_WORKSPACE_ID", quoted(secret("workspaceId")))
        buildConfigField("String", "QWEN_API_HOST", quoted(secret("apiHost")))
        buildConfigField("String", "QWEN_OPENAI_BASE", quoted(secret("openAiCompatible")))
        buildConfigField("String", "QWEN_DASHSCOPE_BASE", quoted(secret("dashScope")))
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The AOQ SDK loads its Opus plugin by library name; extracting the
        // native libraries keeps both System.loadLibrary and plugin lookups happy.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Alibaba AOQ (AI over QUIC) native SDK, arm64-v8a / armeabi-v7a only.
    implementation(files("libs/AoqClientSdk-release.aar"))

    // WebRTC media stack (mic capture, AEC/NS, playback, data channels).
    implementation("io.github.webrtc-sdk:android:137.7151.05")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
