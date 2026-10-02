package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.clients.Scope;

/** How API key scopes appear to Spring Security: {@code read} becomes the authority {@code SCOPE_read}. */
final class Authorities {

    private Authorities() {}

    static String of(Scope scope) {
        return "SCOPE_" + scope.value();
    }
}
