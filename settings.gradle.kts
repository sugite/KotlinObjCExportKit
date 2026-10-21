pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "KotlinObjCExportKit"
include(":annotations", ":gradle-plugin", ":compiler-plugin")
