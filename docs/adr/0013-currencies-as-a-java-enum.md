# ADR-0013: Currencies as a Java enum, mirrored by the `currencies` table

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
`Money` needs each currency's ISO 4217 exponent (USD 2, JPY 0, KWD 3). The `currencies` table (V1 migration) also needs the list, so that accounts and entries can reference it with foreign keys from M3. `Money` is pure Java and must work without a database.

## Options considered
- **`java.util.Currency`.** Built into the JDK, and `getDefaultFractionDigits()` gives the exponent. But it knows about 300 currencies the ledger doesn't support, and its data ships with the JDK rather than being reviewed by us. Some entries (e.g., gold, `XAU`) report −1 digits.
- **Load currencies from the database at startup** into a registry. Flexible, but `Money` would depend on infrastructure, and unit tests would need a registry.
- **A Java enum, mirrored by the table.** A closed, reviewed set where the compiler rejects typos (`CurrencyCode.USDD` doesn't compile). Adding a currency takes two coordinated changes.

## Decision
- `CurrencyCode` is an enum (`USD(2)`, `EUR(2)`, `JPY(0)`, `KWD(3)`) and the source of the exponent for `Money`.
- The `currencies` table holds the same list for foreign keys.
- `CurrencyCodeIT` reads the table and fails if it doesn't list exactly the enum's currencies with the same exponents.
- To add a currency, add the enum value and a new migration in the same change.

## Consequences
- `Money` stays pure Java, and currency mistakes are caught at compile time.
- There are two places to update, but drift is caught by a test rather than in production.
- Adding a currency is a deliberate, reviewed change. That's appropriate for a ledger, because every new currency also needs FX pool accounts (M12).

## How to explain it
"Supported currencies are an enum, so the compiler catches typos, and each exponent is something we chose rather than whatever the JDK ships. The database table mirrors the enum for foreign keys, and an integration test fails if the two ever disagree."
