# ADR-0012: Maven as the build tool

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
A Java project needs a build tool for dependencies, compilation, tests, and packaging.

## Options considered
- **Maven.** Declarative XML with strong conventions. Dominant at banks and large Boston employers, and well documented for beginners.
- **Gradle.** Faster incremental builds and flexible scripting (Kotlin DSL), but a steeper learning curve, and its flexibility makes builds less uniform.

## Decision
Use Maven with the Maven Wrapper (`mvnw`). Everyone (including CI) builds with the same Maven version, without installing it.

## Consequences
- Verbose XML.
- Slower builds than Gradle for large projects, which doesn't matter at this size.
- The standard lifecycle (`mvn verify`) is the single command for "does everything pass?".

## How to explain it
"I picked Maven because it's the norm at the companies I'm targeting and its conventions keep the build boring. The wrapper pins the version for everyone, including CI."
