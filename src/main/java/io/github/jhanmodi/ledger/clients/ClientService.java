package io.github.jhanmodi.ledger.clients;

import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Creates API clients and issues and revokes their keys. */
@Service
public class ClientService {

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public ClientService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ClientId createClient(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) {
            throw new IllegalArgumentException("a client name must be 1 to 100 characters");
        }
        UUID id = jdbc.sql("INSERT INTO api_clients (name) VALUES (:name) RETURNING id")
                .param("name", name)
                .query(UUID.class)
                .single();
        return new ClientId(id);
    }

    /** Issues a new key. The returned plaintext is the only copy that will ever exist: only its hash is stored. */
    public IssuedApiKey issueKey(ClientId clientId, Set<Scope> scopes) {
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("a key needs at least one scope");
        }
        ApiKeyFormat.GeneratedKey key = ApiKeyFormat.generate(random);
        jdbc.sql("""
                        INSERT INTO api_keys (client_id, key_id, secret_hash, scopes)
                        VALUES (:clientId, :keyId, :secretHash, string_to_array(:scopes, ','))
                        """)
                .param("clientId", clientId.value())
                .param("keyId", key.keyId())
                .param("secretHash", ApiKeyFormat.hashSecret(key.secret()))
                .param("scopes", scopes.stream().map(Scope::value).collect(Collectors.joining(",")))
                .update();
        return new IssuedApiKey(clientId, key.keyId(), scopes, key.plaintext());
    }

    /** Revokes a key so it stops working immediately. Returns false if it didn't exist or was already revoked. */
    public boolean revokeKey(String keyId) {
        return jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE key_id = :keyId AND revoked_at IS NULL")
                        .param("keyId", keyId)
                        .update()
                == 1;
    }
}
