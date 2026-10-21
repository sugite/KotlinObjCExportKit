pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "KotlinObjcExportKit"
include(":annotations", ":gradle-plugin", ":compiler-plugin")
