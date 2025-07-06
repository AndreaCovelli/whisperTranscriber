// In settings.gradle.kts

pluginManagement {
    repositories {
        google() // The Google repository, where the Android plugin lives
        mavenCentral() // The main public repository
        gradlePluginPortal()
    }
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