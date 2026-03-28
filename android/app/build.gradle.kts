import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("jacoco")
}

android {
    namespace = "com.paul.sprintsync"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "sync.sprint"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "TCP_ONLY", "false")
        buildConfigField("String", "TCP_HOST_IP", "\"192.168.0.10\"")
        buildConfigField("int", "TCP_HOST_PORT", "9000")
        buildConfigField("String", "AUTO_START_ROLE", "\"none\"")
        buildConfigField("String", "DEVICE_PROFILE", "\"default\"")
        buildConfigField("boolean", "HOST_CONTROLLER_ONLY", "false")
        resValue("string", "app_name", "Sprint Sync")
    }

    flavorDimensions += "deviceProfile"
    productFlavors {
        create("hostXiaomi") {
            dimension = "deviceProfile"
            applicationIdSuffix = ".host.xiaomi"
            versionNameSuffix = "-host-xiaomi"
            buildConfigField("boolean", "TCP_ONLY", "true")
            buildConfigField("String", "TCP_HOST_IP", "\"192.168.0.10\"")
            buildConfigField("int", "TCP_HOST_PORT", "9000")
            buildConfigField("String", "AUTO_START_ROLE", "\"host\"")
            buildConfigField("String", "DEVICE_PROFILE", "\"host_xiaomi\"")
            buildConfigField("boolean", "HOST_CONTROLLER_ONLY", "true")
            resValue("string", "app_name", "Sprint Sync Host (Xiaomi)")
        }
        create("clientPixel") {
            dimension = "deviceProfile"
            applicationIdSuffix = ".client.pixel"
            versionNameSuffix = "-client-pixel"
            buildConfigField("boolean", "TCP_ONLY", "true")
            buildConfigField("String", "TCP_HOST_IP", "\"192.168.0.103\"")
            buildConfigField("int", "TCP_HOST_PORT", "9000")
            buildConfigField("String", "AUTO_START_ROLE", "\"client\"")
            buildConfigField("String", "DEVICE_PROFILE", "\"client_pixel\"")
            buildConfigField("boolean", "HOST_CONTROLLER_ONLY", "false")
            resValue("string", "app_name", "Sprint Sync Client (Pixel)")
        }
        create("clientOneplus") {
            dimension = "deviceProfile"
            applicationIdSuffix = ".client.oneplus"
            versionNameSuffix = "-client-oneplus"
            buildConfigField("boolean", "TCP_ONLY", "true")
            buildConfigField("String", "TCP_HOST_IP", "\"192.168.0.103\"")
            buildConfigField("int", "TCP_HOST_PORT", "9000")
            buildConfigField("String", "AUTO_START_ROLE", "\"client\"")
            buildConfigField("String", "DEVICE_PROFILE", "\"client_oneplus\"")
            buildConfigField("boolean", "HOST_CONTROLLER_ONLY", "false")
            resValue("string", "app_name", "Sprint Sync Client (OnePlus)")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:guava:33.5.0-android")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.8")
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.withType<Test>().configureEach {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testHostXiaomiDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val coverageExclusions =
        listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
        )

    val kotlinClasses =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/hostXiaomiDebug") {
            exclude(coverageExclusions)
        }

    classDirectories.setFrom(files(kotlinClasses))
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get().asFile) {
            include("outputs/unit_test_code_coverage/*DebugUnitTest/*.exec")
            include("jacoco/test*DebugUnitTest.exec")
        },
    )
}
