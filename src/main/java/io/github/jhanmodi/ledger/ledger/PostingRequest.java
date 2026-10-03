package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A ledger transaction to be posted. Construction fails unless the request is structurally valid: at least two
 * entries, and debits equal to credits in every currency. Whether the accounts exist is checked when it's posted.
 */
public record PostingRequest(LedgerTransactionType type, String description, List<NewEntry> entries) {

    /** Matches the {@code ledger_transactions_description_length} constraint, which counts characters. */
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    public PostingRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(entries, "entries");
        // Count characters the way Postgres does; length() counts UTF-16 units, so an emoji would count twice.
        if (description != null && description.codePointCount(0, description.length()) > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description is longer than " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new UnbalancedPostingException("a posting needs at least 2 entries, got " + entries.size());
        }
        requireBalancedInEveryCurrency(entries);
    }

    private static void requireBalancedInEveryCurrency(List<NewEntry> entries) {
        Map<CurrencyCode, Money> debitsMinusCredits = new EnumMap<>(CurrencyCode.class);
        for (NewEntry entry : entries) {
            Money signed = entry.direction() == Direction.DEBIT
                    ? entry.amount()
                    : entry.amount().negate();
            debitsMinusCredits.merge(entry.amount().currency(), signed, Money::plus);
        }
        debitsMinusCredits.forEach((currency, difference) -> {
            if (!difference.isZero()) {
                throw new UnbalancedPostingException("debits and credits differ by " + difference + " in " + currency);
            }
        });
    }
}
