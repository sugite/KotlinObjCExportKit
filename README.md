# Kotlin ObjC Export Kit (KOEK)

[简体中文](README.zh-CN.md)

> **Composite builds only.** KOEK is currently available only as a local source
> checkout included through Gradle composite builds. No binaries are published to
> Maven Central or the Gradle Plugin Portal. The coordinates shown below must be
> resolved through the documented local dependency substitutions.

**Choose the Kotlin APIs that iOS can use. Organize their Objective-C declarations into grouped headers. Keep one framework.**

KOEK adds an explicit allowlist to Kotlin/Native's Objective-C export pipeline.
Use `@ObjCExport` on the types and members you want to expose, and choose their
header group. Unselected declarations remain usable by Kotlin code.

This is an **experimental, source-only project** for **Kotlin 2.2.21**. It uses
internal compiler APIs and reflective phase interception. There is no published
Gradle Plugin Portal or Maven Central release. The included Debug example has
automated header, negative-compilation, and Swift runtime checks. This covers
the example's API surface, not every Kotlin declaration or build configuration.

## Why it exists

A Kotlin/Native framework can expose more Kotlin declarations than an iOS
application needs, collected into a large umbrella header. Making a Kotlin class
public does not necessarily mean every member should become a public iOS API.

KOEK lets you choose that boundary explicitly:

```kotlin
import io.github.sugite.koek.annotations.ObjCExport
import io.github.sugite.koek.annotations.ObjCExportAccess

@ObjCExport(group = "Services")
class GreetingService @ObjCExport constructor() {
    @ObjCExport(access = ObjCExportAccess.READWRITE)
    var enabled: Boolean = true

    @ObjCExport
    fun greet(name: String): String = if (enabled) "Hello, $name!" else ""

    fun diagnosticMessage(): String = "Kotlin-only"
}
```

iOS can construct `GreetingService`, read and write `enabled`, and call `greet`.
`diagnosticMessage` is not an exported entry point. Annotating a class does not
automatically export its constructors or members.

The result is one framework with an umbrella header and grouped headers:

```text
DemoKit.framework/
├── DemoKit
├── Headers/
│   ├── DemoKit.h
│   ├── DemoKitBase.h
│   ├── DemoKitModels.h
│   └── DemoKitServices.h
└── Modules/
    ├── module.modulemap
    └── objc-export-headers.txt
```

Split header filenames use the compiler's Objective-C top-level name prefix
followed by the group. The example assumes the default prefix for `DemoKit`.
Swift still uses `import DemoKit`.

## Requirements and scope

| Component | Current scope |
| --- | --- |
| Kotlin / Kotlin Gradle plugin | Exactly **2.2.21**; other compiler versions are rejected |
| JDK | **21** for building the plugins |
| Gradle Wrapper | **9.3.1**; broader compatibility is not claimed |
| Native build host | Apple Silicon macOS with an installed, selected Xcode and the required Apple SDKs |
| Annotations | JVM 17, iOS arm64, and iOS simulator arm64 variants |
| Demo verification | Python 3, an available iPhone simulator, and the tools above |
| Export backend | Kotlin/Native **Objective-C export**, not Swift export |
| Distribution | Local composite builds; no artifacts are published yet |
| License | MIT for KOEK; see [third-party notices](THIRD_PARTY_NOTICES.md) |

The annotation library has no platform-specific implementation. Applying the
Gradle plugin affects Kotlin/Native **Framework link tasks**, not Android or JVM
compilation tasks. Other Native targets require corresponding annotation
variants and separate validation; they are not enabled by this repository.

## Try the included example

The [basic example](examples/basic) is a separate consumer build. It contains a
DTO, a callback protocol, a service, a deliberately partial enum export, and a
Swift iOS application with XCTest assertions. The sample supports arm64 device
and arm64 simulator builds only.

From the repository root, with JDK 21 selected:

```bash
python3 examples/basic/verify.py
```

The script builds a Debug XCFramework, checks its public surface, runs five
Swift XCTest cases on an iOS simulator, and launches the demo application. It
reuses a booted iPhone simulator when available; otherwise it boots an available
iPhone. To choose a particular device, pass `--simulator <UDID>`.

| Automatic check | Expected result |
| --- | --- |
| Header grouping | Exactly Base, Models, and Services plus the umbrella; each selected definition is in its intended group |
| Header imports | Every header independently passes Objective-C syntax checking for device and simulator |
| XCFramework packaging | Exactly two arm64 slices; packaged headers match the linked frameworks |
| Hidden method and type | Swift cannot access `diagnosticMessage()` or `InternalDiagnostics` |
| Enum selection | Swift cannot access the unselected `GreetingState.internal` entry |
| Property access | Swift cannot write `Person.name`; it can read/write `GreetingService.enabled` at runtime |
| Swift runtime | DTO construction, greeting call, setter effects, Kotlin-to-Swift callback, and enum identity all pass |

Logs and `summary.json` are written under `examples/basic/build/verification/`,
which is ignored by Git. A failed check exits nonzero. No signing team is needed
for simulator verification. This command does not publish artifacts.

To use Xcode interactively, first build the framework, then open the checked-in
project:

```bash
./gradlew -p examples/basic :shared:assembleDemoKitDebugXCFramework
open examples/basic/iosApp/KOEKDemo.xcodeproj
```

Choose the **KOEKDemo** scheme and an arm64 iPhone simulator, then Run. The app
shows five green checks when the Swift/Kotlin calls succeed. Use Product → Test
to rerun the XCTest cases. The scheme is intentionally Debug-only and consumes
the generated Debug XCFramework; it is not a release-distribution template.
For a physical device, select your own signing team. Device execution has not
been validated by the simulator checks.

The XCFramework and individual simulator framework are written to:

```text
examples/basic/shared/build/XCFrameworks/debug/DemoKit.xcframework
examples/basic/shared/build/bin/iosSimulatorArm64/debugFramework/DemoKit.framework
```

For framework-only builds, use `:shared:linkDebugFrameworkIosArm64` or
`:shared:linkDebugFrameworkIosSimulatorArm64`. The Xcode project is checked in;
XcodeGen is needed only if a maintainer changes `examples/basic/iosApp/project.yml`
and regenerates it with `xcodegen generate --spec examples/basic/iosApp/project.yml`.

Verified on 2026-10-21 with Kotlin 2.2.21, Gradle 9.3.1, JDK 21, Xcode 26.3,
and the iOS 26.2 simulator SDK: Debug arm64 device/simulator framework builds,
header/negative-compilation checks, five Swift runtime tests, and simulator app
launch. Release, physical-device execution, other Kotlin versions, and a full
compiler-plugin regression matrix are outside this validation.

## Add KOEK to an existing project

Clone this repository next to your consumer project. The snippets below assume
that `../KotlinObjcExportKit` points to this checkout.

In the consumer's `settings.gradle.kts`, add the plugin build to
`pluginManagement`, and substitute the compiler and annotation artifacts:

```kotlin
pluginManagement {
    includeBuild("../KotlinObjcExportKit")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

includeBuild("../KotlinObjcExportKit") {
    dependencySubstitution {
        substitute(module("io.github.sugite.koek:compiler-plugin"))
            .using(project(":compiler-plugin"))
        substitute(module("io.github.sugite.koek:annotations"))
            .using(project(":annotations"))
    }
}
```

Plugin resolution and ordinary dependency substitution serve different
purposes, so both registrations are intentional. Merge these blocks with your
existing settings rather than creating duplicate settings blocks.

In the module that produces the framework:

```kotlin
plugins {
    kotlin("multiplatform") version "2.2.21"
    id("io.github.sugite.koek")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DemoKit"
        }
    }
    sourceSets.commonMain.dependencies {
        implementation("io.github.sugite.koek:annotations:0.1.0")
    }
}
```

Every module whose source uses `@ObjCExport` needs a direct annotation dependency.
If your framework exports another module, keep its existing `api(project(...))`
and `export(project(...))` configuration. KOEK scans the framework module and its
explicitly exported modules; an annotation in an arbitrary transitive dependency
does not automatically export that dependency.

## Export rules

| Declaration | How to select it |
| --- | --- |
| Class or interface | Add `@ObjCExport(group = "Models")`; interfaces become Objective-C protocols |
| Constructor | Annotate the constructor separately |
| Method | Annotate the exact declaration, including the desired overload |
| Read-only property | Annotate the property; `READ` is the default, even for Kotlin `var` |
| Writable property | Use `access = ObjCExportAccess.READWRITE`; a valid exportable setter is required |
| Constructor parameter property | Use `@property:ObjCExport` on the `val` or `var` |
| Nested type | Annotate it separately; annotating the enclosing class does not select it |
| Top-level declaration | Supply a group on that declaration |

Members can inherit the enclosing type's group. A type and its selected members
must use the same group. Group names match `[A-Za-z][A-Za-z0-9_]*`; names that differ
only by case are rejected. `Base` contains runtime support and forward
declarations and is also suitable for explicitly selected support types.

`@HiddenFromObjC`, Kotlin visibility, and the official mapper's restrictions still
apply. The allowlist cannot override them. Private/internal/local annotated
declarations, including declarations inside such types, fail with diagnostics.

Data class `copy` and `componentN` functions are not implicitly exported. This does
not remove Native's runtime support or NSObject mappings such as equality, hash,
and description. Existing selected inherited contracts retain their required
dispatch bridges; new members of a subtype are not automatically selected.

### Objects, companions, and enums

Compiler-generated entry points are selected with `synthetic`:

```kotlin
@ObjCExport(group = "Services", synthetic = ["shared"])
object Settings {
    @ObjCExport
    val version: String = "1.0"
}

@ObjCExport(group = "Models", synthetic = ["companion"])
class Item {
    @ObjCExport(group = "Models", synthetic = ["shared"])
    companion object {
        @ObjCExport
        const val DEFAULT_LIMIT = 10
    }
}

@ObjCExport(group = "Models", synthetic = ["enum:READY", "enum:DONE"])
enum class State { READY, DONE, INTERNAL }
```

Supported roles are `shared`, `instance`, `companion`, `enum:ENTRY_NAME`, `values`,
`entries`, and `asError` for `kotlin.Throwable`, where applicable. Unknown roles
fail. The parent `companion` accessor and the companion object's own `shared`
accessor are independent selections. New enum entries are not exported
automatically. Enum mappings may require explicit standard-library support
types; see the example policy below.

### External types and policy files

For types you cannot annotate, create `objc-export.api` beside the framework
module's build file. For example, enum mappings can require:

```text
class Base kotlin.Enum
class Base kotlin.Array
protocol Base kotlin.Comparable
```

Only include support types actually required by your selected API. Other rules
can select external members precisely:

```text
class Models example.ExternalModel
function Models example.ExternalModel.<init>(kotlin.String)
property Models example.ExternalModel.name read
function Models example.ExternalModel.update(kotlin.String)
synthetic Models example.ExternalModel companion
```

These are independent syntax examples, not a complete policy for every library.
Properties require `read` or `readwrite`. A function rule without a parameter
list must resolve to exactly one declaration; adding an overload makes it fail
rather than silently exporting both. Full signatures include extension
receivers and fully qualified Kotlin parameter types. Wildcards are unsupported.

Annotation and file rules are merged. Identical rules may coexist; conflicting
groups or access modes fail. The file may be absent when annotations are
sufficient. An empty effective API is rejected.

To use a different policy location:

```kotlin
objcExport {
    policyFile.set(layout.projectDirectory.file("config/ios-api.txt"))
}
```

## How header grouping works

KOEK selects declarations in the compiler's structured export model. It does
**not** generate a full public header and then remove declarations with regular
expressions.

The compiler plugin merges the rules, validates references, delegates mapping and
naming to the official exporter, and filters synthetic declarations and their
bridge specifications consistently. Required signature types and parent types
must be explicitly allowed. Base runtime support and unavailable initializers
remain where required by Native.

Headers are rendered by group. Inheritance and categories create header imports;
ordinary object references use forward declarations. An extension category uses
its target class's group. Top-level declarations in the same Kotlin source file
must share a group because they use one file wrapper class. Cyclic header
dependencies fail rather than producing an arbitrary split.

The compiler writes a versioned `headers.zip`. A Gradle action on the **same link
task** writes headers, the umbrella, modulemap, and ownership manifest, then
removes obsolete owned headers. It does not remove unrelated headers. Direct
framework links and packaging tasks that depend on them follow the same path.

The modulemap writer accepts a narrow known Native format, preserves `use`
dependencies, and rejects unknown directives. Files use atomic replacement
individually; this is not a transaction for the entire framework directory. A
failed link or finalization means the framework must not be shipped.

## Reports and diagnostics

Each framework binary has its own directory:

```text
build/objc-export/<target>/<binary>/
├── api.txt       Selected rules and rendered API surface
└── headers.zip   Rendered headers for the Gradle output action
```

Candidate signatures are opt-in:

```bash
./gradlew -p examples/basic :shared:dumpObjCExportSignatures \
    -PobjcExportDumpSignatures=true
```

The convenience task is registered for a target named `iosSimulatorArm64` with a
`debugFramework` binary. The same property can be used on a direct framework link
task. It enables `signatures.txt` as an additional link output. Turning it off
invalidates the link inputs and removes an old dump on the next link. Without
the flag, the dump task explains the required option without triggering a link.

Candidate signatures do not guarantee that the declaration can be exported.
Diagnostics include a source path and line when PSI is available, a source
filename when retained in KLIB metadata, or a fully qualified signature otherwise.
Reports can contain your project's API names and diagnostic paths; review them
before attaching them to public issues. Their existence alone does not prove a
successful framework build.

## Limitations and maintenance

- Compiler internals are version-specific. Kotlin upgrades need an adapter review
  and fresh compilation, linking, Swift/Objective-C, and runtime checks.
- Name mangling is handled by Native. Moving from a broader export surface can
  change collision resolution; compare generated names and update native callers.
- Unexported means absent from the selected public API. It does **not** guarantee
  removal of implementation code or every Mach-O symbol, or a smaller binary.
- Interactions with other export-transforming plugins, including SKIE, are not
  validated. Do not assume they can be combined.
- Framework build caching is subject to Kotlin Gradle plugin restrictions. KOEK
  does not override Native's cache policy. Configuration-cache compatibility is
  not claimed for this standalone checkout.
- The example has automated runtime and export-surface checks, but there is no
  general API-baseline gate or full compiler-plugin regression matrix.
  Deterministic grouping cannot decide whether a business-level group is sensible.

## Repository layout

```text
annotations/       Public @ObjCExport annotation and access modes
compiler-plugin/   Kotlin 2.2.21 adapter, export plan, bridge filtering, rendering
gradle-plugin/     Link task integration and framework file output
shared/            Pure JVM header-bundle protocol and atomic file utilities
examples/basic/    KMP consumer, Swift iOS demo, XCTest cases, verification script
```

The shared sources are compiled into both plugin JARs; they are not another
runtime artifact. No Native compiler AST crosses into the Gradle output layer.

## Contributing

Keep changes focused. Include a small generic reproduction and the Kotlin,
Gradle, JDK, Xcode, and target versions when reporting an issue. Remove credentials,
private endpoints, local usernames, and proprietary API names from logs and
sample code. Contributions should be made under the [MIT License](LICENSE).

Before treating a change as release-ready, verify positive and negative export
cases, callback and inherited dispatch behavior, individual header imports,
stale-group cleanup, repeated-output consistency, and the intended device and
simulator build variants. This is a release checklist, not a claim that this
checkout has passed the entire matrix. The basic Debug example's verified scope
is described above.
