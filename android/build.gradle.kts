plugins {
    id("com.github.ben-manes.versions")
    id("io.gitlab.arturbosch.detekt") apply false
    id("org.jlleitschuh.gradle.ktlint") apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}

subprojects {
    plugins.withId("com.android.application") {
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    }
    plugins.withId("com.android.library") {
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    }
    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure(io.gitlab.arturbosch.detekt.extensions.DetektExtension::class.java) {
            buildUponDefaultConfig = true
            allRules = false
        }
    }
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        extensions.configure(org.jlleitschuh.gradle.ktlint.KtlintExtension::class.java) {
            filter {
                exclude("**/build/**")
                exclude("**/generated/**")
            }
        }
    }
}

subprojects {
    project.evaluationDependsOn(":app")
    plugins.withId("com.android.library") {
        if (name == "camera_android_camerax") {
            dependencies.add(
                "implementation",
                "androidx.concurrent:concurrent-futures:1.2.0",
            )
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
