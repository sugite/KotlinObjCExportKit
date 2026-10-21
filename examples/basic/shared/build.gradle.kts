import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("io.github.sugite.koek")
}

kotlin {
    val xcframework = XCFramework("DemoKit")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DemoKit"
            isStatic = false
            xcframework.add(this)
        }
    }
    sourceSets.commonMain.dependencies {
        implementation("io.github.sugite.koek:annotations:0.1.0")
    }
}
