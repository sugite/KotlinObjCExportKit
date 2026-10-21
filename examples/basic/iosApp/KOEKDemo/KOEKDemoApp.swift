import SwiftUI

@main
struct KOEKDemoApp: App {
    var body: some Scene {
        WindowGroup {
            DemoView()
        }
    }
}

private struct DemoView: View {
    @State private var results: [String] = []
    @State private var failure: String?

    var body: some View {
        NavigationView {
            List {
                Section(header: Text("Swift ↔ Kotlin")) {
                    Text("Each check below calls the generated DemoKit framework.")
                    ForEach(results, id: \.self) { title in
                        Label(title, systemImage: "checkmark.circle.fill")
                            .foregroundColor(.green)
                    }
                    if let failure {
                        Label(failure, systemImage: "xmark.circle.fill")
                            .foregroundColor(.red)
                    }
                    Button("Run checks again", action: runChecks)
                }
                Section(header: Text("Build-time verification")) {
                    Text("The verification script also checks header groups and rejects Swift access to hidden APIs.")
                }
            }
            .navigationTitle("KOEK Demo")
        }
        .task { runChecks() }
    }

    private func runChecks() {
        do {
            results = try ExportChecks.run()
            failure = nil
        } catch {
            results = []
            failure = error.localizedDescription
        }
    }
}
