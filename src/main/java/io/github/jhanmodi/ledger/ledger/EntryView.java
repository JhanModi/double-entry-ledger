package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/** One entry in an account's history, with the details of the transaction it belongs to. */
public record EntryView(
        EntryId entryId,
        LedgerTransactionId transactionId,
        LedgerTransactionType type,
        String description,
        LocalDate effectiveDate,
        Instant recordedAt,
        Direction direction,
        Money amount) {}
