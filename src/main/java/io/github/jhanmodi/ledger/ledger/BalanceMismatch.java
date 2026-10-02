package io.github.jhanmodi.ledger.ledger;

import java.math.BigInteger;

/** A customer account whose cached balance disagrees with its entries. Amounts are in minor units. */
public record BalanceMismatch(AccountId accountId, long cachedBalance, BigInteger derivedBalance) {}
