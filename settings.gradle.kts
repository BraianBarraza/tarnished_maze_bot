@file:Suppress("UnstableApiUsage")

rootProject.name = "maze-bots"
// comment out or delete either 'java-bots' or 'kt-bots' if you don't plan on implementing bots in that language.
// You could also just ignore them. If you actually want to delete the submodules, you are required to remove its
// inclusion here as well.
include("java-bots")
include("kt-bots")

// This one you should keep
include("ui-wrapper")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // this one is essential for the mazegame dependencies
        maven(url = "https://jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
