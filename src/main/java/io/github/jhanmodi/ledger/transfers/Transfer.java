package io.github.jhanmodi.ledger.transfers;

import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionId;
import io.github.jhanmodi.ledger.money.Money;
import java.time.Instant;

/**
 * A completed transfer: the business record beside the ledger transaction that moved the money (ADR-0018).
 *
 * @param description null if the client gave none
 */
public record Transfer(
        TransferId id,
        AccountId source,
        AccountId destination,
        Money amount,
        String description,
        IdempotencyKey idempotencyKey,
        LedgerTransactionId ledgerTransactionId,
        Instant createdAt) {}
