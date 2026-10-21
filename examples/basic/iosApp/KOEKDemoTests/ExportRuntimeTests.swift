@testable import KOEKDemo
import XCTest

final class ExportRuntimeTests: XCTestCase {
    @MainActor
    func testPersonConstruction() async throws {
        try ExportChecks.verifyPerson()
    }

    @MainActor
    func testGreetingMethod() async throws {
        try ExportChecks.verifyGreeting()
    }

    @MainActor
    func testWritableProperty() async throws {
        try ExportChecks.verifyWritableProperty()
    }

    @MainActor
    func testProtocolCallback() async throws {
        try ExportChecks.verifyCallback()
    }

    @MainActor
    func testSelectedEnumEntries() async throws {
        try ExportChecks.verifyEnumEntries()
    }
}
