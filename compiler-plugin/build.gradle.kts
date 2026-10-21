plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir("../shared/src/main/kotlin") }
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
            "-opt-in=org.jetbrains.kotlin.backend.konan.InternalKotlinNativeApi",
            "-opt-in=org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI",
        )
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-native-compiler-embeddable:2.2.21")
}
