// Android Gradle plugin (AGP) 9.x compiles Kotlin itself ("built-in Kotlin"), so
// `org.jetbrains.kotlin.android` must NOT be applied. AGP 9.4 bundles Kotlin
// Gradle Plugin (KGP) 2.2.10; the version below pins a newer KGP using the
// documented override ("Upgrade to a higher KGP version", AGP 9.0 release
// notes). Keep it in sync with `kotlin` in gradle/libs.versions.toml.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
        trimTrailingWhitespace()
        endWithNewline()
    }
}
