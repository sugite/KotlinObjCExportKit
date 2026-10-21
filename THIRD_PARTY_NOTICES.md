# Third-party notices

The KOEK plugin source is licensed under the [MIT License](LICENSE).

The Gradle Wrapper scripts and JAR are distributed unmodified from
[Gradle 9.3.1](https://github.com/gradle/gradle/tree/v9.3.1) under the
[Apache License 2.0](licenses/Apache-2.0.txt). Upstream copyright and license
notices in the wrapper scripts are preserved.

KOEK integrates with the following Kotlin components from
[JetBrains/kotlin, version 2.2.21](https://github.com/JetBrains/kotlin/tree/v2.2.21),
licensed under the [Apache License 2.0](licenses/Apache-2.0.txt):

- `org.jetbrains.kotlin:kotlin-native-compiler-embeddable:2.2.21`: a compile-only
  dependency of `compiler-plugin`, providing Kotlin/Native compiler internals,
  including descriptor APIs and Objective-C export phases.
- `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21`: a compile-only dependency of
  `gradle-plugin`, providing Gradle integration and compiler subplugin APIs.

These dependencies are resolved from public repositories. Their implementation
JARs are not shaded into KOEK's plugin JARs. Using internal Kotlin APIs does not
change their upstream licenses or make them part of KOEK's MIT-licensed source.
Other resolved Kotlin and Gradle dependencies also retain their upstream licenses.
