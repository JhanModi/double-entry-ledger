package io.github.jhanmodi.ledger.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.util.Objects;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The rule a fingerprint exists for (ADR-0023): two requests have the same fingerprint exactly when they're the same
 * request, ignoring the idempotency key. Values come from small pools, so that generated pairs are often equal, often
 * differ in a single field, and include tricky descriptions (none, empty, a dash, text that looks like the encoding).
 */
class MoneyMovementFingerprintsPropertiesTest {

    private static final Arbitrary<AccountId> ACCOUNTS = Arbitraries.of(
            new AccountId(UUID.fromString("0190f0a8-0000-7000-8000-00000000000a")),
            new AccountId(UUID.fromString("0190f0a8-0000-7000-8000-00000000000b")),
            new AccountId(UUID.fromString("f190f0a8-0000-7000-8000-00000000000c")));

    private static final Arbitrary<Money> AMOUNTS = Combinators.combine(
                    Arbitraries.longs().between(1, 3), Arbitraries.of(CurrencyCode.USD, CurrencyCode.EUR))
            .as(Money::of);

    private static final Arbitrary<IdempotencyKey> KEYS =
            Arbitraries.of("key-1", "key-2").map(IdempotencyKey::new);

    @Property
    void twoTransfersShareAFingerprintExactlyWhenTheyAreTheSameRequest(
            @ForAll("transfers") TransferCommand one, @ForAll("transfers") TransferCommand other) {
        boolean sameRequest = one.source().equals(other.source())
                && one.destination().equals(other.destination())
                && one.amount().equals(other.amount())
                && Objects.equals(one.description(), other.description());

        assertThat(one.fingerprint().equals(other.fingerprint())).isEqualTo(sameRequest);
    }

    @Property
    void twoFundingsShareAFingerprintExactlyWhenTheyAreTheSameRequest(
            @ForAll("fundings") FundingCommand one, @ForAll("fundings") FundingCommand other) {
        boolean sameRequest = one.account().equals(other.account())
                && one.amount().equals(other.amount())
                && one.externalReference().equals(other.externalReference());

        assertThat(one.fingerprint().equals(other.fingerprint())).isEqualTo(sameRequest);
    }

    @Provide
    Arbitrary<TransferCommand> transfers() {
        Arbitrary<String> descriptions =
                Arbitraries.of("rent", "Rent", "", "-", "1:a", "a1:", "😀").injectNull(0.2);
        return Combinators.combine(ACCOUNTS, ACCOUNTS, AMOUNTS, descriptions, KEYS)
                .filter((source, destination, amount, description, key) -> !source.equals(destination))
                .as(TransferCommand::new);
    }

    @Provide
    Arbitrary<FundingCommand> fundings() {
        Arbitrary<String> references = Arbitraries.of("BANK-REF-1", "BANK-REF-2", "-", "1:a", "😀");
        return Combinators.combine(ACCOUNTS, AMOUNTS, references, KEYS).as(FundingCommand::new);
    }
}
