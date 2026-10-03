package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.ledger.Account;
import io.github.jhanmodi.ledger.ledger.AccountBalance;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountStatus;
import io.github.jhanmodi.ledger.ledger.Direction;
import io.github.jhanmodi.ledger.ledger.EntryPage;
import io.github.jhanmodi.ledger.ledger.EntryView;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionType;
import io.github.jhanmodi.ledger.ledger.PostingRequest;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import io.github.jhanmodi.ledger.transfers.Funding;
import io.github.jhanmodi.ledger.transfers.FundingCommand;
import io.github.jhanmodi.ledger.transfers.IdempotencyKey;
import io.github.jhanmodi.ledger.transfers.Transfer;
import io.github.jhanmodi.ledger.transfers.TransferCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

    /**
     * Money in a request. Strict parsing (ADR-0021) has already refused anything but a JSON integer, such as
     * {@code 10.5}, {@code "1050"}, or {@code 1e3}; this rejects missing and non-positive amounts with a 400, so no
     * command ever sees one. (A positive amount above the limit is a business rule: the command refuses it with a 422.)
     */
    record AmountRequest(
            @NotNull @Positive Long amount, @NotNull CurrencyCode currency) {

        Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    record OpenAccountRequest(@NotNull CurrencyCode currency) {}

    record TransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @NotNull @Valid AmountRequest amount,

            @MaxCharacters(PostingRequest.MAX_DESCRIPTION_LENGTH)
            String description) {

        TransferCommand toCommand(IdempotencyKey key) {
            return new TransferCommand(
                    new AccountId(sourceAccountId),
                    new AccountId(destinationAccountId),
                    amount.toMoney(),
                    description,
                    key);
        }
    }

    record TransferResponse(
            UUID id,
            UUID sourceAccountId,
            UUID destinationAccountId,
            MoneyJson amount,
            String description,
            String idempotencyKey,
            UUID ledgerTransactionId,
            Instant createdAt) {

        static TransferResponse of(Transfer transfer) {
            return new TransferResponse(
                    transfer.id().value(),
                    transfer.source().value(),
                    transfer.destination().value(),
                    MoneyJson.of(transfer.amount()),
                    transfer.description(),
                    transfer.idempotencyKey().value(),
                    transfer.ledgerTransactionId().value(),
                    transfer.createdAt());
        }
    }

    record FundingRequest(
            @NotNull UUID accountId,
            @NotNull @Valid AmountRequest amount,

            @NotBlank @MaxCharacters(FundingCommand.MAX_EXTERNAL_REFERENCE_LENGTH)
            String externalReference) {

        FundingCommand toCommand(IdempotencyKey key) {
            return new FundingCommand(new AccountId(accountId), amount.toMoney(), externalReference, key);
        }
    }

    record FundingResponse(
            UUID id,
            UUID accountId,
            MoneyJson amount,
            String externalReference,
            String idempotencyKey,
            UUID ledgerTransactionId,
            Instant createdAt) {

        static FundingResponse of(Funding funding) {
            return new FundingResponse(
                    funding.id().value(),
                    funding.account().value(),
                    MoneyJson.of(funding.amount()),
                    funding.externalReference(),
                    funding.idempotencyKey().value(),
                    funding.ledgerTransactionId().value(),
                    funding.createdAt());
        }
    }

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
