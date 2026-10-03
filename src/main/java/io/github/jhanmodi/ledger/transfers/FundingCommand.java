package io.github.jhanmodi.ledger.transfers;

import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.idempotency.IdempotentOperation;
import io.github.jhanmodi.ledger.idempotency.RequestFingerprint;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.money.Money;
import java.util.Objects;

/**
 * A request to fund one of the caller's accounts, standing in for an inbound bank deposit until M9 (ADR-0018).
 * Construction fails unless the amount is positive and within the limit, and the external reference is 1 to 100
 * characters.
 *
 * @param externalReference the bank's reference for the deposit, kept for reconciliation (M11)
 */
public record FundingCommand(AccountId account, Money amount, String externalReference, IdempotencyKey idempotencyKey) {

    /** Matches the {@code fundings_external_reference_length} constraint, which counts characters. */
    public static final int MAX_EXTERNAL_REFERENCE_LENGTH = 100;

    public FundingCommand {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(externalReference, "externalReference");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a funding amount must be positive, got " + amount);
        }
        if (externalReference.isBlank()
                || externalReference.codePointCount(0, externalReference.length()) > MAX_EXTERNAL_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(
                    "an external reference is 1 to " + MAX_EXTERNAL_REFERENCE_LENGTH + " characters");
        }
        AmountLimits.requireWithinLimit(amount);
    }

    /** This request's fingerprint (ADR-0023): every field except the key, in a fixed order. See {@link TransferCommand}. */
    public RequestFingerprint fingerprint() {
        return RequestFingerprint.of(IdempotentOperation.FUNDING)
                .field("accountId", account.value().toString())
                .field("amount", Long.toString(amount.minorUnits()))
                .field("currency", amount.currency().name())
                .field("externalReference", externalReference)
                .build();
    }
}
