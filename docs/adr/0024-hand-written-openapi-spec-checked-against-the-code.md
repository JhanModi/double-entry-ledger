# ADR-0024: A hand-written OpenAPI spec, checked against the code

- **Status:** Accepted
- **Date:** 2026-10-04

## Context
The API had no machine-readable reference. Its contract was spread across the README, `docs/design.md` §8, and the controllers. M7 makes the repository public, and an API reference is one of the first things a reader looks for.

**OpenAPI** is the standard description of a REST API: its paths, parameters, request and response bodies, and errors. Tools render it as documentation, check it, or generate client code from it. It can be produced in two ways:
- **Code-first:** a library inspects the controllers at runtime and generates the spec.
- **Spec-first:** the spec is written by hand as the contract, and the code is held to it.

**Constraints:**
- The app isn't deployed until M16, so a reader on GitHub can only see files in the repository.
- Every new endpoint needs an explicit security rule (ADR-0017), and new dependencies need the owner's approval.
- Much of this API's contract is specific and easy to get subtly wrong in a generated spec. That includes integer-only amounts, a required `Idempotency-Key` with a fixed format, strict JSON, one Problem Details type per error, and `Retry-After` on 409 and 503.

## Options considered
- **springdoc-openapi (code-first, with Swagger UI).**
  - It can't miss an endpoint, and it serves a page where readers can try requests.
  - It's a runtime dependency with a sizable transitive tree.
  - Its spec only exists while the app runs, so nobody reading the repository would see it.
  - It needs two public endpoints (the spec and the UI), each with a `permitAll` rule. That's a security change.
  - Describing the rules above would still mean hand-written annotations on every controller.
- **A hand-written `docs/openapi.yaml`, plus a test that compares it with the code.**
  - No new Maven dependency: SnakeYAML is already on the classpath through Spring Boot.
  - Readable on GitHub without running anything, and no security change.
  - The test can only check what the code makes visible, so some details can drift (see Consequences).
- **Defer it to M16.** That leaves the public repository without an API reference.

**How to check the hand-written spec is valid OpenAPI:**
- **Redocly CLI in CI**, run from a Docker image pinned by digest, the way gitleaks runs. A real OpenAPI validator and linter.
- **`swagger-parser` as a test dependency.** It validates too, but it's a new Maven dependency with its own transitive tree.
- **Only the comparison test.** It can't see invalid OpenAPI, such as a misspelled keyword.

## Decision
- **The spec is `docs/openapi.yaml`, OpenAPI 3.1, written by hand.** It covers the `/v1` API: every endpoint, header, parameter, body, and problem type, with examples. Actuator's health check and Spring's `/error` stay out.
- **`OpenApiSpecIT` compares it with what Spring actually serves.** It reads the spec with SnakeYAML (its safe loader, which builds only maps, lists, and scalars), and it reads the code through Spring's request mappings and reflection over the JSON records. It fails on any difference in:
  - the endpoints (method and path, including path-variable names);
  - each endpoint's headers and parameters: names, whether they're required, types, and the limits from `@Pattern`, `@Min`, and `@Max`;
  - which JSON record each endpoint takes and returns;
  - each record's fields: names, JSON types and formats, required fields, enum values, `@MaxCharacters` limits, and nested records;
  - request schemas refusing unknown fields (`additionalProperties: false`, matching ADR-0021);
  - every record in `ApiJson` having a schema, and every schema being accounted for;
  - every `$ref` resolving.
- **Redocly CLI lints the spec in CI** (the `api-spec` job), with the rules in `redocly.yaml`:
  - Its `recommended-strict` ruleset, which turns every warning into an error.
  - One rule is switched off: the one that rejects a `localhost` server URL, because localhost is the true server until M16.
  - The image is pinned by tag and digest (`redocly/cli:2.57.0@sha256:…`), and it runs with `--network none` and telemetry switched off.
- **The spec isn't served by the app.** M16 can serve this same file with a Swagger UI if a public demo needs one. That would be its own decision, with its own security rule.

## Consequences
- **What the test can't see, and so can drift:** status codes, problem types, which errors an endpoint can return, headers on responses, examples, and descriptions. These are kept true by review against the API tests (`*ApiIT`) and `ApiExceptionHandler`. A change to error handling must update the spec in the same change.
- **A new endpoint or JSON record can't ship undocumented.** The test fails until the spec has it, much as deny-by-default stops an endpoint without a security rule.
- **Response fields are all marked required,** because Jackson writes every field, null or not; nullable ones are typed `[string, "null"]`. If the API ever omits null fields, the test's rule for required response fields must change with it.
- **The schema-to-record mapping is explicit in the test** (`RECORD_SCHEMAS`), so a schema can have a client-facing name (`Transfer`) while the record keeps its Java name (`TransferResponse`).
- **Bumping Redocly is manual,** like gitleaks: change the tag and the digest together in `ci.yml`.

## How to explain it
"The spec is the contract, so I wrote it by hand, where a reviewer can read it without running anything. A test then compares it with what Spring really serves: endpoints, parameters, fields, required fields, enums, and validation limits. If someone adds an endpoint or renames a field without updating the contract, the build fails. A linter in CI checks that it's valid OpenAPI. The part the test can't see is error handling, so that's kept true by review against the API tests."
