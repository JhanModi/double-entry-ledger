package io.github.jhanmodi.ledger.idempotency;

import static io.github.jhanmodi.ledger.idempotency.IdempotentOperation.FUNDING;
import static io.github.jhanmodi.ledger.idempotency.IdempotentOperation.TRANSFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The fingerprint's encoding (ADR-0023, D3). It's a contract with every stored claim, so the exact text and hash are
 * pinned here. The expected hash was computed outside Java, with {@code printf '%s' '<text>' | sha256sum}, so this test
 * doesn't just compare the code with itself.
 */
class RequestFingerprintTest {

    @Test
    void theEncodingIsPinned() {
        RequestFingerprint.Builder builder =
                RequestFingerprint.of(TRANSFER).field("currency", "USD").field("description", null);

        assertThat(builder.canonicalText())
                .isEqualTo("2:v1" + "8:TRANSFER" + "8:currency" + "3:USD" + "11:description" + "-");
        assertThat(builder.build()).hasToString("6561afc17f9226d69fb87ce113dedf9fd14b589b382ab9412d2feb8524b6207d");
    }

    @Test
    void eachLengthCountsUtf8BytesNotCharacters() {
        // "café" is 4 characters but 5 bytes; the emoji is 1 character, 2 Java chars, and 4 bytes.
        assertThat(RequestFingerprint.of(TRANSFER).field("x", "café").canonicalText())
                .endsWith("1:x5:café");
        assertThat(RequestFingerprint.of(TRANSFER).field("x", "😀").canonicalText())
                .endsWith("1:x4:😀");
    }

    @Test
    void theLengthsKeepNeighbouringValuesApart() {
        // Joined without lengths, both would read "abc".
        assertDiffer(
                RequestFingerprint.of(TRANSFER).field("x", "ab").field("y", "c"),
                RequestFingerprint.of(TRANSFER).field("x", "a").field("y", "bc"));
        // The same, across a field name and a value.
        assertDiffer(
                RequestFingerprint.of(TRANSFER).field("ab", "c"),
                RequestFingerprint.of(TRANSFER).field("a", "bc"));
    }

    @Test
    void aMissingValueDiffersFromAnEmptyOneAndFromADash() {
        RequestFingerprint.Builder missing = RequestFingerprint.of(TRANSFER).field("description", null);
        RequestFingerprint.Builder empty = RequestFingerprint.of(TRANSFER).field("description", "");
        RequestFingerprint.Builder dash = RequestFingerprint.of(TRANSFER).field("description", "-");

        assertThat(missing.canonicalText()).endsWith("11:description-");
        assertThat(empty.canonicalText()).endsWith("11:description0:");
        assertThat(dash.canonicalText()).endsWith("11:description1:-");
        assertDiffer(missing, empty);
        assertDiffer(missing, dash);
        assertDiffer(empty, dash);
    }

    @Test
    void theOperationFieldNamesAndOrderAllCount() {
        assertDiffer(
                RequestFingerprint.of(TRANSFER).field("a", "1"),
                RequestFingerprint.of(FUNDING).field("a", "1"));
        assertDiffer(
                RequestFingerprint.of(TRANSFER).field("a", "1"),
                RequestFingerprint.of(TRANSFER).field("b", "1"));
        assertDiffer(
                RequestFingerprint.of(TRANSFER).field("a", "1").field("b", "2"),
                RequestFingerprint.of(TRANSFER).field("b", "2").field("a", "1"));
    }

    @Test
    void theSameRequestAlwaysHasTheSameFingerprint() {
        RequestFingerprint first =
                RequestFingerprint.of(FUNDING).field("a", "1").field("b", null).build();
        RequestFingerprint second =
                RequestFingerprint.of(FUNDING).field("a", "1").field("b", null).build();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).isNotSameAs(second);
    }

    @Test
    void itIs32BytesAndCantBeChangedFromOutside() {
        RequestFingerprint fingerprint = RequestFingerprint.of(TRANSFER).build();
        byte[] bytes = fingerprint.bytes();
        String before = fingerprint.toString();

        bytes[0]++;

        assertThat(bytes).hasSize(32);
        assertThat(fingerprint.toString()).isEqualTo(before).hasSize(64);
        assertThat(RequestFingerprint.fromBytes(fingerprint.bytes())).isEqualTo(fingerprint);
    }

    @Test
    void aStoredHashMustBe32Bytes() {
        assertThatThrownBy(() -> RequestFingerprint.fromBytes(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestFingerprint.fromBytes(new byte[33]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertDiffer(RequestFingerprint.Builder one, RequestFingerprint.Builder other) {
        assertThat(one.canonicalText()).isNotEqualTo(other.canonicalText());
        assertThat(one.build()).isNotEqualTo(other.build());
    }
}
