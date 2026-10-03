package io.github.jhanmodi.ledger.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The same format the database enforces (transfers_idempotency_key_format; MoneyMovementSchemaIT uses these cases). */
class IdempotencyKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "order-123:attempt_2.v1", "0190a000-0000-7000-8000-000000000001"})
    void acceptsSafeCharacters(String value) {
        assertThat(new IdempotencyKey(value).value()).isEqualTo(value);
    }

    @Test
    void acceptsUpTo255Characters() {
        assertThat(new IdempotencyKey("k".repeat(255)).value()).hasSize(255);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "café", "line\nbreak", "tab\there", "semi;colon"})
    void rejectsAnythingElse(String value) {
        // A fixed message: the rejected input isn't repeated back, since it could be anything.
        assertThatThrownBy(() -> new IdempotencyKey(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an idempotency key must be 1 to 255 characters from A-Z a-z 0-9 _ . : -");
    }

    @Test
    void rejects256Characters() {
        assertThatThrownBy(() -> new IdempotencyKey("k".repeat(256))).isInstanceOf(IllegalArgumentException.class);
    }
}
