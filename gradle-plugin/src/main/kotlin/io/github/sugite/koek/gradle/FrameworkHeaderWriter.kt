package io.github.sugite.koek.gradle

import io.github.sugite.koek.output.HeaderBundle
import io.github.sugite.koek.output.writeTextAtomically
import java.io.File
import java.nio.file.Files

internal object FrameworkHeaderWriter {
    fun write(framework: File, bundle: HeaderBundle) {
        require(framework.isDirectory && framework.name == "${bundle.frameworkName}.framework") {
            "Header bundle ${bundle.frameworkName} does not match framework $framework"
        }
        val modulemap = File(framework, "Modules/module.modulemap")
        val selectedModulemap = splitFrameworkModuleMap(bundle.frameworkName, modulemap.readText())
        val manifest = File(framework, "Modules/objc-export-headers.txt")
        val previous = if (manifest.exists()) manifest.readLines() else emptyList()
        require(previous.all(HeaderBundle::isHeaderName)) { "Invalid generated header manifest at $manifest" }

        bundle.headers.forEach { (name, content) -> writeTextAtomically(File(framework, "Headers/$name"), content) }
        previous.filter { it !in bundle.headers }.forEach { name ->
            Files.deleteIfExists(File(framework, "Headers/$name").toPath())
        }
        writeTextAtomically(modulemap, selectedModulemap)
        writeTextAtomically(manifest, bundle.headers.keys.sorted().joinToString("\n", postfix = "\n"))
    }
}
