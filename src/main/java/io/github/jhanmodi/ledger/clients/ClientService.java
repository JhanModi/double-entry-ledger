package io.github.jhanmodi.ledger.clients;

import io.github.jhanmodi.ledger.audit.Actor;
import io.github.jhanmodi.ledger.audit.AuditAction;
import io.github.jhanmodi.ledger.audit.AuditLog;
import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates API clients and issues and revokes their keys.
 *
 * <p>Only the operator creates clients and issues keys, at the command line (ADR-0016), so both are audited as the
 * operator. Each is one transaction with its audit row.
 */
@Service
public class ClientService {

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public ClientService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public ClientId createClient(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100) {
            throw new IllegalArgumentException("a client name must be 1 to 100 characters");
        }
        UUID id = jdbc.sql("INSERT INTO api_clients (name) VALUES (:name) RETURNING id")
                .param("name", name)
                .query(UUID.class)
                .single();
        audit.record(AuditAction.CLIENT_CREATED, id.toString(), Actor.OPERATOR_CLI);
        return new ClientId(id);
    }

    /** Issues a new key. The returned plaintext is the only copy that will ever exist: only its hash is stored. */
    @Transactional
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
        audit.record(AuditAction.API_KEY_ISSUED, key.keyId(), Actor.OPERATOR_CLI);
        return new IssuedApiKey(clientId, key.keyId(), scopes, key.plaintext());
    }

    /** A new client and its first key: both are created, or neither is. */
    @Transactional
    public IssuedApiKey createClientWithKey(String name, Set<Scope> scopes) {
        // Calls within this class skip Spring's proxy, so these two don't start transactions of their own. They run
        // inside this method's transaction, which is the point: if issuing the key fails, the client is rolled back.
        return issueKey(createClient(name), scopes);
    }

    /** Revokes a key so it stops working immediately. Returns false if it didn't exist or was already revoked. */
    public boolean revokeKey(String keyId) {
        return jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE key_id = :keyId AND revoked_at IS NULL")
                        .param("keyId", keyId)
                        .update()
                == 1;
    }
}
