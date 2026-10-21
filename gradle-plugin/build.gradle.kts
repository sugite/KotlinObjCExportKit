plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir("../shared/src/main/kotlin") }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
}

gradlePlugin {
    plugins {
        create("objcExport") {
            id = "io.github.sugite.koek"
            displayName = "Kotlin ObjC Export Kit"
            description = "Explicit Kotlin/Native Objective-C API exports and grouped framework headers"
            implementationClass = "io.github.sugite.koek.gradle.ObjCExportPlugin"
        }
    }
}
