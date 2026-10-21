# Changelog

This file records changes and the Kotlin version supported by each KOEK version.
KOEK uses internal Kotlin/Native compiler APIs; compatibility with other Kotlin
versions must be checked explicitly before changing the toolchain.

## [Unreleased]

### Compatibility and availability

| KOEK version in source | Kotlin version | Distribution |
| --- | --- | --- |
| `0.1.0` (unreleased) | `2.2.21` only | Local Gradle composite build |

There is no published Maven Central or Gradle Plugin Portal release. The version
in source identifies the current development build, not an available artifact.
The project uses JDK 21 and the Gradle 9.3.1 wrapper. The iOS example targets
`iosArm64` and `iosSimulatorArm64`; `iosX64` is not included.

### Added

- `@ObjCExport` to select exported types, protocols, functions, and properties,
  with explicit header groups and property access control.
- Kotlin/Native compiler integration for selecting Objective-C exports and Gradle
  integration for writing grouped headers into a single framework.
- A Kotlin example and a Swift iOS demo with automatic header/group assertions,
  negative Swift compilation checks, and Swift runtime tests.
- English and Simplified Chinese documentation, an MIT license, and third-party
  dependency notices.

### Changed

- Standardized the Gradle root project name and documented checkout directory
  spelling as `KotlinObjCExportKit`.

### Validation scope

The automated example checks cover Debug frameworks for iOS arm64 and simulator
arm64, and Swift runtime tests on an Apple Silicon iOS simulator. They do not
establish support for other Kotlin versions or every Kotlin declaration pattern.
