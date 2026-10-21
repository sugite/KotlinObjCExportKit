@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.sugite.koek.compiler

import org.jetbrains.kotlin.backend.konan.objcexport.ObjCEntryPoints
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportMapper
import org.jetbrains.kotlin.backend.konan.objcexport.shouldBeExposed
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.MemberScope

internal class ExportPlan(
    val policy: ExportPolicy,
    private val modules: List<ModuleDescriptor>,
    annotations: AnnotationExports,
) : ObjCEntryPoints {
    val rules = (policy.rules + annotations.rules).distinctBy { listOf(it.kind, it.header, it.symbol, it.access) }
    private val typeRules = rules.filter { it.kind in setOf("class", "protocol") }.groupBy { it.symbol }.mapValues { (_, entries) ->
        require(entries.size == 1) { "Conflicting type rules: ${entries.joinToString { policy.location(it) }}" }
        entries.single()
    }
    val types: Map<ClassDescriptor, ExportRule> = typeRules.values.associate { rule ->
        val descriptor = resolveClass(rule.symbol) ?: policy.failure(rule, "type not found: ${rule.symbol}")
        require((descriptor.kind == ClassKind.INTERFACE) == (rule.kind == "protocol")) {
            "${policy.location(rule)}: ${rule.symbol} must use ${if (descriptor.kind == ClassKind.INTERFACE) "protocol" else "class"}"
        }
        descriptor to rule
    }
    val members = linkedMapOf<CallableMemberDescriptor, ExportRule>()
    private val writable = mutableSetOf<PropertyDescriptor>()
    val synthetic = linkedMapOf<ClassDescriptor, MutableSet<String>>()
    private val exposureCache = mutableMapOf<CallableMemberDescriptor, Boolean>()
    private val writableCache = mutableMapOf<PropertyDescriptor, Boolean>()

    init {
        require(rules.isNotEmpty()) { "No @ObjCExport annotations or rules in ${policy.file}; refusing an empty API" }
        rules.filter { it.kind == "function" || it.kind == "property" }.forEach { rule ->
            val candidates = resolveMembers(rule).distinctBy { it.original }.filter { descriptor ->
                when {
                    '(' !in rule.symbol -> true
                    descriptor is FunctionDescriptor -> exportSignature(descriptor) == rule.symbol
                    descriptor is PropertyDescriptor -> exportPropertySignature(descriptor) == rule.symbol
                    else -> false
                }
            }
            if (candidates.size != 1) policy.failure(rule,
                "expected one declaration for ${rule.symbol}, found ${candidates.size}: " +
                    candidates.joinToString { if (it is FunctionDescriptor) exportSignature(it) else it.fqNameSafe.asString() },
            )
            val member = candidates.single()
            val owner = member.containingDeclaration as? ClassDescriptor
            if (owner != null && owner !in types) policy.failure(rule, "member owner is not allowed: ${owner.fqNameSafe}")
            if (owner != null && types.getValue(owner).header != rule.header) {
                policy.failure(rule, "a type and its members must use the same header")
            }
            val previous = members[member.original]
            if (previous != null && (previous.header != rule.header || previous.access != rule.access)) {
                policy.failure(rule, "conflicts with ${policy.location(previous)}")
            }
            members[member.original] = rule
            if (rule.access == "readwrite") {
                val property = member as PropertyDescriptor
                if (property.setter == null) policy.failure(rule, "property has no setter")
                writable += property.original
            }
        }
        rules.filter { it.kind == "synthetic" }.forEach { rule ->
            val owner = types.keys.singleOrNull { it.fqNameSafe.asString() == rule.symbol }
                ?: policy.failure(rule, "synthetic owner is not an allowed type")
            if (types.getValue(owner).header != rule.header) policy.failure(rule, "synthetic header differs from owner")
            synthetic.getOrPut(owner) { linkedSetOf() }.add(rule.access!!)
        }
    }

    override fun shouldBeExposed(descriptor: CallableMemberDescriptor): Boolean = exposureCache.getOrPut(descriptor) {
        if (descriptor is PropertyAccessorDescriptor) {
            val property = descriptor.correspondingProperty
            return@getOrPut if (descriptor is PropertySetterDescriptor) isWritable(property) else shouldBeExposed(property)
        }
        if (descriptor.original in members) return@getOrPut true
        // An exported inherited contract needs the subtype's dispatch adapter as well.
        // Omitting real overrides can silently call the base implementation from Swift.
        descriptor.containingDeclaration in types &&
            descriptor.overriddenDescriptors.any { shouldBeExposed(it) }
    }

    private fun isWritable(property: PropertyDescriptor): Boolean = writableCache.getOrPut(property) {
        property.original in writable ||
            (property.containingDeclaration in types && property.overriddenDescriptors.any { isWritable(it) })
    }

    fun validate(mapper: ObjCExportMapper) {
        types.forEach { (type, rule) ->
            if (!mapper.shouldBeExposed(type)) policy.failure(rule, "type cannot be exported (visibility, mapping or HiddenFromObjC): ${rule.symbol}")
        }
        members.forEach { (member, rule) ->
            if (!mapper.shouldBeExposed(member)) policy.failure(rule, "member cannot be exported (visibility or HiddenFromObjC): ${rule.symbol}")
            if (member is PropertyDescriptor && member.original in writable && !mapper.shouldBeExposed(member.setter!!)) {
                policy.failure(rule, "setter cannot be exported")
            }
        }
    }

    fun requireType(descriptor: ClassDescriptor): Boolean {
        require(descriptor in types) {
            "${policy.file}: exported API requires undeclared type ${descriptor.fqNameSafe}; " +
                "add @ObjCExport to the type or an explicit class/protocol rule. No dependency is exported automatically."
        }
        return true
    }

    private fun resolveClass(fqName: String): ClassDescriptor? {
        val segments = fqName.split('.')
        for (boundary in segments.lastIndex downTo 0) {
            for (module in modules) {
                var scope = module.getPackage(FqName(segments.take(boundary).joinToString("."))).memberScope
                var result: ClassDescriptor? = null
                for (segment in segments.drop(boundary)) {
                    result = scope.getContributedClassifier(Name.identifier(segment), NoLookupLocation.FROM_BACKEND) as? ClassDescriptor
                    if (result == null) break
                    scope = result.unsubstitutedInnerClassesScope
                }
                if (result != null) return result
            }
        }
        return null
    }

    private fun resolveMembers(rule: ExportRule): List<CallableMemberDescriptor> {
        val ownerName = rule.fqName.substringBeforeLast('.')
        val memberName = rule.fqName.substringAfterLast('.')
        val owner = resolveClass(ownerName)
        if (memberName == "<init>") return owner?.constructors?.toList().orEmpty()
        val scopes = owner?.let { listOf(it.unsubstitutedMemberScope) }
            ?: modules.map { it.getPackage(FqName(ownerName)).memberScope }
        return scopes.flatMap { scope -> scope.members(memberName, rule.kind) }
    }

    private fun MemberScope.members(name: String, kind: String): Collection<CallableMemberDescriptor> =
        if (kind == "property") getContributedVariables(Name.identifier(name), NoLookupLocation.FROM_BACKEND)
        else getContributedFunctions(Name.identifier(name), NoLookupLocation.FROM_BACKEND)

}
