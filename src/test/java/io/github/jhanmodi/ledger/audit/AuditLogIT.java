package io.github.jhanmodi.ledger.audit;

import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.IssuedApiKey;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.PostingService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What gets audited, with which details, and the guarantee that matters most: an audit row is committed if and only if
 * the action it describes is (ADR-0020).
 *
 * <p>The application can't read the audit log, so these tests read it as the database owner.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditLogIT {

    @Autowired
    AuditLog auditLog;

    @Autowired
    ClientService clientService;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    OwnerDatabase owner;

    LedgerFixtures ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService, clientService);
    }

    // --- What gets audited ---

    @Test
    void creatingAClientIsAuditedAsTheOperator() {
        ClientId client = clientService.createClient("acme");

        assertThat(auditRows(client.toString()))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .contains(entry("action", "CLIENT_CREATED"), entry("actor_type", "OPERATOR_CLI"))
                        .containsEntry("actor_client_id", null)
                        .containsEntry("actor_key_id", null)
                        .containsEntry("request_id", null)
                        .containsEntry("source_ip", null));
    }

    @Test
    void issuingAKeyIsAuditedByItsPublicKeyIdNeverItsSecret() {
        IssuedApiKey key = clientService.issueKey(clientService.createClient("acme"), Set.of(Scope.READ));
        // dbl_<16-character key id>_<secret>. By position, because '_' can also appear inside the secret.
        String secret = key.plaintext().substring("dbl_".length() + key.keyId().length() + 1);

        assertThat(auditRows(key.keyId()))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .contains(entry("action", "API_KEY_ISSUED"), entry("actor_type", "OPERATOR_CLI")));
        assertThat(owner.jdbc()
                        // strpos, not LIKE: '_' is a LIKE wildcard, and the secret may contain one.
                        .sql("SELECT count(*) FROM audit_log a WHERE strpos(a::text, :secret) > 0")
                        .param("secret", secret)
                        .query(Long.class)
                        .single())
                .as("the key's secret appears nowhere in the audit log")
                .isZero();
    }

    @Test
    void openingAnAccountIsAuditedWithTheCallersKeyRequestAndAddress() {
        Caller caller = ledger.caller(ledger.client());

        AccountId account = accountService.openCustomerAccount(caller, USD);

        assertThat(auditRows(account.toString()))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .contains(
                                entry("action", "ACCOUNT_OPENED"),
                                entry("actor_type", "API_KEY"),
                                entry("actor_client_id", caller.clientId().value()),
                                entry("actor_key_id", caller.client().keyId()),
                                entry("request_id", caller.requestId().value()),
                                entry("source_ip", LedgerFixtures.TEST_SOURCE_IP)));
    }

    // --- All or nothing ---

    @Test
    void anActionThatRollsBackLeavesNoAuditRow() {
        Caller caller = ledger.caller(ledger.client());
        AtomicReference<AccountId> opened = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            opened.set(accountService.openCustomerAccount(caller, USD));
            status.setRollbackOnly();
        });

        assertThat(owner.jdbc()
                        .sql("SELECT count(*) FROM accounts WHERE id = :id")
                        .param("id", opened.get().value())
                        .query(Long.class)
                        .single())
                .as("the account was rolled back")
                .isZero();
        assertThat(auditRows(opened.get().toString()))
                .as("and so was its audit row")
                .isEmpty();
    }

    @Test
    void aClientAndItsFirstKeyAreCreatedTogetherOrNotAtAll() {
        String name = "all-or-nothing " + UUID.randomUUID();

        // Issuing the key fails (no scopes) after the client was inserted, so the client must be rolled back too.
        assertThatThrownBy(() -> clientService.createClientWithKey(name, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(owner.jdbc()
                        .sql("SELECT count(*) FROM api_clients WHERE name = :name")
                        .param("name", name)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void recordingOutsideATransactionIsRefused() {
        String target = UUID.randomUUID().toString();

        // MANDATORY propagation: an audit row can't be written on its own, apart from the action it describes.
        assertThatThrownBy(() -> auditLog.record(AuditAction.CLIENT_CREATED, target, Actor.OPERATOR_CLI))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(auditRows(target)).isEmpty();
    }

    /** The audit rows for one target, read as the owner. {@code source_ip} comes back as plain text. */
    private List<Map<String, Object>> auditRows(String targetId) {
        return owner.jdbc().sql("""
                        SELECT action, actor_type, actor_client_id, actor_key_id, request_id,
                               host(source_ip) AS source_ip
                        FROM audit_log
                        WHERE target_id = :targetId
                        """).param("targetId", targetId).query().listOfRows();
    }
}
