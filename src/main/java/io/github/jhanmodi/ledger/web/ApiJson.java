package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.ledger.Account;
import io.github.jhanmodi.ledger.ledger.AccountBalance;
import io.github.jhanmodi.ledger.ledger.AccountStatus;
import io.github.jhanmodi.ledger.ledger.Direction;
import io.github.jhanmodi.ledger.ledger.EntryPage;
import io.github.jhanmodi.ledger.ledger.EntryView;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionType;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The JSON shapes of the API. Kept separate from domain types, so the API can stay stable while the domain evolves. */
final class ApiJson {

    private ApiJson() {}

    /** Money as integer minor units plus a currency: {@code {"amount": 1050, "currency": "USD"}} is $10.50 (ADR-0002). */
    record MoneyJson(long amount, CurrencyCode currency) {

        static MoneyJson of(Money money) {
            return new MoneyJson(money.minorUnits(), money.currency());
        }
    }

    record OpenAccountRequest(@NotNull CurrencyCode currency) {}

    record AccountResponse(UUID id, CurrencyCode currency, AccountStatus status, BalanceJson balance) {

        static AccountResponse of(Account account, AccountBalance balance) {
            return new AccountResponse(
                    account.id().value(),
                    account.currency(),
                    account.status(),
                    new BalanceJson(
                            MoneyJson.of(balance.posted()),
                            MoneyJson.of(balance.held()),
                            MoneyJson.of(balance.available())));
        }
    }

    record BalanceJson(MoneyJson posted, MoneyJson held, MoneyJson available) {}

    record EntryJson(
            UUID id,
            UUID transactionId,
            LedgerTransactionType type,
            String description,
            LocalDate effectiveDate,
            Instant recordedAt,
            Direction direction,
            MoneyJson amount) {

        static EntryJson of(EntryView entry) {
            return new EntryJson(
                    entry.entryId().value(),
                    entry.transactionId().value(),
                    entry.type(),
                    entry.description(),
                    entry.effectiveDate(),
                    entry.recordedAt(),
                    entry.direction(),
                    MoneyJson.of(entry.amount()));
        }
    }

    /** One page of history, newest first. Pass {@code nextCursor} as {@code ?cursor=} for the next page; null on the last. */
    record EntryPageJson(List<EntryJson> entries, UUID nextCursor) {

        static EntryPageJson of(EntryPage page) {
            return new EntryPageJson(
                    page.entries().stream().map(EntryJson::of).toList(),
                    page.nextCursor().map(cursor -> cursor.value()).orElse(null));
        }
    }
}
