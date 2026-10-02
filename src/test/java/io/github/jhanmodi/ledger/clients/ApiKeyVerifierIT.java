package io.github.jhanmodi.ledger.clients;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyVerifierIT {

    @Autowired
    ClientService clients;

    @Autowired
    ApiKeyVerifier verifier;

    @Autowired
    OwnerDatabase owner;

    @Test
    void aFreshlyIssuedKeyAuthenticatesItsClientWithItsScopes() {
        ClientId client = clients.createClient("acme");
        IssuedApiKey key = clients.issueKey(client, EnumSet.of(Scope.READ, Scope.WRITE));

        assertThat(verifier.verify(key.plaintext())).hasValueSatisfying(authenticated -> {
            assertThat(authenticated.clientId()).isEqualTo(client);
            assertThat(authenticated.keyId()).isEqualTo(key.keyId());
            assertThat(authenticated.scopes()).containsExactlyInAnyOrder(Scope.READ, Scope.WRITE);
        });
    }

    @Test
    void aWrongSecretIsRejected() {
        IssuedApiKey key = clients.issueKey(clients.createClient("acme"), Set.of(Scope.READ));
        String plaintext = key.plaintext();
        // Same key id, still a well-formed key, one character of the secret changed.
        char last = plaintext.charAt(plaintext.length() - 1);
        String tampered = plaintext.substring(0, plaintext.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(verifier.verify(tampered)).isEmpty();
    }

    @Test
    void anUnknownKeyIdIsRejected() {
        assertThat(verifier.verify("dbl_0000000000000000_" + "A".repeat(43))).isEmpty();
    }

    @Test
    void aMalformedKeyIsRejected() {
        assertThat(verifier.verify("not a key")).isEmpty();
        assertThat(verifier.verify(null)).isEmpty();
    }

    @Test
    void aRevokedKeyStopsWorkingAndTheClientsOtherKeysKeepWorking() {
        ClientId client = clients.createClient("acme");
        IssuedApiKey revoked = clients.issueKey(client, Set.of(Scope.READ));
        IssuedApiKey kept = clients.issueKey(client, Set.of(Scope.READ));

        assertThat(clients.revokeKey(revoked.keyId())).isTrue();

        assertThat(verifier.verify(revoked.plaintext())).isEmpty();
        assertThat(verifier.verify(kept.plaintext())).isPresent();
        assertThat(clients.revokeKey(revoked.keyId())).as("revoking twice").isFalse();
    }

    @Test
    void aDisabledClientsKeysStopWorking() {
        ClientId client = clients.createClient("acme");
        IssuedApiKey key = clients.issueKey(client, Set.of(Scope.READ));
        // The app can't change a client's status (ADR-0015), so the test does it as the owner, as an operator would.
        owner.jdbc()
                .sql("UPDATE api_clients SET status = 'DISABLED' WHERE id = :id")
                .param("id", client.value())
                .update();

        assertThat(verifier.verify(key.plaintext())).isEmpty();
    }

    @Test
    void onlyAHashOfTheSecretIsStored() {
        IssuedApiKey key = clients.issueKey(clients.createClient("acme"), Set.of(Scope.READ));
        // The secret starts at a fixed position: after "dbl_", 16 hex characters, and "_".
        String secret = key.plaintext().substring(21);

        String wholeRow = owner.jdbc()
                .sql("SELECT row_to_json(k)::text FROM api_keys k WHERE key_id = :keyId")
                .param("keyId", key.keyId())
                .query(String.class)
                .single();
        byte[] storedHash = owner.jdbc()
                .sql("SELECT secret_hash FROM api_keys WHERE key_id = :keyId")
                .param("keyId", key.keyId())
                .query(byte[].class)
                .single();

        assertThat(wholeRow).doesNotContain(secret).doesNotContain(key.plaintext());
        assertThat(HexFormat.of().formatHex(storedHash))
                .isEqualTo(HexFormat.of().formatHex(ApiKeyFormat.hashSecret(secret)));
    }
}
