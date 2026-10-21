# Kotlin ObjC Export Kit (KOEK)

[简体中文](README.zh-CN.md)

**Choose the Kotlin APIs that iOS can use. Organize their Objective-C declarations into grouped headers. Keep one framework.**

KOEK adds an explicit allowlist to Kotlin/Native's Objective-C export pipeline.
Use `@ObjCExport` on the types and members you want to expose, and choose their
header group. Unselected declarations remain usable by Kotlin code.

This is an **experimental, source-only project** for **Kotlin 2.2.21**. It uses
internal compiler APIs and reflective phase interception. There is no published
Gradle Plugin Portal or Maven Central release. The standalone build and runtime
validation matrix has not been executed for this checkout; the commands below
are reproduction instructions, not claims of passing tests.

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
| Native build host | macOS with an installed, selected Xcode and the required Apple SDKs |
| Annotations | JVM 17, iOS x64, iOS arm64, and iOS simulator arm64 variants |
| Export backend | Kotlin/Native **Objective-C export**, not Swift export |
| Distribution | Local composite builds; no artifacts are published yet |
| License | MIT for KOEK; see [third-party notices](THIRD_PARTY_NOTICES.md) |

The annotation library has no platform-specific implementation. Applying the
Gradle plugin affects Kotlin/Native **Framework link tasks**, not Android or JVM
compilation tasks. Other Native targets require corresponding annotation
variants and separate validation; they are not enabled by this repository.

## Try the included example

The [basic example](examples/basic) is a separate consumer build. It contains a
DTO, a callback protocol, a service, and a deliberately partial enum export.

From the repository root, with JDK 21 selected:

```bash
./gradlew -p examples/basic :shared:linkDebugFrameworkIosSimulatorArm64
```

The framework is written to:

```text
examples/basic/shared/build/bin/iosSimulatorArm64/debugFramework/DemoKit.framework
```

For a device build, use `:shared:linkDebugFrameworkIosArm64`. The example does not
install or run an iOS application, and does not publish any artifacts.

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
- No automated API-baseline gate or runtime regression suite is included yet.
  Deterministic grouping cannot decide whether a business-level group is sensible.

## Repository layout

```text
annotations/       Public @ObjCExport annotation and access modes
compiler-plugin/   Kotlin 2.2.21 adapter, export plan, bridge filtering, rendering
gradle-plugin/     Link task integration and framework file output
shared/            Pure JVM header-bundle protocol and atomic file utilities
examples/basic/    Self-contained consumer using the local composite build
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
checkout has passed it.
