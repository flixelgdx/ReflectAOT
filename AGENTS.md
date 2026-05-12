# AGENTS.md

## Cursor Cloud specific instructions

This is a **Gradle multi-module JVM library** (not a running application). There are no services, databases, or Docker containers to start.

### Modules

| Module | Language | Purpose |
|---|---|---|
| `reflectaot-runtime` | Java 8 | User-facing `Reflect` API |
| `reflectaot-codegen` | Kotlin (JVM 17) | ASM-based bytecode scanner & emitter |
| `reflectaot-gradle-plugin` | Kotlin (JVM 17) | Gradle plugin wiring codegen into builds |

### Key commands

All commands use the Gradle wrapper (`./gradlew`). See `README.md` for full details.

- **Build + test all modules:** `./gradlew build`
- **Tests only:** `./gradlew test`
- **Publish to Maven Local:** `./gradlew publishToMavenLocal`
- **Clean:** `./gradlew clean`

### Notes

- JDK 21 is pre-installed on the VM. Gradle 9.0.0 is managed by the wrapper and auto-downloads on first use.
- The `foojay-resolver-convention` plugin in `settings.gradle` can auto-provision JDK toolchains (the Kotlin modules target JDK 17 via `jvmToolchain(17)`).
- The runtime module targets Java 8 source/target, which produces "obsolete options" warnings on JDK 21 — these are expected and harmless.
- Kotlin compiler warnings about `java.lang.Object` / `java.util.List` usage are expected in codegen module — these are intentional for ASM interop.
- There is no lint command separate from `build`; Gradle's compilation and `validatePlugins` task serve as the lint check.
- First build downloads Gradle distribution + all dependencies from Maven Central; subsequent builds use the cache (`~/.gradle`).
