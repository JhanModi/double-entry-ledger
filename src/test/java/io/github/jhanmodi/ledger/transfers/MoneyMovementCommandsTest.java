package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a {@link TransferCommand} or {@link FundingCommand} refuses to be, before any database is involved. */
class MoneyMovementCommandsTest {

    private static final AccountId A = new AccountId(UUID.randomUUID());
    private static final AccountId B = new AccountId(UUID.randomUUID());
    private static final IdempotencyKey KEY = new IdempotencyKey("key-1");

    // --- Transfers ---

    @Test
    void aValidTransfer() {
        assertThatCode(() -> new TransferCommand(A, B, usd(100), "rent", KEY)).doesNotThrowAnyException();
        assertThatCode(() -> new TransferCommand(A, B, usd(100), null, KEY))
                .as("the description is optional")
                .doesNotThrowAnyException();
    }

    @Test
    void aTransferAmountMustBePositive() {
        assertThatThrownBy(() -> new TransferCommand(A, B, usd(0), null, KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferCommand(A, B, usd(-1), null, KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTransferIsBetweenTwoDifferentAccounts() {
        assertThatThrownBy(() -> new TransferCommand(A, A, usd(100), null, KEY))
                .isInstanceOf(SameAccountException.class);
    }

    @Test
    void aTransferIsWithinTheAmountLimit() {
        assertThatThrownBy(() -> new TransferCommand(A, B, usd(AmountLimits.USD_MAX_MINOR_UNITS + 1), null, KEY))
                .isInstanceOf(AmountTooLargeException.class);
    }

    @Test
    void aTransferDescriptionIsAtMost500CharactersCountedAsPostgresCountsThem() {
        // 500 emoji are 1,000 UTF-16 units in Java but 500 characters to Postgres, so they fit.
        assertThatCode(() -> new TransferCommand(A, B, usd(1), "😀".repeat(500), KEY))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new TransferCommand(A, B, usd(1), "x".repeat(501), KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Fundings ---

    @Test
    void aValidFunding() {
        assertThatCode(() -> new FundingCommand(A, usd(100), "BANK-REF-1", KEY)).doesNotThrowAnyException();
    }

    @Test
    void aFundingAmountMustBePositiveAndWithinTheLimit() {
        assertThatThrownBy(() -> new FundingCommand(A, usd(0), "BANK-REF-1", KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundingCommand(A, usd(AmountLimits.USD_MAX_MINOR_UNITS + 1), "BANK-REF-1", KEY))
                .isInstanceOf(AmountTooLargeException.class);
    }

    @Test
    void aFundingNeedsAnExternalReferenceOf1To100Characters() {
        assertThatCode(() -> new FundingCommand(A, usd(1), "r".repeat(100), KEY))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new FundingCommand(A, usd(1), "", KEY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundingCommand(A, usd(1), "   ", KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundingCommand(A, usd(1), "r".repeat(101), KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
