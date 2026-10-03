package io.github.jhanmodi.ledger.transfers;

import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionId;
import io.github.jhanmodi.ledger.money.Money;
import java.time.Instant;

/** A completed funding: the business record beside the ledger transaction that moved the money (ADR-0018). */
public record Funding(
        FundingId id,
        AccountId account,
        Money amount,
        String externalReference,
        IdempotencyKey idempotencyKey,
        LedgerTransactionId ledgerTransactionId,
        Instant createdAt) {}
