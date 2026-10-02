package io.github.jhanmodi.ledger.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssuedApiKeyTest {

    private static final String PLAINTEXT = "dbl_0123456789abcdef_" + "S".repeat(43);

    @Test
    void toStringNeverContainsTheKey() {
        IssuedApiKey key =
                new IssuedApiKey(new ClientId(UUID.randomUUID()), "0123456789abcdef", Set.of(Scope.READ), PLAINTEXT);

        assertThat(key.toString())
                .doesNotContain(PLAINTEXT)
                .doesNotContain("S".repeat(43))
                .contains("<hidden>");
    }

    @Test
    void keepsItsOwnCopyOfTheScopes() {
        Set<Scope> scopes = EnumSet.of(Scope.READ);
        IssuedApiKey key = new IssuedApiKey(new ClientId(UUID.randomUUID()), "0123456789abcdef", scopes, PLAINTEXT);

        scopes.add(Scope.ADMIN);

        assertThat(key.scopes()).containsExactly(Scope.READ);
        assertThatThrownBy(() -> key.scopes().add(Scope.WRITE)).isInstanceOf(UnsupportedOperationException.class);
    }
}
