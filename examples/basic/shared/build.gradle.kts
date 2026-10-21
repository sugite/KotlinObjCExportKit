plugins {
    kotlin("multiplatform")
    id("io.github.sugite.koek")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DemoKit"
            isStatic = false
        }
    }
    sourceSets.commonMain.dependencies {
        implementation("io.github.sugite.koek:annotations:0.1.0")
    }
}
