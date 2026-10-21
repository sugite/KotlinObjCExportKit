package io.github.sugite.koek.compiler

import org.jetbrains.kotlin.backend.konan.objcexport.*

internal class HeaderLayout(
    private val header: ObjCHeader,
    grouped: List<Pair<String, ObjCExportStub>>,
    private val prefix: String,
    private val exportKDoc: Boolean,
    skippedTypes: Set<String>,
    private val ruleLocations: Map<String, List<String>>,
) {
    var apiReport: String = ""
    private val groups = grouped.groupBy({ it.first }, { it.second }).toSortedMap()
    private val owners = linkedMapOf<String, String>()
    private val dependencies = groups.keys.associateWith { linkedSetOf<String>() }

    init {
        require(groups.keys.map { it.lowercase() }.distinct().size == groups.size) {
            "Header names must be distinct on case-insensitive filesystems"
        }
        groups.forEach { (group, stubs) ->
            stubs.filterIsInstance<ObjCClass>().filterNot { it is ObjCInterface && it.categoryName != null }.forEach { stub ->
                require(owners.put(stub.name, group) == null) { "Duplicate ObjC type: ${stub.name}" }
            }
        }
        groups.forEach { (group, stubs) ->
            stubs.forEach { stub ->
                val missing = references(stub) intersect skippedTypes
                require(missing.isEmpty()) {
                    val members = (stub as? ObjCClass)?.members.orEmpty().filter { (references(it) intersect missing).isNotEmpty() }
                    val details = members.joinToString("; ") { member ->
                        val kotlinName = member.origin?.name?.asString() ?: member.name
                        val locations = ruleLocations["${stub.name}.$kotlinName"].orEmpty()
                        "${stub.name}.$kotlinName (${locations.joinToString().ifEmpty { "generated member" }})"
                    }.ifEmpty { ruleLocations[stub.name].orEmpty().joinToString() }
                    "$details references a companion type missing from the export policy: $missing. " +
                        "Annotate the companion with @ObjCExport or remove the referring member's export."
                }
                if (stub is ObjCClass) {
                    val strong = stub.superProtocols + if (stub is ObjCInterface) {
                        listOfNotNull(stub.superClass, stub.name.takeIf { stub.categoryName != null })
                    } else emptyList()
                    strong.mapNotNull { owners[it] }.filter { it != group }.forEach { dependencies.getValue(group) += it }
                }
            }
            if (group != "Base") dependencies.getValue(group) += "Base"
        }
        orderedGroups()
    }

    fun render(frameworkName: String): Map<String, String> = linkedMapOf<String, String>().apply {
        val order = orderedGroups()
        order.forEach { group ->
            val imports = dependencies.getValue(group).sorted().map { "#import \"${fileName(it)}\"" }
            val content = header.copy(
                stubs = groups.getValue(group),
                classForwardDeclarations = if (group == "Base") header.classForwardDeclarations else emptySet(),
                protocolForwardDeclarations = if (group == "Base") header.protocolForwardDeclarations else emptySet(),
            ).render(exportKDoc)
            put(fileName(group), (listOf("#pragma once") + imports + content).joinToString("\n", postfix = "\n"))
        }
        val umbrella = "$frameworkName.h"
        require(umbrella !in keys) { "A split header collides with the umbrella header: $umbrella" }
        put(umbrella, (listOf("#pragma once") + order.map { "#import \"${fileName(it)}\"" }).joinToString("\n", postfix = "\n"))
    }

    private fun fileName(group: String) = "$prefix$group.h"

    fun surfaceReport(): String = buildString {
        appendLine()
        appendLine("Generated ObjC declarations (including native runtime support):")
        groups.forEach { (group, stubs) ->
            stubs.forEach { stub ->
                appendLine("${fileName(group)}\t${stub.name}")
                (stub as? ObjCClass)?.members?.forEach { member ->
                    val name = when (member) {
                        is ObjCMethod -> (if (member.isInstanceMethod) "-" else "+") + member.selectors.joinToString("")
                        is ObjCProperty -> "property ${member.name} (${member.propertyAttributes.joinToString(",")})"
                        else -> member.name
                    }
                    appendLine("  $name")
                }
            }
        }
    }

    private fun orderedGroups(): List<String> {
        val result = mutableListOf<String>()
        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(group: String) {
            if (group in visited) return
            require(visiting.add(group)) {
                "Header dependency cycle: ${(visiting + group).joinToString(" -> ")}; move shared declarations into a common header"
            }
            dependencies.getValue(group).sorted().forEach { visit(it) }
            visiting -= group
            visited += group
            result += group
        }
        groups.keys.forEach { visit(it) }
        return result
    }

    private fun references(stub: ObjCExportStub): Set<String> = when (stub) {
        is ObjCInterface -> stub.members.flatMap { references(it) }.toSet() +
            stub.superClassGenerics.flatMap { references(it) } + stub.superProtocols + listOfNotNull(stub.superClass)
        is ObjCProtocol -> stub.members.flatMap { references(it) }.toSet() + stub.superProtocols
        is ObjCMethod -> references(stub.returnType) + stub.parameters.flatMap { references(it.type) }
        is ObjCProperty -> references(stub.type)
        else -> emptySet()
    }

    private fun references(type: ObjCType): Set<String> = when (type) {
        is ObjCClassType -> setOf(type.className) + type.typeArguments.flatMap { references(it) }
        is ObjCProtocolType -> setOf(type.protocolName)
        is ObjCNullableReferenceType -> references(type.nonNullType)
        is ObjCBlockPointerType -> references(type.returnType) + type.parameters.flatMap { references(it.type) }
        else -> emptySet()
    }
}
