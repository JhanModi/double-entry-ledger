# ADR-0003: Entries use direction + positive amount

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
Each entry records one side of a money movement. Each ledger transaction must balance.

## Options considered
- **Signed amounts** (e.g., negative means debit) that sum to zero. `SUM` is trivial. But what the sign means depends on a convention, and it's easy to flip by mistake.
- **Direction (DEBIT/CREDIT) + positive amount.** Standard accounting form. The invariant "debits = credits" is explicit, and the database can enforce `amount > 0`.

## Decision
- **Entry shape:** `entries(direction DEBIT|CREDIT, amount BIGINT CHECK (amount > 0))`.
- **Normal side:** every account has one.
  - **Debit-normal** (debits increase the balance): asset and expense accounts.
  - **Credit-normal** (credits increase the balance): liability, equity, and revenue accounts.
- **Balance** = sum of entries on the normal side − sum of entries on the opposite side.
- **Customer wallets are liabilities.** The platform owes that money to its customers, so a credit increases a customer's balance. The bank-settlement account (cash held at the partner bank) is an asset.

## Consequences
- Readable, and it matches accounting textbooks and the vocabulary interviewers use.
- `amount > 0` rules out a whole class of sign bugs at the database level.
- Calculating a balance has to account for the normal side. One small, heavily tested helper does this.

## How to explain it
"Every entry is a direction plus a positive amount, and each transaction's debits must equal its credits. The database enforces both. Customer balances are liabilities, because the platform owes that money to customers."
