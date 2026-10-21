@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.sugite.koek.compiler

import io.github.sugite.koek.output.HeaderBundle
import io.github.sugite.koek.output.writeTextAtomically
import org.jetbrains.kotlin.backend.common.serialization.findSourceFile
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.backend.konan.driver.phases.FrontendPhaseOutput
import org.jetbrains.kotlin.backend.konan.objcexport.*
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.io.File

internal class ExportSession(
    private val policy: ExportPolicy,
    private val report: File,
    private val dumpSignatures: Boolean = false,
) {
    private lateinit var synthetic: SyntheticExports

    fun produceInterface(context: PhaseContext, input: FrontendPhaseOutput.Full): ObjCExportedInterface {
        val config = context.config
        val modules = listOf(input.moduleDescriptor) + input.moduleDescriptor.getExportedDependencies(config)
        val annotations = AnnotationExports(modules, policy.rules, collectSignatures = dumpSignatures)
        val signaturesFile = File(report.parentFile, "signatures.txt")
        if (dumpSignatures) {
            writeTextAtomically(signaturesFile, annotations.signatures.joinToString("\n", postfix = "\n"))
        } else {
            // Remove a previous opt-in dump so it cannot be mistaken for current diagnostics.
            java.nio.file.Files.deleteIfExists(signaturesFile.toPath())
        }
        val plan = ExportPlan(policy, modules, annotations)
        val mapper = ObjCExportMapper(
            input.frontendServices.deprecationResolver,
            unitSuspendFunctionExport = config.unitSuspendFunctionObjCExport,
            entryPoints = plan,
        )
        plan.validate(mapper)
        val collector = ExportProblems(context)
        val generics = config.configuration.getBoolean(KonanConfigKeys.OBJC_GENERICS)
        val blockNames = config.configuration.getBoolean(BinaryOptions.objcExportBlockExplicitParameterNames)
        val namer = ObjCExportNamerImpl(
            modules.toSet(), input.moduleDescriptor.builtIns, mapper, collector,
            context.objCExportTopLevelNamePrefix, local = false, objcGenerics = generics,
            disableSwiftMemberNameMangling = config.configuration.getBoolean(BinaryOptions.objcExportDisableSwiftMemberNameMangling),
            ignoreInterfaceMethodCollisions = config.configuration.getBoolean(BinaryOptions.objcExportIgnoreInterfaceMethodCollisions),
            nameCollisionMode = when {
                config.configuration.getBoolean(BinaryOptions.objcExportErrorOnNameCollisions) -> ObjCExportNameCollisionMode.ERROR
                config.configuration.getBoolean(BinaryOptions.objcExportReportNameCollisions) -> ObjCExportNameCollisionMode.WARNING
                else -> ObjCExportNameCollisionMode.NONE
            },
            explicitMethodFamily = config.configuration.getBoolean(BinaryOptions.objcExportExplicitMethodFamily),
        )
        synthetic = SyntheticExports(plan, namer)
        val skipped = linkedSetOf<ClassDescriptor>()
        val generator: ObjCExportHeaderGenerator = object : ObjCExportHeaderGenerator(emptyList(), mapper, namer, generics, blockNames, collector) {
            override val shouldExportKDoc = context.shouldExportKDoc()
            override fun getAdditionalImports() = config.configuration.getNotNull(KonanConfigKeys.FRAMEWORK_IMPORT_HEADERS)
            override fun shouldTranslateExtraClass(descriptor: ClassDescriptor): Boolean {
                // Native eagerly requests every companion, even if its property is later
                // suppressed. Other references to a skipped type are rejected by HeaderLayout.
                if (descriptor.isCompanionObject && descriptor !in plan.types) {
                    skipped += descriptor
                    return false
                }
                return plan.requireType(descriptor)
            }
        }
        val translator = ObjCExportTranslatorImpl(generator, mapper, namer, collector, generics, blockNames)
        generator.translateBaseDeclarations()
        val fileMembers = linkedMapOf<SourceFile, MutableList<CallableMemberDescriptor>>()
        val categories = linkedMapOf<ClassDescriptor, MutableList<CallableMemberDescriptor>>()
        val extraStubs = mutableListOf<Pair<String, ObjCExportStub>>()
        plan.members.keys.filter { it.containingDeclaration !is ClassDescriptor }.forEach { member ->
            val category = getClassIfCategory(member)
            if (category == null) fileMembers.getOrPut(member.findSourceFile()) { mutableListOf() } += member
            else {
                plan.requireType(category)
                require(plan.types.getValue(category).header == plan.members.getValue(member).header) {
                    "${policy.file}: a category must use the same header as its class"
                }
                categories.getOrPut(category) { mutableListOf() } += member
            }
        }
        fileMembers.entries.sortedBy { it.key.name }.forEach { (file, declarations) ->
            val headers = declarations.map { plan.members.getValue(it).header }.distinct()
            require(headers.size == 1) { "${policy.file}: top-level declarations in ${file.name} must use one header" }
            extraStubs += headers.single() to translator.translateFile(file, declarations.sortedBy { DescriptorRenderer.FQ_NAMES_IN_TYPES.render(it) })
        }
        categories.entries.sortedBy { it.key.fqNameSafe.asString() }.forEach { (type, declarations) ->
            extraStubs += plan.types.getValue(type).header to translator.translateExtensions(type, declarations.sortedBy { DescriptorRenderer.FQ_NAMES_IN_TYPES.render(it) })
        }
        plan.types.keys.sortedBy { it.fqNameSafe.asString() }.forEach { generator.requireClassOrInterface(it) }
        generator.translateModuleDeclarations()
        val result = generator.buildInterface()
        check(result.generatedClasses.all { it in plan.types }) { "Unexpected type added by the native exporter" }
        val originalHeader = generator.buildHeader()
        val byObjCName = plan.types.keys.associateBy { namer.getClassOrProtocolName(it).objCName }
        val grouped = originalHeader.stubs.map { stub ->
            val type = (stub as? ObjCClass)?.let { byObjCName[it.name] }
            if (type == null) "Base" to stub else plan.types.getValue(type).header to synthetic.filter(type, stub as ObjCClass)
        } + extraStubs
        val allStubs = grouped.map { it.second }
        val skippedNames = skipped.map { namer.getClassOrProtocolName(it).objCName }.toSet()
        val header = originalHeader.copy(
            stubs = allStubs,
            classForwardDeclarations = originalHeader.classForwardDeclarations.filterNot { it.className in skippedNames }.toSet(),
        )
        val ruleLocations = linkedMapOf<String, List<String>>()
        byObjCName.forEach { (name, type) ->
            ruleLocations[name] = listOf(policy.location(plan.types.getValue(type)))
            plan.members.entries.filter { it.key.containingDeclaration == type }.groupBy { it.key.name.asString() }.forEach { (member, entries) ->
                ruleLocations["$name.$member"] = entries.map { policy.location(it.value) }
            }
        }
        val layout = HeaderLayout(header, grouped, context.objCExportTopLevelNamePrefix, generator.shouldExportKDoc, skippedNames, ruleLocations)
        val selectedInterface = ObjCExportedInterface(
            result.generatedClasses, categories, fileMembers, header.render(generator.shouldExportKDoc), namer, mapper,
        )
        layout.apiReport = buildString {
            appendLine("Kotlin 2.2.21 / explicit ObjC export")
            plan.rules.sortedWith(compareBy({ it.header }, { it.kind }, { it.symbol }, { it.access.orEmpty() })).forEach {
                appendLine("${it.header}\t${it.kind}\t${it.symbol}\t${it.access.orEmpty()}")
            }
        }
        val frameworkName = File(OutputFiles(config.outputPath, config.target, config.produce).mainFile.absolutePath)
            .name.removeSuffix(".framework")
        HeaderBundle(frameworkName, layout.render(frameworkName)).write(File(report.parentFile, "headers.zip"))
        writeTextAtomically(report, layout.apiReport + layout.surfaceReport())
        return selectedInterface
    }

    fun filterCodeSpec(spec: ObjCExportCodeSpec): ObjCExportCodeSpec {
        check(::synthetic.isInitialized) { "ObjC export code-spec phase ran before interface generation; incompatible phase order or plugin" }
        return synthetic.filter(spec)
    }

}

private class ExportProblems(private val context: PhaseContext) : ObjCExportProblemCollector {
    override fun reportWarning(text: String) = context.messageCollector.report(CompilerMessageSeverity.WARNING, text)
    override fun reportWarning(declaration: DeclarationDescriptor, text: String) = reportWarning("${declaration.fqNameSafe}: $text")
    override fun reportError(text: String): Nothing = error(text)
    override fun reportError(declaration: DeclarationDescriptor, text: String): Nothing = error("${declaration.fqNameSafe}: $text")
    override fun reportException(throwable: Throwable): Nothing = throw throwable
}
