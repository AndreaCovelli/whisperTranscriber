@file:Suppress("UnstableApiUsage")

// In settings.gradle.kts

pluginManagement {
    repositories {
        google() // The Google repository, where the Android plugin lives
        mavenCentral() // The main public repository
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WhisperTranscriber" // Make sure this matches your project name
include(":app")