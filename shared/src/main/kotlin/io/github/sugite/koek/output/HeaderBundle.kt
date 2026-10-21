package io.github.sugite.koek.output

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val HEADER_BUNDLE_VERSION = "1\n"

/** Versioned rendered text only; no Native compiler model crosses this boundary. */
internal data class HeaderBundle(val frameworkName: String, val headers: Map<String, String>) {
    init {
        require(frameworkName.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "Invalid framework name in header bundle: $frameworkName" }
        require(headers.isNotEmpty() && "$frameworkName.h" in headers) { "Header bundle is missing its umbrella" }
        require(headers.keys.all(::isHeaderName)) { "Invalid header filename in header bundle" }
        require(headers.keys.map { it.lowercase() }.distinct().size == headers.size) { "Duplicate header names in header bundle" }
    }

    fun write(file: File) = writeAtomically(file) { temporary ->
        val entries = linkedMapOf("format-version" to HEADER_BUNDLE_VERSION, "framework-name" to "$frameworkName\n") +
            headers.toSortedMap().mapKeys { "Headers/${it.key}" }
        ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name).apply { setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)) })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    companion object {
        fun isHeaderName(name: String): Boolean = name.matches(Regex("[A-Za-z][A-Za-z0-9_]*\\.h"))

        fun read(file: File): HeaderBundle = ZipFile(file).use { zip ->
            val entries = linkedMapOf<String, String>()
            zip.entries().asSequence().forEach { entry ->
                require(!entry.isDirectory && (entry.name in setOf("format-version", "framework-name") ||
                    entry.name.startsWith("Headers/") && isHeaderName(entry.name.removePrefix("Headers/")))) {
                    "Unknown header bundle entry: ${entry.name}"
                }
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                require(entries.put(entry.name, text) == null) { "Duplicate header bundle entry: ${entry.name}" }
            }
            check(entries["format-version"] == HEADER_BUNDLE_VERSION) { "Unsupported ObjC header bundle version in $file" }
            HeaderBundle(
                entries["framework-name"]?.trim() ?: error("Missing framework name in $file"),
                entries.filterKeys { it.startsWith("Headers/") }.mapKeys { it.key.removePrefix("Headers/") },
            )
        }
    }
}
