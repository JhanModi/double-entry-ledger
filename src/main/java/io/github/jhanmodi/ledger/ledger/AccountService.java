package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Opens accounts. */
@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * A customer's wallet, owned by one client. Always a liability, because the platform owes that money to the
     * customer.
     */
    public AccountId openCustomerAccount(ClientId owner, CurrencyCode currency) {
        Objects.requireNonNull(owner, "owner");
        return accounts.insert(AccountKind.CUSTOMER, AccountType.LIABILITY, currency, owner);
    }

    /** A platform account, such as bank settlement (an asset) or fee income (revenue). Owned by no client. */
    public AccountId openSystemAccount(AccountType type, CurrencyCode currency) {
        return accounts.insert(AccountKind.SYSTEM, type, currency, null);
    }
}
