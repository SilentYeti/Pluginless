pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

// One node per Minecraft version, each with its own versions/<mc>/gradle.properties. To add one:
// list it here, create that properties file, then guard any API differences with `//? if >=26.1 {`.
stonecutter {
    create(rootProject) {
        versions("1.21.1", "26.1", "26.2", "26.3")
        vcsVersion = "1.21.1"
    }
}

rootProject.name = "Pluginless"
