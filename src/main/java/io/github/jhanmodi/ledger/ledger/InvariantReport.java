package io.github.jhanmodi.ledger.ledger;

import java.util.List;

/** What the invariant checker found. Clean means both lists are empty. */
public record InvariantReport(List<BalanceMismatch> balanceMismatches, List<CurrencyImbalance> currencyImbalances) {

    public InvariantReport {
        balanceMismatches = List.copyOf(balanceMismatches);
        currencyImbalances = List.copyOf(currencyImbalances);
    }

    public boolean isClean() {
        return balanceMismatches.isEmpty() && currencyImbalances.isEmpty();
    }
}
