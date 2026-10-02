package io.github.jhanmodi.ledger.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeyFormatTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String VALID_ID = "0123456789abcdef";
    private static final String VALID_SECRET = "A".repeat(ApiKeyFormat.SECRET_LENGTH);

    @Test
    void generatedKeysHaveTheDocumentedShape() {
        String key = ApiKeyFormat.generate(RANDOM).plaintext();

        assertThat(key).hasSize(64).matches("dbl_[0-9a-f]{16}_[A-Za-z0-9_-]{43}");
    }

    @Test
    void generatedKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            ApiKeyFormat.GeneratedKey key = ApiKeyFormat.generate(RANDOM);
            keys.add(key.plaintext());
            ids.add(key.keyId());
        }

        assertThat(keys).hasSize(1_000);
        assertThat(ids).hasSize(1_000);
    }

    @Test
    void parsesWhatItGenerates() {
        ApiKeyFormat.GeneratedKey key = ApiKeyFormat.generate(RANDOM);

        assertThat(ApiKeyFormat.parse(key.plaintext())).hasValueSatisfying(parsed -> {
            assertThat(parsed.keyId()).isEqualTo(key.keyId());
            assertThat(parsed.secret()).isEqualTo(key.secret());
        });
    }

    @Test
    void parsesByPositionSoASecretMayContainUnderscores() {
        // "_" is a base64url character. Splitting the key on "_" would cut this secret into pieces.
        String secret = "_".repeat(ApiKeyFormat.SECRET_LENGTH);

        assertThat(ApiKeyFormat.parse("dbl_" + VALID_ID + "_" + secret))
                .hasValueSatisfying(parsed -> assertThat(parsed.secret()).isEqualTo(secret));
    }

    @Test
    void rejectsAnythingNotShapedLikeAKey() {
        assertThat(ApiKeyFormat.parse(null)).isEmpty();
        assertThat(ApiKeyFormat.parse("")).isEmpty();
        assertThat(ApiKeyFormat.parse("not-a-key")).isEmpty();
        assertThat(ApiKeyFormat.parse("abc_" + VALID_ID + "_" + VALID_SECRET))
                .as("wrong prefix")
                .isEmpty();
        assertThat(ApiKeyFormat.parse("dbl_" + VALID_ID + "_" + VALID_SECRET + "A"))
                .as("too long")
                .isEmpty();
        assertThat(ApiKeyFormat.parse("dbl_" + VALID_ID + "_" + VALID_SECRET.substring(1)))
                .as("too short")
                .isEmpty();
        assertThat(ApiKeyFormat.parse("dbl_" + VALID_ID + "-" + VALID_SECRET))
                .as("wrong separator")
                .isEmpty();
        assertThat(ApiKeyFormat.parse("dbl_0123456789ABCDEF_" + VALID_SECRET))
                .as("uppercase key id")
                .isEmpty();
        assertThat(ApiKeyFormat.parse("dbl_0123456789abcdeg_" + VALID_SECRET))
                .as("non-hex key id")
                .isEmpty();
        for (String bad : new String[] {"+", "/", "=", " "}) {
            String secret = bad + "A".repeat(ApiKeyFormat.SECRET_LENGTH - 1);
            assertThat(ApiKeyFormat.parse("dbl_" + VALID_ID + "_" + secret))
                    .as("secret containing '%s'", bad)
                    .isEmpty();
        }
    }

    @Test
    void hashesTheSecretWithSha256() {
        // The standard SHA-256 test vector for "abc".
        assertThat(HexFormat.of().formatHex(ApiKeyFormat.hashSecret("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void neverPrintsTheSecret() {
        ApiKeyFormat.GeneratedKey key = ApiKeyFormat.generate(RANDOM);

        assertThat(key.toString()).doesNotContain(key.secret());
        assertThat(ApiKeyFormat.parse(key.plaintext()).orElseThrow().toString()).doesNotContain(key.secret());
    }
}
