package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.audit.AuditAction;
import io.github.jhanmodi.ledger.audit.AuditLog;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens accounts. */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final AuditLog audit;

    public AccountService(AccountRepository accounts, AuditLog audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * A customer's wallet, owned by the calling client. Always a liability, because the platform owes that money to the
     * customer.
     *
     * <p>This is the only way to open a customer account, and it's audited: the account and its audit row are committed
     * together, or neither is (ADR-0020).
     */
    @Transactional
    public AccountId openCustomerAccount(Caller caller, CurrencyCode currency) {
        Objects.requireNonNull(caller, "caller");
        AccountId id = accounts.insert(AccountKind.CUSTOMER, AccountType.LIABILITY, currency, caller.clientId());
        audit.record(AuditAction.ACCOUNT_OPENED, id.toString(), caller.auditActor());
        return id;
    }

    /** A platform account, such as bank settlement (an asset) or fee income (revenue). Owned by no client. */
    public AccountId openSystemAccount(AccountType type, CurrencyCode currency) {
        return accounts.insert(AccountKind.SYSTEM, type, currency, null);
    }
}
