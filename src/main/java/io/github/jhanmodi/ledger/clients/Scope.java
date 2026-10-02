package io.github.jhanmodi.ledger.clients;

import java.util.Locale;

/** A permission on an API key. Stored in lowercase ({@code read}), matching the {@code api_keys.scopes} check. */
public enum Scope {
    /** Read accounts, balances, and history. */
    READ,
    /** Open accounts and move money. */
    WRITE,
    /** Platform operations, such as funding (M4b). */
    ADMIN;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Scope fromValue(String value) {
        for (Scope scope : values()) {
            if (scope.value().equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("unknown scope: " + value + " (expected read, write, or admin)");
    }
}
