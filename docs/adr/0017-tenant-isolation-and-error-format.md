# ADR-0017: Tenant isolation and the error format

- **Status:** Accepted. The point that unknown JSON fields are ignored is superseded by [ADR-0021](0021-request-ids-and-strict-request-parsing.md): they're now rejected with 400.
- **Date:** 2026-10-02

## Context
Each API client is a separate business (a tenant). A client must never see or affect another client's accounts, and it shouldn't even be able to learn that they exist. The API also needs one predictable error format.

## Options considered
- **Isolation**
  - *Check ownership in the controller after loading the account.* Easy to forget in a new endpoint.
  - *Put the ownership condition in the SQL itself.* An account the caller doesn't own is simply not found.
  - *Postgres row-level security.* A strong second layer, but more moving parts (ADR-0015 mentions it as a possible later addition).
- **Answer for someone else's account:** 403 Forbidden (honest, but it confirms the account exists) or 404 Not Found (identical to an account that doesn't exist).
- **Error format:** ad-hoc JSON, or RFC 9457 Problem Details (`type`, `title`, `status`, `detail`, `instance`).

## Decision
- **Ownership is part of the query.**
  - Customer accounts have a `client_id` (V4). A CHECK requires it for customer accounts and forbids it for system accounts, and the identity trigger stops an account from ever changing owner.
  - Client-facing reads go through `LedgerQueries.accountOwnedBy(client, id)`, which runs `WHERE id = :id AND client_id = :clientId`.
- **One answer for everything the caller doesn't own:** whether the account is another client's, is a system account, or doesn't exist, the response is the **same 404 with the same body**. Only `instance` (the request path) differs.
- **The owner always comes from the API key, never from the request.** Unknown JSON fields such as `clientId` are ignored.
- **Every error is a Problem Details document** (`application/problem+json`):
  - 400 for malformed input (handled by Spring MVC)
  - 401 and 403 from the security layer, as fixed text
  - 404 `/problems/account-not-found`
  - a generic 500 that's logged on the server and reveals nothing to the client

  Stack traces never reach a response.
- **`type` values are relative URIs** (`/problems/<name>`). That keeps them stable and short; they could later point at published documentation.

## Consequences
- **IDOR protection:** an endpoint that looks accounts up through `accountOwnedBy` can't leak another tenant's data by accident. Tests prove that client B gets an identical 404 for client A's account and for a random id.
- **Future endpoints must use the owner-scoped lookup.** That rule is in CLAUDE.md.
- **Clients can rely on one error shape everywhere,** including authentication failures.

## How to explain it
"Ownership lives in the SQL: the query asks for 'this account *and* this client', so another tenant's account is simply not found. It gets the same 404 and body as a random id, because a 403 would confirm the account exists. All errors use RFC 9457 Problem Details, and nothing internal like a stack trace ever reaches the client."
