/**
 * Root build file — intentionally minimal.
 *
 * All Android/Kotlin build logic lives in android/build.gradle.kts.
 * This file exists so Gradle can load as a valid root project.
 */

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
