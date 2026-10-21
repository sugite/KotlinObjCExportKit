package io.github.sugite.koek.annotations

/**
 * Selects one declaration for the Objective-C API. Members inherit their enclosing
 * annotated type's group, but must each be selected explicitly.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class ObjCExport(
    val group: String = "",
    val access: ObjCExportAccess = ObjCExportAccess.READ,
    val synthetic: Array<String> = [],
)

public enum class ObjCExportAccess { READ, READWRITE }
