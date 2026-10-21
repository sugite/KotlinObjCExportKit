package example

import io.github.sugite.koek.annotations.ObjCExport
import io.github.sugite.koek.annotations.ObjCExportAccess

@ObjCExport(group = "Models")
data class Person @ObjCExport constructor(
    @property:ObjCExport val name: String,
)

@ObjCExport(group = "Services")
interface GreetingListener {
    @ObjCExport
    fun onGreeting(message: String)
}

@ObjCExport(group = "Services")
class GreetingService @ObjCExport constructor() {
    @ObjCExport(access = ObjCExportAccess.READWRITE)
    var enabled: Boolean = true

    @ObjCExport
    fun greet(person: Person): String = if (enabled) "Hello, ${person.name}!" else ""

    @ObjCExport
    fun notify(person: Person, listener: GreetingListener) {
        listener.onGreeting(greet(person))
    }

    fun diagnosticMessage(): String = "This method is Kotlin-only."
}

@ObjCExport(group = "Models", synthetic = ["enum:READY", "enum:DONE"])
enum class GreetingState { READY, DONE, INTERNAL }

class InternalDiagnostics(val message: String)
