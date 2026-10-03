package io.github.jhanmodi.ledger.clients;

/**
 * The caller's key lacks a scope this operation needs. The security rules normally refuse such a request first (403);
 * services that move money check again, so an endpoint added without the right rule still can't move money.
 */
public final class ScopeRequiredException extends RuntimeException {

    private final Scope required;

    public ScopeRequiredException(Scope required) {
        super("this operation needs a key with the " + required.value() + " scope");
        this.required = required;
    }

    public Scope required() {
        return required;
    }
}
