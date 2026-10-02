# ADR-0002: Money as `long` minor units + currency

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
Binary floating point can't represent most decimal fractions exactly (`0.1 + 0.2 != 0.3`). Currencies also have different numbers of decimal places (ISO 4217 exponent): USD has 2, JPY 0, KWD 3.

## Options considered
- **`double`.** Rejected outright: it's inexact.
- **`BigDecimal` everywhere.** Exact, with flexible precision. Pitfalls:
  - `equals` compares scale, so `2.0` and `2.00` aren't equal.
  - Forgetting a rounding mode throws an exception or lets scale drift.
  - It's slower and more verbose.
- **`long` minor units + currency.** Exact, fast, and equality is simple. The range is about ±9.2 × 10^18 minor units, far more than needed. It can't directly represent values smaller than one minor unit.

## Decision
- **Type:** `Money(long minorUnits, CurrencyCode currency)`. The exponent comes from the `currencies` table (ISO 4217).
- **Arithmetic:** `Math.addExact` / `Math.subtractExact`, which throw on overflow instead of silently wrapping.
- **`BigDecimal` only inside FX conversion and percentage calculations.** Results are converted back with a **named** `RoundingMode` at one documented point.
- **Database:** `BIGINT`.
- **JSON:** integer minor units plus a currency code, e.g. `{"amount": 1050, "currency": "USD"}` is $10.50.
- **Amount limit:** the per-request maximum stays well below 2^53, so JavaScript clients (which store numbers as doubles) never lose precision.
- An ArchUnit rule forbids `float`/`double` in money-handling packages.

## Consequences
- No rounding drift, and database sums are exact.
- API clients convert to and from minor units. This is documented in the API docs.
- Assets with more decimals (e.g., USDC with 6) work as long as their exponent is defined.

## How to explain it
"Floats can't represent 0.10 exactly, so errors build up. I store money as a 64-bit integer count of the currency's smallest unit, plus the currency code. Overflow checks throw instead of wrapping. Decimal math appears only in FX, with one explicitly named rounding step."
