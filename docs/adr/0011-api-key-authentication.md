# ADR-0011: API-key authentication with scopes

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
The API's callers are programs belonging to client businesses, not people in browsers. Each client must only ever see and move its own money.

## Options considered
- **API keys per client.** Simple and Stripe-like, but they are long-lived bearer secrets.
- **OAuth2 client credentials** with an authorization server (Spring Authorization Server). The standard for B2B, with short-lived tokens, but much more setup.
- **Mutual TLS.** Strong, but complex to run locally.

## Decision
- **Key format:** a public **prefix** (lookup id) plus a **secret** of at least 32 random bytes from `SecureRandom`. The key is shown once, at creation.
- **Storage:** the SHA-256 hash of the secret, looked up by prefix and compared in constant time (`MessageDigest.isEqual`).
  - **Why SHA-256 and not argon2id:** API keys are high-entropy random values, so brute-forcing the hash is infeasible, and a fast hash keeps per-request authentication cheap. argon2id exists to protect low-entropy human passwords.
- **Rotation and revocation:** a client can have several active keys, so rotation causes no downtime. Revocation sets `revoked_at`.
- **Scopes:** `read`, `write`, `admin`. `admin` is required for funding, reversals, reconciliation, and resolving NEEDS_REVIEW payments.
- **Enforcement:** a Spring Security filter authenticates every request. Every resource access checks ownership (`client_id`), and anything else is denied by default.
- **System accounts** are never addressable through the public API.
- Keys are never logged. Only the prefix may appear in logs.

## Consequences
- Simple and familiar to API consumers.
- A leaked key stays valid until revoked. Mitigations: rotation, revocation, never logging keys, and per-client rate limits.
- OAuth2 client credentials is a possible later upgrade.

## How to explain it
"Clients authenticate with API keys. Only a hash is stored, the key is looked up by a public prefix, and the secret is compared in constant time. SHA-256 is enough because the keys are random, not human-chosen. Every query is scoped to the caller's client id, and the test suite proves one client can't touch another's accounts."
