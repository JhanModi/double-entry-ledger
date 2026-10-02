package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.audit.RequestId;
import io.github.jhanmodi.ledger.clients.AuthenticatedClient;
import io.github.jhanmodi.ledger.clients.Caller;
import jakarta.servlet.http.HttpServletRequest;

/** Builds the {@link Caller} that services need to do and audit a client's request. */
final class Callers {

    private Callers() {}

    /**
     * The verified client, with this request's id and the address it came from. The address is the TCP peer's: behind
     * a proxy it would be the proxy's, and trusting {@code X-Forwarded-For} is a deployment decision (ADR-0020).
     */
    static Caller of(AuthenticatedClient client, HttpServletRequest request) {
        if (!(request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof RequestId requestId)) {
            // Can't happen while RequestIdFilter is registered. If it does, fail rather than audit without an id.
            throw new IllegalStateException("this request has no request id");
        }
        return new Caller(client, requestId, request.getRemoteAddr());
    }
}
