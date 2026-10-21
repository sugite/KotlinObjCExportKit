pluginManagement {
    includeBuild("../..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("multiplatform") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "koek-example"
include(":shared")

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.sugite.koek:compiler-plugin"))
            .using(project(":compiler-plugin"))
        substitute(module("io.github.sugite.koek:annotations"))
            .using(project(":annotations"))
    }
}
