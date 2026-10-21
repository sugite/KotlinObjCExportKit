package io.github.sugite.koek.compiler

import java.io.File

internal data class ExportRule(
    val kind: String,
    val header: String,
    val symbol: String,
    val access: String?,
    val line: Int,
    val origin: String? = null,
) {
    val fqName: String get() = symbol.substringBefore('(')
}

internal class ExportPolicy(val file: File) {
    val rules: List<ExportRule> = (if (file.exists()) file.readLines() else emptyList()).mapIndexedNotNull { index, raw ->
        val text = raw.substringBefore('#').trim()
        if (text.isEmpty()) return@mapIndexedNotNull null
        val columns = text.split(Regex("\\s+"), limit = 3)
        require(columns.size == 3) { "$file:${index + 1}: expected kind header symbol [access]" }
        val remainder = columns[2]
        val symbolAndAccess = if (columns[0] == "function") {
            var depth = 0
            remainder.forEach { character ->
                when (character) {
                    '(' -> depth++
                    ')' -> depth--
                    else -> require(!character.isWhitespace() || depth > 0) {
                        "$file:${index + 1}: whitespace is only allowed inside a function's parameter list"
                    }
                }
                require(depth >= 0) { "$file:${index + 1}: unbalanced function signature" }
            }
            require(depth == 0 && ('(' !in remainder || remainder.endsWith(')'))) {
                "$file:${index + 1}: unbalanced function signature"
            }
            listOf(remainder.filterNot(Char::isWhitespace))
        } else remainder.split(Regex("\\s+"))
        require(symbolAndAccess.size in 1..2) { "$file:${index + 1}: expected one symbol and optional access" }
        val rule = ExportRule(columns[0], columns[1], symbolAndAccess[0], symbolAndAccess.getOrNull(1), index + 1)
        require(rule.kind in setOf("class", "protocol", "function", "property", "synthetic")) {
            "$file:${rule.line}: unknown rule ${rule.kind}"
        }
        require(rule.header.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "$file:${rule.line}: invalid header name" }
        require('*' !in rule.fqName) { "$file:${rule.line}: wildcards are not supported" }
        require(when (rule.kind) {
            "property" -> rule.access in setOf("read", "readwrite")
            "synthetic" -> rule.access != null
            else -> rule.access == null
        }) { "$file:${rule.line}: invalid access for ${rule.kind}" }
        rule
    }.also { rules ->
        require(rules.map { Triple(it.kind, it.symbol, it.access) }.distinct().size == rules.size) {
            "$file: duplicate export rule"
        }
    }

    val typeRules = rules.filter { it.kind == "class" || it.kind == "protocol" }.associateBy { it.symbol }

    init {
        require(typeRules.size == rules.count { it.kind == "class" || it.kind == "protocol" }) {
            "$file: a type can belong to only one header"
        }
    }

    fun location(rule: ExportRule): String = rule.origin ?: "$file:${rule.line}"

    fun failure(rule: ExportRule, message: String): Nothing = error("${location(rule)}: $message")
}
