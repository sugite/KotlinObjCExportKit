@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.sugite.koek.compiler

import org.jetbrains.kotlin.backend.konan.driver.phases.CreateObjCExportCodeSpecPhase
import org.jetbrains.kotlin.backend.konan.driver.phases.ProduceObjCExportInterfacePhase
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.config.LoggingContext
import org.jetbrains.kotlin.config.phaser.NamedCompilerPhase
import java.io.File

private const val WRAPPER_PROTOCOL_VERSION = 1

internal object ExportConfiguration {
    val policy = CompilerConfigurationKey.create<String>("ObjC export policy")
    val report = CompilerConfigurationKey.create<String>("ObjC export report")
    val dumpSignatures = CompilerConfigurationKey.create<Boolean>("ObjC export candidate signatures")
    val session = CompilerConfigurationKey.create<ExportSession>("ObjC export session")
}

class ExportCommandLineProcessor : CommandLineProcessor {
    override val pluginId = "io.github.sugite.koek"
    override val pluginOptions = listOf(
        CliOption("policy", "path", "Export policy file", required = true),
        CliOption("report", "path", "Resolved API report", required = true),
        CliOption("dumpSignatures", "true|false", "Collect candidate signatures", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "policy" -> configuration.put(ExportConfiguration.policy, value)
            "report" -> configuration.put(ExportConfiguration.report, value)
            "dumpSignatures" -> configuration.put(ExportConfiguration.dumpSignatures, value.toBooleanStrict())
            else -> error("Unknown ObjC export option: ${option.optionName}")
        }
    }
}

class ExportPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2 = false

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        check(KotlinCompilerVersion.VERSION == "2.2.21") {
            "ObjC export adapter supports Kotlin 2.2.21; found ${KotlinCompilerVersion.VERSION}."
        }
        val policy = configuration.get(ExportConfiguration.policy) ?: return
        val report = configuration.get(ExportConfiguration.report) ?: error("Missing ObjC export report path")
        configuration.put(ExportConfiguration.session, ExportSession(
            ExportPolicy(File(policy)), File(report), configuration.getBoolean(ExportConfiguration.dumpSignatures),
        ))
        installPhase(configuration, ProduceObjCExportInterfacePhase) { context, input, _ ->
            session(context).produceInterface(context, input)
        }
        installPhase(configuration, CreateObjCExportCodeSpecPhase) { context, input, next ->
            session(context).filterCodeSpec(next(context, input))
        }
    }

    private fun session(context: org.jetbrains.kotlin.backend.konan.ConfigChecks): ExportSession =
        context.config.configuration.getNotNull(ExportConfiguration.session)
}

// The phase body is shared by compiler invocations. Store each handler in its own
// CompilerConfiguration so another framework or a later invocation cannot reuse its policy.
private fun <Context, Input, Output> installPhase(
    configuration: CompilerConfiguration,
    phase: NamedCompilerPhase<Context, Input, Output>,
    handler: (Context, Input, (Context, Input) -> Output) -> Output,
) where Context : LoggingContext, Context : org.jetbrains.kotlin.backend.konan.ConfigChecks {
    synchronized(phase) {
        val field = try {
            phase.javaClass.getDeclaredField("\$op").apply { isAccessible = true }
        } catch (exception: ReflectiveOperationException) {
            error("Unsupported Native phase ${phase.name}: missing phase body; Kotlin 2.2.21 adapter required. $exception")
        }
        val current = field.get(phase)
        check(current is Function2<*, *, *>) { "Unsupported Native phase body: ${phase.name}" }
        val wrapped = current.javaClass.name == ExportPhaseBody::class.java.name
        val original = if (wrapped) wrapperField(current, "original") else current
        check(original is Function2<*, *, *>) { "Unsupported original phase body: ${phase.name}" }
        @Suppress("UNCHECKED_CAST")
        val key: CompilerConfigurationKey<(Context, Input, (Context, Input) -> Output) -> Output> =
            if (wrapped) {
                val existing = wrapperField(current, "key")
                check(existing is CompilerConfigurationKey<*>) { "Unsupported ObjC export wrapper key" }
                existing as CompilerConfigurationKey<(Context, Input, (Context, Input) -> Output) -> Output>
            } else {
                CompilerConfigurationKey.create("ObjC export interceptor: ${phase.name}")
            }
        // Reuse the key across cached plugin classloaders; each configuration owns
        // its handler, which resolves its session from the invocation's context.
        configuration.put(key, handler)
        @Suppress("UNCHECKED_CAST")
        field.set(phase, ExportPhaseBody(original as (Context, Input) -> Output, key))
    }
}

private fun wrapperField(wrapper: Any, name: String): Any {
    try {
        val version = wrapper.javaClass.getDeclaredField("protocolVersion").apply { isAccessible = true }
        check(version.type == Int::class.javaPrimitiveType && version.getInt(wrapper) == WRAPPER_PROTOCOL_VERSION) {
            "Incompatible ObjC export phase wrapper protocol; restart the Gradle daemon after updating the plugin"
        }
        return wrapper.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(wrapper)
            ?: error("Missing ObjC export wrapper value: $name")
    } catch (exception: ReflectiveOperationException) {
        error("Incompatible cached ObjC export phase wrapper; restart the Gradle daemon after updating the plugin. $exception")
    }
}

private class ExportPhaseBody<Context, Input, Output>(
    val original: (Context, Input) -> Output,
    val key: CompilerConfigurationKey<(Context, Input, (Context, Input) -> Output) -> Output>,
) : (Context, Input) -> Output where Context : org.jetbrains.kotlin.backend.konan.ConfigChecks {
    @Suppress("unused")
    val protocolVersion: Int = WRAPPER_PROTOCOL_VERSION

    override fun invoke(context: Context, input: Input): Output =
        context.config.configuration.get(key)?.invoke(context, input, original) ?: original(context, input)
}
