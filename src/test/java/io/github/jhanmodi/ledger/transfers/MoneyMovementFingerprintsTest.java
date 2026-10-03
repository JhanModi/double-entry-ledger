package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What each command puts in its fingerprint (ADR-0023, D3): every field but the idempotency key, so a retry with a
 * different value is recognised as a different request. The fields and their order are part of the stored claims'
 * contract, so one transfer's and one funding's fingerprints are pinned to values computed outside Java:
 *
 * <pre>
 * printf '2:v18:TRANSFER15:sourceAccountId36:...a20:destinationAccountId36:...b6:amount4:10508:currency3:USD11:description9:rent \xF0\x9F\x98\x80' | sha256sum
 * printf '2:v17:FUNDING9:accountId36:...a6:amount4:25008:currency3:USD17:externalReference10:BANK-REF-1' | sha256sum
 * </pre>
 *
 * (with the account ids written out in full).
 */
class MoneyMovementFingerprintsTest {

    private static final AccountId A = new AccountId(UUID.fromString("0190f0a8-0000-7000-8000-00000000000a"));
    private static final AccountId B = new AccountId(UUID.fromString("0190f0a8-0000-7000-8000-00000000000b"));
    private static final AccountId C = new AccountId(UUID.fromString("0190f0a8-0000-7000-8000-00000000000c"));
    private static final IdempotencyKey KEY = new IdempotencyKey("key-1");
    private static final IdempotencyKey OTHER_KEY = new IdempotencyKey("key-2");

    // --- Transfers ---

    @Test
    void aTransfersFingerprintIsPinned() {
        // The description's 9 bytes include a 4-byte emoji, so the length really is in bytes.
        TransferCommand transfer = new TransferCommand(A, B, usd(1050), "rent 😀", KEY);

        assertThat(transfer.fingerprint())
                .hasToString("016e71516c3836e0d23e391ba25aefb3596b31ef108963836ff6da97987b662e");
    }

    @Test
    void everyTransferFieldButTheKeyChangesTheFingerprint() {
        TransferCommand original = new TransferCommand(A, B, usd(100), "rent", KEY);

        assertThat(new TransferCommand(A, B, usd(100), "rent", OTHER_KEY).fingerprint())
                .as("the key is how a retry is found, not part of the request")
                .isEqualTo(original.fingerprint());
        assertThat(new TransferCommand(C, B, usd(100), "rent", KEY).fingerprint())
                .as("source")
                .isNotEqualTo(original.fingerprint());
        assertThat(new TransferCommand(A, C, usd(100), "rent", KEY).fingerprint())
                .as("destination")
                .isNotEqualTo(original.fingerprint());
        assertThat(new TransferCommand(B, A, usd(100), "rent", KEY).fingerprint())
                .as("direction")
                .isNotEqualTo(original.fingerprint());
        assertThat(new TransferCommand(A, B, usd(101), "rent", KEY).fingerprint())
                .as("amount")
                .isNotEqualTo(original.fingerprint());
        assertThat(new TransferCommand(A, B, Money.of(100, EUR), "rent", KEY).fingerprint())
                .as("currency")
                .isNotEqualTo(original.fingerprint());
        assertThat(new TransferCommand(A, B, usd(100), "Rent", KEY).fingerprint())
                .as("description")
                .isNotEqualTo(original.fingerprint());
    }

    @Test
    void noDescriptionDiffersFromAnEmptyOne() {
        assertThat(new TransferCommand(A, B, usd(100), null, KEY).fingerprint())
                .isNotEqualTo(new TransferCommand(A, B, usd(100), "", KEY).fingerprint());
    }

    // --- Fundings ---

    @Test
    void aFundingsFingerprintIsPinned() {
        FundingCommand funding = new FundingCommand(A, usd(2500), "BANK-REF-1", KEY);

        assertThat(funding.fingerprint())
                .hasToString("35f28283f20a4441c3a1886358855e7462c4e4e469f3af491b15adf0ab974e75");
    }

    @Test
    void everyFundingFieldButTheKeyChangesTheFingerprint() {
        FundingCommand original = new FundingCommand(A, usd(100), "BANK-REF-1", KEY);

        assertThat(new FundingCommand(A, usd(100), "BANK-REF-1", OTHER_KEY).fingerprint())
                .isEqualTo(original.fingerprint());
        assertThat(new FundingCommand(B, usd(100), "BANK-REF-1", KEY).fingerprint())
                .as("account")
                .isNotEqualTo(original.fingerprint());
        assertThat(new FundingCommand(A, usd(101), "BANK-REF-1", KEY).fingerprint())
                .as("amount")
                .isNotEqualTo(original.fingerprint());
        assertThat(new FundingCommand(A, Money.of(100, EUR), "BANK-REF-1", KEY).fingerprint())
                .as("currency")
                .isNotEqualTo(original.fingerprint());
        assertThat(new FundingCommand(A, usd(100), "BANK-REF-2", KEY).fingerprint())
                .as("external reference")
                .isNotEqualTo(original.fingerprint());
    }

    @Test
    void aTransferAndAFundingNeverShareAFingerprint() {
        // Even when every value they have in common is the same, the operation differs.
        assertThat(new TransferCommand(A, B, usd(100), null, KEY).fingerprint())
                .isNotEqualTo(new FundingCommand(A, usd(100), "x", KEY).fingerprint());
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
