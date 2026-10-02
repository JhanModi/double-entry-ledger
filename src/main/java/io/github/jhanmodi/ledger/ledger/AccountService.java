package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import org.springframework.stereotype.Service;

/** Opens accounts. */
@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** A customer's wallet: always a liability, because the platform owes that money to the customer. */
    public AccountId openCustomerAccount(CurrencyCode currency) {
        return accounts.insert(AccountKind.CUSTOMER, AccountType.LIABILITY, currency);
    }

    /** A platform account, such as bank settlement (an asset) or fee income (revenue). */
    public AccountId openSystemAccount(AccountType type, CurrencyCode currency) {
        return accounts.insert(AccountKind.SYSTEM, type, currency);
    }
}
