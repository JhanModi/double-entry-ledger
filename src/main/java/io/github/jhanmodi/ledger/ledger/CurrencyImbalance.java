package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import java.math.BigInteger;

/** A currency whose debits and credits don't add up to the same total. Amounts are in minor units. */
public record CurrencyImbalance(CurrencyCode currency, BigInteger totalDebits, BigInteger totalCredits) {}
