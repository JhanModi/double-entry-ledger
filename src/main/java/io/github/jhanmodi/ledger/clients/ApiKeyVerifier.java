package io.github.jhanmodi.ledger.clients;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Decides whether a presented API key is valid (ADR-0016). The answer is either the authenticated client or empty.
 * The caller never learns <em>why</em> a key was rejected (malformed, unknown, wrong secret, revoked, or disabled
 * client), so a rejection gives an attacker nothing to work with.
 */
@Service
public class ApiKeyVerifier {

    /** Compared against when the key id is unknown, so that case does the same work as a wrong secret. */
    private static final byte[] NO_STORED_HASH = new byte[32];

    private final JdbcClient jdbc;

    public ApiKeyVerifier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AuthenticatedClient> verify(String presentedKey) {
        Optional<ApiKeyFormat.ParsedKey> parsed = ApiKeyFormat.parse(presentedKey);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        ApiKeyFormat.ParsedKey key = parsed.get();
        byte[] presentedHash = ApiKeyFormat.hashSecret(key.secret());
        Optional<StoredKey> stored = findByKeyId(key.keyId());

        // MessageDigest.isEqual takes the same time however many leading bytes match. A normal comparison stops at the
        // first difference, and that timing difference could let an attacker guess a hash byte by byte.
        byte[] expectedHash = stored.map(StoredKey::secretHash).orElse(NO_STORED_HASH);
        boolean secretMatches = MessageDigest.isEqual(expectedHash, presentedHash);

        if (stored.isEmpty()
                || !secretMatches
                || stored.get().revoked()
                || stored.get().clientStatus() != ClientStatus.ACTIVE) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedClient(
                stored.get().clientId(), key.keyId(), stored.get().scopes()));
    }

    private Optional<StoredKey> findByKeyId(String keyId) {
        return jdbc.sql("""
                        SELECT k.client_id, k.secret_hash, array_to_string(k.scopes, ',') AS scopes,
                               k.revoked_at IS NOT NULL AS revoked, c.status
                        FROM api_keys k
                        JOIN api_clients c ON c.id = k.client_id
                        WHERE k.key_id = :keyId
                        """)
                .param("keyId", keyId)
                .query((rs, rowNum) -> new StoredKey(
                        new ClientId(rs.getObject("client_id", UUID.class)),
                        rs.getBytes("secret_hash"),
                        Arrays.stream(rs.getString("scopes").split(","))
                                .map(Scope::fromValue)
                                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Scope.class))),
                        rs.getBoolean("revoked"),
                        ClientStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    private record StoredKey(
            ClientId clientId, byte[] secretHash, Set<Scope> scopes, boolean revoked, ClientStatus clientStatus) {}
}
