import DemoKit
import Foundation

@MainActor
enum ExportChecks {
    static func run() throws -> [String] {
        let checks: [(String, () throws -> Void)] = [
            ("DTO construction and read-only property", verifyPerson),
            ("Swift to Kotlin method call", verifyGreeting),
            ("Writable property round trip", verifyWritableProperty),
            ("Kotlin to Swift protocol callback", verifyCallback),
            ("Selected enum entries", verifyEnumEntries),
        ]
        return try checks.map { title, check in
            try check()
            return title
        }
    }

    static func verifyPerson() throws {
        let person = Person(name: "Ada")
        try require(person.name == "Ada", "Person.name did not round-trip through Kotlin")
    }

    static func verifyGreeting() throws {
        let service = GreetingService()
        try require(service.greet(person: Person(name: "Ada")) == "Hello, Ada!", "Unexpected greeting")
    }

    static func verifyWritableProperty() throws {
        let service = GreetingService()
        let person = Person(name: "Ada")
        try require(service.enabled, "The initial enabled value should be true")
        service.enabled = false
        try require(!service.enabled, "The Kotlin setter did not retain false")
        try require(service.greet(person: person).isEmpty, "The Kotlin method did not observe the Swift write")
        service.enabled = true
        try require(service.greet(person: person) == "Hello, Ada!", "The property could not be enabled again")
    }

    static func verifyCallback() throws {
        let listener = ListenerProbe()
        GreetingService().notify(person: Person(name: "Ada"), listener: listener)
        try require(listener.messages == ["Hello, Ada!"], "Expected exactly one Kotlin to Swift callback")
    }

    static func verifyEnumEntries() throws {
        try require(GreetingState.ready === GreetingState.ready, "Enum entries must retain their identity")
        try require(GreetingState.ready !== GreetingState.done, "Different enum entries must be distinct")
    }

    private static func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
        if !condition() {
            throw CheckFailure(message: message)
        }
    }
}

private struct CheckFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private final class ListenerProbe: NSObject, GreetingListener {
    var messages: [String] = []

    func onGreeting(message: String) {
        messages.append(message)
    }
}
