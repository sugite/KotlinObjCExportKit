package io.github.sugite.koek.gradle

internal fun splitFrameworkModuleMap(frameworkName: String, original: String): String {
    val name = Regex.escape(frameworkName)
    val supported = Regex(
        """\s*framework\s+module\s+"$name"\s*\{\s*umbrella\s+header\s+"$name\.h"\s*export\s*\*\s*(?:module\s*\*\s*\{\s*export\s*\*\s*}\s*)?(?:use\s+[A-Za-z_][A-Za-z0-9_.]*\s*)*}\s*""",
    )
    check(supported.matches(original)) {
        "Unsupported Native modulemap for $frameworkName; refusing to overwrite unknown module directives:\n$original"
    }
    val dependencies = Regex("""\buse\s+([A-Za-z_][A-Za-z0-9_.]*)""").findAll(original).map { it.groupValues[1] }.toList()
    return buildString {
        appendLine("framework module \"$frameworkName\" {")
        appendLine("    umbrella header \"$frameworkName.h\"")
        appendLine("    export *")
        dependencies.forEach { appendLine("    use $it") }
        appendLine("}")
    }
}
