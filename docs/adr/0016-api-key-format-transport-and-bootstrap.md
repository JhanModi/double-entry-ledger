# ADR-0016: API key format, transport, and bootstrap

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
ADR-0011 chose API keys with scopes, stored only as a hash. Building them (M4a) needed concrete answers:
- What does a key look like?
- How is it sent?
- How is it checked safely?
- How is the very first key created, when nobody has a key yet?

## Options considered
- **Format:** a random opaque token; a JWT; or a structured `prefix_id_secret` token.
  - JWTs are self-contained, but they can't be revoked without extra machinery, and they put signing keys in play.
  - A structured token lets the server find the stored hash by a public id, without scanning every key.
- **Transport:** `Authorization: Bearer <key>` (RFC 6750), or a custom `X-API-Key` header.
- **Bootstrap:** an admin HTTP endpoint guarded by a bootstrap token, a key seeded by a migration, or a command-line mode of the app.

## Decision
- **Format:** `dbl_<key id>_<secret>`, always exactly 64 characters.
  - **`dbl_`:** a fixed prefix, so secret scanners (gitleaks, GitHub secret scanning) can recognise a leaked key.
  - **Key id:** 16 lowercase hex characters (64 random bits). Public; it's how the server finds the stored hash.
  - **Secret:** 43 base64url characters (256 random bits from `SecureRandom`). Only `SHA-256(secret)` is stored.
  - **Parsing uses fixed positions,** because `_` is also a base64url character and can appear inside the secret.
  - **The hash covers the secret's exact text,** so only the exact string that was issued can ever match.
- **Transport:** `Authorization: Bearer <key>`. The scheme name is matched case-insensitively (RFC 7235).
- **Verification** (`ApiKeyVerifier`):
  1. Parse the key.
  2. Look up the key id.
  3. Hash the presented secret.
  4. Compare it with `MessageDigest.isEqual`, which takes constant time. When the key id is unknown, compare against a dummy hash instead, so that case does the same work.
  5. Reject the key if it's revoked or its client is disabled.

  **Every failure gives the same 401 response**, so a caller never learns which check failed.
- **Authorization:** each scope becomes a Spring Security authority (`SCOPE_read`, `SCOPE_write`, `SCOPE_admin`). Every endpoint has an explicit rule, and anything without one is denied (`denyAll`), even with a valid key.
- **Bootstrap:** `clients create --name=<name> --scopes=<list>` runs the app without a web server, creates the client and a key, prints the key once, and exits. There's no network surface and no bootstrap secret. Whoever can run the app with database access can create clients.
- **No default user:** Spring Boot's default username/password user is excluded (`UserDetailsServiceAutoConfiguration`), so no generated password is ever printed.

## Consequences
- **A leaked key** can be revoked immediately (`api_keys.revoked_at`), and a whole client can be disabled.
- **Keys never appear in logs or the database.** `IssuedApiKey.toString()` hides the plaintext, and a test checks that no stored row contains a secret.
- **Constant-time comparison is verified by reading the code, not by a test,** because timing tests are too unreliable.
- **Rate limiting for repeated bad keys is not yet in place** (M4b). With 256-bit secrets, guessing is infeasible anyway, but rate limits still cut noise and cost.
- **Pitfall found and fixed:** the security configuration must only load in web mode. In command-line mode, Spring Security's web support doesn't exist. `ClientsCommandIT` now runs in a non-web context to catch this.

## How to explain it
"A key is `dbl_` plus a public id plus a 256-bit secret. The server looks the key up by its id, hashes the presented secret with SHA-256, and compares the hashes in constant time. Every kind of failure returns the same 401, so an attacker learns nothing from a rejection. The first key comes from a command-line mode rather than an endpoint, so there's no bootstrap secret to protect."
