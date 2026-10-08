@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.cyclonedx)
}

val ktlintVersion = "1.7.1"
val instrumentationBuildType = providers.gradleProperty("instrumentationBuildType").orElse("debug").get()

android {
    val roomSchemaDir = "$projectDir/schemas"
    val keystorePath = findProperty("keystorePath") as? String
    val propKeystorePassword = findProperty("keystorePassword") as? String
    val propKeyAlias = findProperty("keyAlias") as? String
    val propKeyPassword = findProperty("keyPassword") as? String
    val propVersionCode = (findProperty("versionCode") as? String)?.toIntOrNull()
    val propVersionName = findProperty("versionName") as? String
    val signingProperties =
        listOf(
            "keystorePath" to keystorePath,
            "keystorePassword" to propKeystorePassword,
            "keyAlias" to propKeyAlias,
            "keyPassword" to propKeyPassword,
        )
    val configuredSigningProperties = signingProperties.filter { (_, value) -> !value.isNullOrBlank() }
    if (configuredSigningProperties.isNotEmpty() && configuredSigningProperties.size != signingProperties.size) {
        val missing =
            signingProperties
                .filter { (_, value) -> value.isNullOrBlank() }
                .joinToString { (name, _) -> name }
        throw GradleException("Partial release signing configuration. Missing: $missing")
    }
    val releaseSigningConfigured = configuredSigningProperties.size == signingProperties.size

    namespace = "com.andreacovelli.whispertranscriber"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.andreacovelli.whispertranscriber"
        minSdk = 26
        targetSdk = 36
        versionCode = propVersionCode ?: 1
        versionName = propVersionName ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Permission recreation needs a fresh install before other tests grant notification access.
        // CI runs it explicitly in its own managed-device invocation.
        if (providers.gradleProperty("android.testInstrumentationRunnerArguments.class").orNull == null) {
            testInstrumentationRunnerArguments["notClass"] =
                "com.andreacovelli.whispertranscriber.NotificationPermissionRecreationInstrumentedTest"
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.directories.add(roomSchemaDir)
            // Export coverage belongs to the minified APK; debug runs the remaining device tests.
            if (instrumentationBuildType == "minifiedSmoke") {
                java.directories.clear()
                kotlin.directories.clear()
                kotlin.directories.add("src/androidTest/export/java")
            }
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(keystorePath))
                storePassword = requireNotNull(propKeystorePassword)
                keyAlias = requireNotNull(propKeyAlias)
                keyPassword = requireNotNull(propKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("release") else null
        }
        create("minifiedSmoke") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles("proguard-minified-smoke.pro")
            matchingFallbacks += "release"
        }
    }
    testBuildType = instrumentationBuildType

    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        animationsDisabled = true

        managedDevices {
            localDevices {
                create("pixel6Api34") {
                    device = "Pixel 6"
                    sdkVersion = 34
                    systemImageSource = "aosp-atd"
                    testedAbi = "x86_64"
                }
            }
        }
    }
    packaging {
        jniLibs {
            // These AndroidX-provided JNI libs are already packaged in a form AGP cannot strip.
            // Keeping debug symbols for them suppresses noisy strip warnings without changing inclusion.
            keepDebugSymbols +=
                setOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libdatastore_shared_counter.so",
                )
        }
        resources {
            pickFirsts +=
                setOf(
                    "META-INF/INDEX.LIST",
                    "META-INF/DEPENDENCIES",
                    "META-INF/NOTICE",
                    "META-INF/NOTICE.txt",
                    "META-INF/NOTICE.md",
                    "META-INF/LICENSE",
                    "META-INF/LICENSE.txt",
                    "META-INF/LICENSE.md",
                )
        }
    }
}

tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("releaseRuntimeClasspath")
    // Keep task input discovery from evaluating CycloneDX's dependency graph provider.
    resolvedDependencies.setFrom(configurations.named("releaseRuntimeClasspath"))
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx-release/bom.json"))
    xmlOutput.unsetConvention()
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
            .editorConfigOverride(
                mapOf(
                    "ktlint_code_style" to "android_studio",
                    "ktlint_standard_function-naming" to "disabled",
                    "ktlint_standard_property-naming" to "disabled",
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint(ktlintVersion)
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    setSource(
        files(
            "src/main/java",
            "src/test/java",
            "src/androidTest/java",
            "src/androidTest/export/java",
        ),
    )
    include("**/*.kt")
    exclude("**/build/**", "**/generated/**")
    reports {
        html.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
        md.required.set(false)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    // DataStore - use a single, consistent artifact
    implementation(libs.androidx.datastore.preferences)

    // Used by HistoryExportFormat for JSON export serialization.
    implementation(libs.gson)
    // Required to satisfy the google-genai runtime service entry and avoid release-time R8 warnings.
    implementation(libs.jackson.module.kotlin)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    // Google ML Kit
    implementation(libs.google.genai)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    ksp(libs.androidx.room.compiler)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    if (instrumentationBuildType != "minifiedSmoke") {
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.room.testing)
        androidTestImplementation(libs.androidx.ui.test.junit4)
    }
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    constraints {
        // AndroidX Test's ViewCapture needs SuspendToFutureAdapter in the shared app/test runtime.
        implementation(libs.androidx.concurrent.futures.ktx)
        // Keep Room migration testing and AndroidX saved state/navigation on one serialization runtime.
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
    }
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
}
