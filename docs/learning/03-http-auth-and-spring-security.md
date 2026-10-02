# Primer: HTTP authentication and Spring Security

M4a learning notes: how a request to this API gets authenticated and authorized, and the security ideas behind each step.

---

## 1. Authentication vs. authorization

- **Authentication:** *who is calling?* Here, the API key identifies a client. Failure means **401 Unauthorized** (the name is historical; it really means "unauthenticated").
- **Authorization:** *may they do this?* Here, the key's scopes decide. Failure means **403 Forbidden**.

A request with no key, or a bad one, gets 401. A request with a valid read-only key trying to open an account gets 403.

---

## 2. Bearer tokens

`Authorization: Bearer <token>` (RFC 6750) means "whoever holds this token is allowed in." There's no password exchange and no session: the token *is* the credential.

That's why API keys:
- are never logged
- are stored only as a hash
- are hidden in `toString()`
- can be revoked at any time

Anyone who copies one becomes that client.

---

## 3. The Spring Security filter chain

Before a request reaches a controller, it passes through a chain of servlet **filters**. Each one can let it through, change it, or answer it straight away.

```
request → … → ApiKeyAuthenticationFilter → … → AuthorizationFilter → controller
                     │                              │
                     │ valid key: sets "who"        │ checks the rule for this URL
                     │ bad key: 401 right here      │ no "who": 401   wrong scope: 403
```

- **The `SecurityContext`** holds "who is calling" for the current request. The filter puts an `Authentication` there, whose *principal* is the `AuthenticatedClient` and whose *authorities* are `SCOPE_read`, and so on.
- **`authorizeHttpRequests`** lists rules in order. The first rule that matches the request wins. The last rule, `anyRequest().denyAll()`, makes everything without an explicit rule unreachable. That's **deny by default**.
- **Controllers receive the caller** through `@AuthenticationPrincipal AuthenticatedClient client`, and never from the request body.

Two Spring Boot gotchas this project hit:
- **A `Filter` that is also a Spring bean gets registered twice:** once by Spring Boot on the servlet container, once in the security chain. So `ApiKeyAuthenticationFilter` is created by hand inside `SecurityConfiguration`.
- **In command-line mode there's no web server,** so the security configuration must be `@ConditionalOnWebApplication`.

---

## 4. Why CSRF protection is off

Cross-site request forgery tricks a logged-in user's *browser* into sending a request, which works because browsers attach cookies automatically. This API never uses cookies: the caller must put the key in a header itself, and another website can't make a browser do that. With no ambient credential, there's nothing to forge. Turning CSRF off is correct here, but *only* because of that.

---

## 5. Timing attacks and constant-time comparison

A normal comparison (`Arrays.equals`, `String.equals`) stops at the first differing byte. In principle, measuring how long rejections take could tell an attacker how many leading bytes they got right, and let them build a match byte by byte.

`MessageDigest.isEqual` always compares every byte, so the time taken reveals nothing. The verifier also compares against a dummy hash when the key id doesn't exist, so "unknown key" and "wrong secret" do the same work.

---

## 6. Tenant isolation and IDOR

**IDOR** (insecure direct object reference) is the classic API bug: change `/accounts/123` to `/accounts/124` and see someone else's data. This project prevents it by putting the owner into the query itself (`WHERE id = :id AND client_id = :clientId`), so there's no separate check to forget.

The response for an account you don't own is the **same 404** as for one that doesn't exist. A 403 would confirm that the account exists.

---

## Further reading
- RFC 6750 (Bearer tokens): https://www.rfc-editor.org/rfc/rfc6750
- RFC 9457 (Problem Details): https://www.rfc-editor.org/rfc/rfc9457
- Spring Security architecture: https://docs.spring.io/spring-security/reference/servlet/architecture.html
- OWASP API Security Top 10 (API1: broken object-level authorization): https://owasp.org/API-Security/
