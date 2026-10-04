# Primer: API contracts, diagrams, and publishing a repository

M7 learning notes: what an OpenAPI spec is and how this one is kept true to the code, diagrams written as text, a demo that checks itself, and what changes when a repository becomes public.

---

## 1. The API contract

An API's **contract** is everything a client may rely on: the paths, which headers are required, the exact shape of every body, and what each error means. A business that integrates with a payments API writes code against that contract, and often generates its client code from it. If the contract is vague or wrong, the client finds out in production.

Before M7 the contract was spread over the README, `docs/design.md` §8, and the controllers. Now it's one file: `docs/openapi.yaml`.

## 2. OpenAPI in one page

An OpenAPI document (version 3.1 here) is YAML with a few main parts:
- **`paths`:** each path and HTTP method is an *operation*, with its `parameters` (path, query, header), `requestBody`, and `responses` keyed by status code.
- **`components`:** reusable pieces, referenced with `$ref: "#/components/schemas/Money"`. Schemas, parameters (like `Idempotency-Key`), headers, and whole responses (like `InvalidRequest`) are written once and used everywhere.
- **Schemas are JSON Schema:** `type: integer`, `format: int64`, `minimum: 1`, `enum: [USD, EUR, JPY, KWD]`, `required: [...]`, and `additionalProperties: false`, which says "no other fields allowed". A field that can be null is `type: [string, "null"]`.
- **`securitySchemes`:** how clients authenticate. Here it's an HTTP bearer token.

The spec also says the things that are easy to get wrong in an API like this one: amounts are integers, not decimals; `Idempotency-Key` is required and has a fixed format; unknown fields are refused; and 409 has two problem types that mean different things.

## 3. Code-first or spec-first (ADR-0024)

- **Code-first:** a library such as springdoc reads the controllers when the app starts and generates the spec. It can't forget an endpoint. But the spec only exists while the app runs, it adds a dependency and two public endpoints, and the subtle rules still need hand-written annotations.
- **Spec-first:** the spec is written by hand as the contract, and the code is held to it. It's readable on GitHub without running anything. Its weakness is drift: nothing stops someone renaming a field in the code and forgetting the spec.

This project writes the spec by hand and fixes the weakness with a test.

## 4. Keeping a hand-written spec honest: `OpenApiSpecIT`

The test reads both sides and compares them.
- **The spec:** parsed with SnakeYAML's *safe* loader, which builds only maps, lists, and scalars. An unsafe YAML loader can be tricked into creating arbitrary Java objects, a classic deserialization vulnerability.
- **The code:** Spring's `RequestMappingHandlerMapping` knows every endpoint the app serves, and the controller method behind each one. From the method's parameters come the headers and query parameters, with their annotations (`@Pattern`, `@Min`, `@Max`). From its `@RequestBody` parameter and its return type come the JSON records. Java's reflection over records (`getRecordComponents()`) then lists each record's fields and their types, and the fields' annotations (`@NotNull`, `@MaxCharacters`).

It fails if any of these disagree: endpoints, parameter names and rules, which record each endpoint takes and returns, field names, types, required fields, enum values, length limits, and whether unknown fields are refused. It also checks that every `$ref` resolves.

**What it can't see:** status codes, problem types, and descriptions. Nothing in the code lists "the errors this endpoint can return" in a form a test can read. Those are kept true by review against the API tests.

**Testing the test:** 15 mismatches were planted, 10 in the spec and 5 in the code, such as a renamed field, a missing endpoint, a wrong length limit, a currency missing from an enum, and a new endpoint left undocumented. Each made the test fail, in the check written for it.

## 5. Linting in CI, and pinning by digest

Redocly CLI checks that the file is valid OpenAPI and follows good practice, such as every operation having an `operationId` and at least one 4xx response. `redocly.yaml` turns its warnings into errors, so CI fails on them too.

The CI job runs Redocly from a Docker image pinned by **digest**: `redocly/cli:2.57.0@sha256:e320…`. A tag like `2.57.0` is just a label, and whoever controls the image can move it to different contents. A digest is a hash of the image's contents, so it can only ever mean those exact bytes. gitleaks is pinned the same way. The job also runs with `--network none`. The spec has no remote references, so nothing needs the network, and that guarantees the CLI can't send anything out, such as its usage telemetry (which is also switched off).

## 6. Diagrams as text: Mermaid

Mermaid diagrams are written as text inside Markdown, and GitHub draws them:
- **`flowchart`** for the pieces and how requests move between them;
- **`sequenceDiagram`** for one request over time, with `alt` blocks for its branches;
- **`erDiagram`** for tables and relationships. `||--o{` means "exactly one to zero or more".

Being text, a diagram is reviewed in the same diff as the code it describes, so it's more likely to be kept up to date than an image.

## 7. A demo that checks itself

`scripts/demo.sh` walks through the API, and every step asserts the response it expects. A run that finishes is evidence, not just a show. A few techniques worth knowing:
- **Real concurrency from a shell:** `curl --parallel --parallel-immediate` sends many requests from one process at the same moment. Starting 30 separate `curl` processes one after another would spread them out over time.
- **Keeping secrets off the command line:** on a shared machine, other users can see every process's command line (`ps`). The script writes the `Authorization` header to a temporary file and passes it with `--header @file`, so the key never appears there. It also never prints the key, and deletes the file on exit (`trap … EXIT`).
- **Re-runnable:** each run creates new clients and keys with unique names, because the ledger is append-only and can't be "reset" row by row.

## 8. Making a repository public (ADR-0025)

- **The history is published too.** Every commit becomes readable, and anyone can clone it. Deleting a file now doesn't remove it from older commits.
- **A leaked secret must be rotated,** not just deleted: it stays in the history and in every clone. That's why gitleaks scans *every* commit in CI, and why GitHub's push protection (which rejects a push containing a known secret format) is worth turning on.
- **gitleaks can't see everything:** personal data, local file paths, or a real email address in commit metadata. Those were checked by hand.
- **A license is what lets others use the code.** Without one, "public" means readable, not reusable. MIT allows reuse if the notice is kept; Apache-2.0 adds an explicit patent grant.

---

## Further reading
- OpenAPI Specification 3.1: https://spec.openapis.org/oas/v3.1.0
- JSON Schema, getting started: https://json-schema.org/learn/getting-started-step-by-step
- Redocly CLI, built-in rules: https://redocly.com/docs/cli/rules/built-in-rules
- Docker, pinning images by digest: https://docs.docker.com/reference/cli/docker/image/pull/#pull-an-image-by-digest-immutable-identifier
- Mermaid documentation: https://mermaid.js.org/intro/
- curl, parallel transfers: https://everything.curl.dev/cmdline/urls/parallel.html
- GitHub, push protection: https://docs.github.com/en/code-security/secret-scanning/introduction/about-push-protection
- Choose a license: https://choosealicense.com/licenses/
