/**
 * Root settings for multi-module project.
 *
 * This allows IntelliJ IDEA to treat the entire repository as a Gradle project,
 * enabling full Kotlin semantic indexing alongside the existing JavaScript/Node.js support.
 *
 * The Android module retains its own settings.gradle.kts for standalone builds
 * (e.g., via `cd android && ./gradlew assembleDebug`), but this root file lets
 * the IDE load everything in a single window.
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Android & Kotlin plugins — declared here but applied per-module
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("com.github.ben-manes.versions") version "0.51.0" apply false
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "photo-finish-tcp-device-apks"

// Include the Android app module
includeBuild("android")
