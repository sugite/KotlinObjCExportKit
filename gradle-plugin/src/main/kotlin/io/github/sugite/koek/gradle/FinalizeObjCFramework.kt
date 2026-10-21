package io.github.sugite.koek.gradle

import io.github.sugite.koek.output.HeaderBundle
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import java.io.File

internal class FinalizeObjCFramework(
    private val bundle: Provider<File>,
    private val framework: Provider<File>,
) : Action<Task> {
    override fun execute(task: Task) {
        FrameworkHeaderWriter.write(framework.get(), HeaderBundle.read(bundle.get()))
    }
}
