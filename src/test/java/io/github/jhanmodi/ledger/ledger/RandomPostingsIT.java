package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Hundreds of random fundings and transfers, some of which overdraw and must be rejected. The seed is in every
 * failure message; rerun with {@code -DargLine=-DrandomPostings.seed=<seed>} to reproduce a failure exactly.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RandomPostingsIT {

    private static final int OPERATIONS = 500;
    private static final int CUSTOMERS_PER_CURRENCY = 4;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    LedgerQueries queries;

    @Autowired
    InvariantChecker invariantChecker;

    LedgerFixtures ledger;
    long seed;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService);
        seed = Long.getLong("randomPostings.seed", ThreadLocalRandom.current().nextLong());
    }

    @Test
    void cachedBalancesMatchAnIndependentModel() {
        Simulation simulation = runRandomPostings();

        simulation.expected.forEach((account, expectedMinorUnits) -> {
            CurrencyCode currency = queries.account(account).currency();
            assertThat(queries.balance(account).posted())
                    .as("account %s (seed %d)", account, seed)
                    .isEqualTo(Money.of(expectedMinorUnits, currency));
        });
        assertThat(simulation.rejectedOverdrafts)
                .as("the run should include some rejected overdrafts (seed %d)", seed)
                .isPositive();
    }

    @Test
    void theInvariantCheckerReportsCleanAfterRandomPostings() {
        runRandomPostings();

        InvariantReport report = invariantChecker.check();

        assertThat(report.isClean()).as("%s (seed %d)", report, seed).isTrue();
    }

    /** Applies random postings and tracks, in plain Java, what every account's balance should be. */
    private Simulation runRandomPostings() {
        Random random = new Random(seed);
        Simulation simulation = new Simulation();
        Map<CurrencyCode, AccountId> banks = new HashMap<>();
        Map<CurrencyCode, List<AccountId>> customers = new HashMap<>();
        for (CurrencyCode currency : List.of(USD, EUR)) {
            AccountId bank = ledger.bank(currency);
            banks.put(currency, bank);
            simulation.expected.put(bank, 0L);
            List<AccountId> accounts = new ArrayList<>();
            for (int i = 0; i < CUSTOMERS_PER_CURRENCY; i++) {
                AccountId customer = ledger.customer(currency);
                accounts.add(customer);
                simulation.expected.put(customer, 0L);
            }
            customers.put(currency, accounts);
        }

        for (int i = 0; i < OPERATIONS; i++) {
            CurrencyCode currency = random.nextBoolean() ? USD : EUR;
            List<AccountId> pool = customers.get(currency);
            if (random.nextInt(10) < 3) {
                AccountId customer = pool.get(random.nextInt(pool.size()));
                long amount = 1 + random.nextInt(100_000);
                ledger.fund(banks.get(currency), customer, Money.of(amount, currency));
                simulation.expected.merge(banks.get(currency), amount, Long::sum);
                simulation.expected.merge(customer, amount, Long::sum);
            } else {
                AccountId from = pool.get(random.nextInt(pool.size()));
                AccountId to = pool.get(random.nextInt(pool.size()));
                if (from.equals(to)) {
                    continue;
                }
                long balance = simulation.expected.get(from);
                // Up to 1.5x what the sender has, so a good share of attempts must be rejected.
                long amount = 1 + (long) random.nextInt((int) Math.min(Integer.MAX_VALUE - 1, balance * 3 / 2 + 1));
                transfer(simulation, from, to, Money.of(amount, currency), balance);
            }
        }
        return simulation;
    }

    private void transfer(Simulation simulation, AccountId from, AccountId to, Money amount, long senderBalance) {
        try {
            ledger.transfer(from, to, amount);
            if (amount.minorUnits() > senderBalance) {
                fail("an overdraft of %s from %s was accepted (seed %d)", amount, from, seed);
            }
            simulation.expected.merge(from, -amount.minorUnits(), Long::sum);
            simulation.expected.merge(to, amount.minorUnits(), Long::sum);
        } catch (InsufficientFundsException e) {
            assertThat(amount.minorUnits())
                    .as("a valid transfer of %s from %s was rejected (seed %d)", amount, from, seed)
                    .isGreaterThan(senderBalance);
            simulation.rejectedOverdrafts++;
        }
    }

    private static final class Simulation {
        final Map<AccountId, Long> expected = new HashMap<>();
        int rejectedOverdrafts;
    }
}
