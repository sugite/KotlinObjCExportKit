@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.sugite.koek.compiler

import org.jetbrains.kotlin.backend.konan.descriptors.enumEntries
import org.jetbrains.kotlin.backend.konan.objcexport.*
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

internal class SyntheticExports(private val plan: ExportPlan, private val namer: ObjCExportNamer) {
    private val decisions = plan.types.keys.associateWith { type ->
        val requested = plan.synthetic[type].orEmpty()
        val selectors = linkedMapOf<Pair<String, Boolean>, Boolean>()
        val roles = linkedSetOf<String>()
        fun selector(role: String, selector: String, classMember: Boolean = true) {
            roles += role
            selectors[selector to classMember] = role in requested
        }
        if (type.kind == ClassKind.OBJECT) {
            selector("instance", namer.getObjectInstanceSelector(type))
            selector("shared", namer.getObjectPropertySelector(type))
        }
        type.companionObjectDescriptor?.let {
            selector("companion", namer.getCompanionObjectPropertySelector(type))
            if ("companion" in requested) plan.requireType(it)
        }
        if (type.kind == ClassKind.ENUM_CLASS) {
            type.enumEntries.forEach { selector("enum:${it.name.asString()}", namer.getEnumEntrySelector(it)) }
            type.getEnumValuesFunctionDescriptor()?.let { selector("values", namer.getEnumStaticMemberSelector(it)) }
            type.getEnumEntriesPropertyDescriptor()?.let { selector("entries", namer.getEnumStaticMemberSelector(it)) }
        }
        if (type.fqNameSafe.asString() == "kotlin.Throwable") {
            selector("asError", ObjCExportNamer.kotlinThrowableAsErrorMethodName, classMember = false)
        }
        require(requested.all { it in roles }) {
            "${plan.policy.location(plan.types.getValue(type))}: unknown synthetic exports for ${type.fqNameSafe}: ${requested - roles}"
        }
        selectors
    }

    fun filter(type: ClassDescriptor, stub: ObjCClass): ObjCClass {
        val members = stub.members.filter { member ->
            when (member) {
                is ObjCMethod -> allowed(type, member.selectors.joinToString(""), classMember = !member.isInstanceMethod)
                is ObjCProperty -> allowed(type, member.getterName ?: member.name, classMember = "class" in member.propertyAttributes)
                else -> true
            }
        }
        return when (stub) {
            is ObjCInterface -> ObjCInterfaceImpl(
                name = stub.name, comment = stub.comment, origin = stub.origin,
                attributes = stub.attributes, superProtocols = stub.superProtocols,
                members = members, categoryName = stub.categoryName, generics = stub.generics,
                superClass = stub.superClass, superClassGenerics = stub.superClassGenerics, extras = stub.extras,
            )
            is ObjCProtocol -> stub
        }
    }

    fun filter(spec: ObjCExportCodeSpec): ObjCExportCodeSpec {
        val byBinaryName = plan.types.keys.associateBy { namer.getClassOrProtocolName(it).binaryName }
        val rebuilt = mutableMapOf<ObjCClassForKotlinClass, ObjCClassForKotlinClass>()
        fun filterMethods(type: ObjCTypeForKotlinType): List<ObjCMethodSpec> {
            val descriptor = byBinaryName[type.binaryName] ?: error("Unexpected exported type: ${type.binaryName}")
            return type.methods.filter { method ->
                val selector = when (method) {
                    is ObjCGetterForObjectInstance -> method.selector
                    is ObjCGetterForKotlinEnumEntry -> method.selector
                    is ObjCClassMethodForKotlinEnumValuesOrEntries -> method.selector
                    is ObjCKotlinThrowableAsErrorMethod -> ObjCExportNamer.kotlinThrowableAsErrorMethodName
                    else -> null
                }
                selector == null || allowed(descriptor, selector, classMember = method !is ObjCKotlinThrowableAsErrorMethod)
            }
        }
        fun rebuild(type: ObjCClassForKotlinClass): ObjCClassForKotlinClass = rebuilt.getOrPut(type) {
            ObjCClassForKotlinClass(
                type.binaryName, type.irClassSymbol, filterMethods(type), type.categoryMethods,
                type.superClassNotAny?.let { rebuild(it) },
            )
        }
        return ObjCExportCodeSpec(spec.files, spec.types.map<ObjCTypeForKotlinType, ObjCTypeForKotlinType> { type ->
            when (type) {
                // Explicit upcasts avoid K2 inferring an inaccessible internal subtype
                // as the common type and emitting an invalid sibling-class cast.
                is ObjCClassForKotlinClass -> rebuild(type) as ObjCTypeForKotlinType
                is ObjCProtocolForKotlinInterface -> type as ObjCTypeForKotlinType
            }
        })
    }

    private fun allowed(type: ClassDescriptor, selector: String, classMember: Boolean): Boolean =
        decisions[type]?.get(selector to classMember) ?: true
}
