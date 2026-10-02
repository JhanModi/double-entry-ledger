package io.github.jhanmodi.ledger.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScopeTest {

    @Test
    void usesLowercaseValues() {
        assertThat(Scope.READ.value()).isEqualTo("read");
        assertThat(Scope.WRITE.value()).isEqualTo("write");
        assertThat(Scope.ADMIN.value()).isEqualTo("admin");
    }

    @Test
    void parsesItsOwnValuesAndNothingElse() {
        for (Scope scope : Scope.values()) {
            assertThat(Scope.fromValue(scope.value())).isEqualTo(scope);
        }
        assertThatThrownBy(() -> Scope.fromValue("READ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scope.fromValue("superuser")).isInstanceOf(IllegalArgumentException.class);
    }
}
