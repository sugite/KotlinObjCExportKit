package io.github.sugite.koek.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

abstract class ObjCExportExtension {
    abstract val policyFile: RegularFileProperty
}

abstract class DumpObjCExportSignatures : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:org.gradle.api.tasks.PathSensitive(PathSensitivity.RELATIVE)
    abstract val signaturesFile: RegularFileProperty

    @TaskAction
    fun dump() {
        val file = signaturesFile.orNull ?: throw GradleException(
            "Candidate signatures are disabled. Run this task with -PobjcExportDumpSignatures=true",
        )
        logger.lifecycle(file.asFile.readText())
    }
}

class ObjCExportPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("objcExport", ObjCExportExtension::class.java)
        extension.policyFile.convention(project.layout.projectDirectory.file("objc-export.api"))
        val dumpSignatures = project.providers.gradleProperty("objcExportDumpSignatures")
            .map { it.toBooleanStrict() }.getOrElse(false)
        val compiler = project.configurations.create("objcExportCompiler") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.isTransitive = false
            it.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        }
        project.dependencies.add(compiler.name, "io.github.sugite.koek:compiler-plugin:0.1.0")

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
                target.binaries.withType(Framework::class.java).configureEach { framework ->
                    val report = project.layout.buildDirectory.file("objc-export/${target.name}/${framework.name}/api.txt")
                    framework.linkTaskProvider.configure { task ->
                        task.inputs.files(extension.policyFile)
                            .withPropertyName("objcExportPolicy")
                            .withPathSensitivity(PathSensitivity.RELATIVE)
                        task.inputs.files(compiler).withPropertyName("objcExportCompiler").withNormalizer(
                            org.gradle.api.tasks.ClasspathNormalizer::class.java,
                        )
                        task.outputs.file(report).withPropertyName("objcExportReport")
                        task.inputs.property("objcExportDumpSignatures", dumpSignatures)
                        if (dumpSignatures) {
                            task.outputs.file(report.map { it.asFile.resolveSibling("signatures.txt") })
                                .withPropertyName("objcExportSignatures")
                        }
                        val bundle = report.map { it.asFile.resolveSibling("headers.zip") }
                        task.outputs.file(bundle).withPropertyName("objcExportHeaderBundle")
                        task.compilerPluginClasspath = task.compilerPluginClasspath?.plus(compiler) ?: compiler
                        task.compilerPluginOptions.addPluginArgument(
                            "io.github.sugite.koek",
                            SubpluginOption("policy", lazy { extension.policyFile.get().asFile.absolutePath }),
                        )
                        task.compilerPluginOptions.addPluginArgument(
                            "io.github.sugite.koek",
                            SubpluginOption("report", lazy { report.get().asFile.absolutePath }),
                        )
                        task.compilerPluginOptions.addPluginArgument(
                            "io.github.sugite.koek",
                            SubpluginOption("dumpSignatures", dumpSignatures.toString()),
                        )
                        // The link task owns both Native outputs and the final split
                        // headers, including direct links and XCFramework dependencies.
                        task.doLast(FinalizeObjCFramework(bundle, task.outputFile))
                    }
                    if (target.name == "iosSimulatorArm64" && framework.name == "debugFramework") {
                        project.tasks.register("dumpObjCExportSignatures", DumpObjCExportSignatures::class.java) { task ->
                            task.group = "objc export"
                            task.description = "Print Kotlin signatures; requires -PobjcExportDumpSignatures=true"
                            if (dumpSignatures) {
                                task.dependsOn(framework.linkTaskProvider)
                                task.signaturesFile.set(project.layout.file(report.map { it.asFile.resolveSibling("signatures.txt") }))
                            }
                        }
                    }
                }
            }
        }
    }
}
