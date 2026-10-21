package io.github.sugite.koek.output

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun writeAtomically(file: File, writer: (Path) -> Unit) {
    Files.createDirectories(file.parentFile.toPath())
    val temporary = Files.createTempFile(file.parentFile.toPath(), ".objc-export-", ".tmp")
    try {
        writer(temporary)
        Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun writeTextAtomically(file: File, content: String) = writeAtomically(file) {
    Files.writeString(it, content)
}
