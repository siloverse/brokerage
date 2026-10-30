@file:Suppress("UnstableApiUsage")

import io.github.siloverse.build.SiloverseBuild

pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/siloverse/siloverse-build")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("GITHUB_TOKEN")).orNull
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("io.github.siloverse.parent") version "1.11.2" apply false
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/siloverse/siloverse-build")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("GITHUB_TOKEN")).orNull
            }
        }
        mavenCentral()
        mavenLocal()
    }
    versionCatalogs {
        create("libs") {
            from("io.github.siloverse.gradle:version-catalog:${SiloverseBuild.version}")
        }
        create("local") {
            from(files("gradle/dep.versions.toml"))
        }
    }
}

rootProject.name = "brokerage"

include(
    ":orders",
    ":ledger",
    ":marketdata",
    ":portfolio",
    ":engine",
    ":app",
    ":venue"
)