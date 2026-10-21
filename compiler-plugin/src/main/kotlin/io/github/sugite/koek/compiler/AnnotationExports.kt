@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.sugite.koek.compiler

import org.jetbrains.kotlin.backend.common.serialization.findSourceFile
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedSimpleFunctionDescriptor

internal fun exportSignature(descriptor: FunctionDescriptor): String = buildString {
    append(descriptor.fqNameSafe.asString())
    append('(')
    val parameters = listOfNotNull(descriptor.extensionReceiverParameter?.type) + descriptor.valueParameters.map { it.type }
    append(parameters.joinToString(",") { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it).filterNot(Char::isWhitespace) })
    append(')')
}

internal fun exportPropertySignature(descriptor: PropertyDescriptor): String {
    val name = descriptor.fqNameSafe.asString()
    val receiver = descriptor.extensionReceiverParameter?.type ?: return name
    return "$name(${DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(receiver).filterNot(Char::isWhitespace)})"
}

internal class AnnotationExports(
    modules: List<ModuleDescriptor>,
    fileRules: List<ExportRule>,
    private val collectSignatures: Boolean = false,
) {
    val rules = mutableListOf<ExportRule>()
    val signatures = sortedSetOf<String>()
    private val marker = FqName("io.github.sugite.koek.annotations.ObjCExport")
    private val visited = mutableSetOf<DeclarationDescriptor>()
    private val configuredGroups = fileRules.filter { it.kind in setOf("class", "protocol") }.associate { it.symbol to it.header }

    init {
        modules.distinct().forEach { module ->
            // The regular module provider includes dependencies, which would walk
            // the same dependency package trees once for every exported module.
            val provider = (module as? ModuleDescriptorImpl)?.packageFragmentProviderForModuleContentWithoutDependencies
                ?: error("Unsupported module descriptor for Kotlin 2.2.21 ObjC export: ${module.javaClass.name}")
            val packages = linkedSetOf<FqName>()
            fun visitPackage(name: FqName) {
                if (!packages.add(name)) return
                provider.getPackageFragments(name).forEach { fragment ->
                    fragment.getMemberScope().getContributedDescriptors().forEach(::visit)
                }
                provider.getSubPackagesOf(name) { true }.sortedBy { it.asString() }.forEach(::visitPackage)
            }
            visitPackage(FqName.ROOT)
        }
    }

    private fun group(descriptor: DeclarationDescriptor): String {
        val explicit = (descriptor.annotations.findAnnotation(marker)?.allValueArguments?.get(Name.identifier("group")) as? StringValue)?.value
        if (!explicit.isNullOrEmpty()) return explicit
        configuredGroups[descriptor.fqNameSafe.asString()]?.let { return it }
        val owner = descriptor.containingDeclaration
        return if (owner is ClassDescriptor) group(owner) else ""
    }

    private fun visit(descriptor: DeclarationDescriptor) {
        if (!visited.add(descriptor)) return
        if (descriptor is CallableMemberDescriptor && descriptor.kind != CallableMemberDescriptor.Kind.DECLARATION) return
        if (collectSignatures && descriptor is FunctionDescriptor && !DescriptorVisibilities.isPrivate(descriptor.visibility)) {
            signatures += "function\t${exportSignature(descriptor)}"
        }
        if (collectSignatures && descriptor is PropertyDescriptor && !DescriptorVisibilities.isPrivate(descriptor.visibility)) {
            signatures += "property\t${exportPropertySignature(descriptor)}"
        }
        descriptor.annotations.findAnnotation(marker)?.let { annotation ->
            val kind = when (descriptor) {
                is ClassDescriptor -> if (descriptor.kind == ClassKind.INTERFACE) "protocol" else "class"
                is PropertyDescriptor -> "property"
                is FunctionDescriptor -> "function"
                else -> error("@ObjCExport is not supported on ${descriptor.fqNameSafe}")
            }
            val name = when (descriptor) {
                is FunctionDescriptor -> exportSignature(descriptor)
                is PropertyDescriptor -> exportPropertySignature(descriptor)
                else -> descriptor.fqNameSafe.asString()
            }
            val origin = "@ObjCExport $name${sourceLocation(descriptor)}"
            validateVisibility(descriptor, origin)
            val header = group(descriptor)
            require(header.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
                "$origin: specify a valid group here or on an enclosing type"
            }
            val access = (annotation.allValueArguments[Name.identifier("access")] as? EnumValue)?.enumEntryName?.asString() ?: "READ"
            require(access in setOf("READ", "READWRITE") && (kind == "property" || access == "READ")) {
                "$origin: access = READWRITE is only valid on a property"
            }
            rules += ExportRule(kind, header, name, if (kind == "property") access.lowercase() else null, 0, origin)
            val synthetic = (annotation.allValueArguments[Name.identifier("synthetic")] as? ArrayValue)?.value.orEmpty()
            require(synthetic.isEmpty() || descriptor is ClassDescriptor) { "$origin: synthetic entries require a class/object/enum" }
            val roles = synthetic.map { (it as? StringValue)?.value ?: error("$origin: expected a synthetic role string") }
            require(roles.distinct().size == roles.size) { "$origin: duplicate synthetic role" }
            roles.forEach { role -> rules += ExportRule("synthetic", header, name, role, 0, origin) }
        }
        if (descriptor is ClassDescriptor) {
            descriptor.constructors.forEach(::visit)
            descriptor.unsubstitutedMemberScope.getContributedDescriptors().filter { it.containingDeclaration == descriptor }.forEach(::visit)
        }
    }

    private fun validateVisibility(descriptor: DeclarationDescriptor, origin: String) {
        var current: DeclarationDescriptor? = descriptor
        while (current is DeclarationDescriptorWithVisibility) {
            val declaration = current
            val visibility = declaration.visibility
            require(!DescriptorVisibilities.isPrivate(visibility) && visibility != DescriptorVisibilities.INTERNAL &&
                visibility != DescriptorVisibilities.LOCAL) {
                "$origin: cannot export ${visibility.name} declaration ${declaration.fqNameSafe}"
            }
            current = declaration.containingDeclaration
        }
    }

    private fun sourceLocation(descriptor: DeclarationDescriptor): String {
        val declaration = DescriptorToSourceUtils.descriptorToDeclaration(descriptor)
        val file = declaration?.containingFile
        if (declaration != null && file != null) {
            val path = file.virtualFile?.path ?: file.name
            val line = file.text.take(declaration.textOffset).count { it == '\n' } + 1
            return " at $path:$line"
        }
        val source = (descriptor as? DeclarationDescriptorWithSource)?.source?.containingFile
        if (source != null && source != SourceFile.NO_SOURCE_FILE) return " at ${source.name}"
        // Native's findSourceFile throws for descriptors without file metadata.
        val librarySource = when {
            descriptor is DeserializedSimpleFunctionDescriptor && descriptor.proto.hasExtension(KlibMetadataProtoBuf.functionFile) ->
                descriptor.findSourceFile()
            descriptor is DeserializedPropertyDescriptor && descriptor.proto.hasExtension(KlibMetadataProtoBuf.propertyFile) ->
                descriptor.findSourceFile()
            else -> null
        }
        return librarySource?.let { " at ${it.name}" }.orEmpty()
    }
}
