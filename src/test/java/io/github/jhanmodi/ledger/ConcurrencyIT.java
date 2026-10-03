package io.github.jhanmodi.ledger;

import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.ledger.AccountBalance;
import io.github.jhanmodi.ledger.ledger.AccountBusyException;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.InsufficientFundsException;
import io.github.jhanmodi.ledger.ledger.InvariantChecker;
import io.github.jhanmodi.ledger.ledger.InvariantReport;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionType;
import io.github.jhanmodi.ledger.ledger.NewEntry;
import io.github.jhanmodi.ledger.ledger.PostingRequest;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.money.Money;
import io.github.jhanmodi.ledger.transfers.DuplicateRequestException;
import io.github.jhanmodi.ledger.transfers.Funding;
import io.github.jhanmodi.ledger.transfers.FundingCommand;
import io.github.jhanmodi.ledger.transfers.FundingService;
import io.github.jhanmodi.ledger.transfers.IdempotencyKey;
import io.github.jhanmodi.ledger.transfers.Transfer;
import io.github.jhanmodi.ledger.transfers.TransferCommand;
import io.github.jhanmodi.ledger.transfers.TransferService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The concurrency suite (M5, ADR-0005, ADR-0022): many requests at once against a few busy accounts, with the invariant
 * checker running while they do. Nothing here depends on timing. The tests check what must hold whatever order the
 * requests happen to run in.
 *
 * <p>The seed is in every failure message; rerun with {@code -DargLine=-Dconcurrency.seed=<seed>} to send the same
 * requests again. (Which ones run first still depends on the threads, so a failure may need a few runs to repeat.)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyIT {

    private static final int REQUESTS = 1_000;
    private static final int THREADS = 32;
    private static final int ACCOUNTS = 4;
    private static final long INITIAL_BALANCE_CENTS = 50_000;
    private static final long MAX_TRANSFER_CENTS = 50_000;
    private static final long MAX_FUNDING_CENTS = 10_000;
    private static final int MIN_INVARIANT_CHECKS = 3;
    private static final Duration WAIT_LIMIT = Duration.ofMinutes(2);

    /** Every outcome a request may have under contention. Anything else is a bug. */
    private static final Set<Class<?>> ALLOWED_OUTCOMES = Set.of(
            Transfer.class,
            Funding.class,
            InsufficientFundsException.class,
            DuplicateRequestException.class,
            AccountBusyException.class);

    @Autowired
    TransferService transfers;

    @Autowired
    FundingService fundings;

    @Autowired
    PostingService postingService;

    @Autowired
    AccountService accountService;

    @Autowired
    ClientService clientService;

    @Autowired
    LedgerQueries queries;

    @Autowired
    InvariantChecker invariantChecker;

    @Autowired
    OwnerDatabase owner;

    LedgerFixtures ledger;
    long seed;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService, clientService);
        seed = Long.getLong("concurrency.seed", ThreadLocalRandom.current().nextLong());
    }

    /**
     * A thousand transfers and fundings, including overdraws and repeated idempotency keys, released together onto four
     * accounts. Afterwards, every balance must be exactly what the successful requests add up to, every success must
     * have its business row and audit row, and the invariant checker must have been clean every time it looked, during
     * the load and after it.
     */
    @Test
    void aThousandConcurrentRequestsKeepEveryInvariant() throws Exception {
        ClientId client = ledger.client();
        List<AccountId> accounts = new ArrayList<>();
        Map<AccountId, Long> expected = new HashMap<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            AccountId account = ledger.customer(USD);
            accounts.add(account);
            fundings.fund(
                    new FundingCommand(account, usd(INITIAL_BALANCE_CENTS), "INITIAL-" + i, key()), admin(client));
            expected.put(account, INITIAL_BALANCE_CENTS);
        }
        List<Callable<Object>> requests = requests(client, accounts, new Random(seed));

        List<InvariantCheck> checks = Collections.synchronizedList(new ArrayList<>());
        Run run = runAtOnce(requests, () -> checks.add(InvariantCheck.now(invariantChecker)));
        List<Object> outcomes = run.outcomes();

        // The run exercised the paths it's meant to: overdraws refused, and repeated keys refused.
        assertThat(ofType(outcomes, InsufficientFundsException.class))
                .as("seed %d", seed)
                .isNotEmpty();
        assertThat(ofType(outcomes, DuplicateRequestException.class))
                .as("seed %d", seed)
                .isNotEmpty();

        // Every outcome is one the API has an answer for.
        assertThat(outcomes)
                .as("seed %d", seed)
                .allSatisfy(outcome -> assertThat(ALLOWED_OUTCOMES)
                        .as("unexpected outcome %s (seed %d)", outcome, seed)
                        .contains(outcome.getClass()));

        // Each balance is exactly its starting balance plus what the successful requests moved: nothing reported as
        // done is missing, and nothing moved that wasn't reported.
        List<Transfer> moved = ofType(outcomes, Transfer.class);
        List<Funding> funded = ofType(outcomes, Funding.class);
        moved.forEach(transfer -> {
            expected.merge(transfer.source(), -transfer.amount().minorUnits(), Long::sum);
            expected.merge(transfer.destination(), transfer.amount().minorUnits(), Long::sum);
        });
        funded.forEach(
                funding -> expected.merge(funding.account(), funding.amount().minorUnits(), Long::sum));
        for (AccountId account : accounts) {
            AccountBalance balance = queries.balance(account);
            assertThat(balance.posted())
                    .as("account %s (seed %d)", account, seed)
                    .isEqualTo(usd(expected.get(account)));
            assertThat(balance.available().minorUnits()).isNotNegative();
        }

        // Every success has exactly one business row and one audit row, and a key was never applied twice.
        assertThat(rowsFor(client, "transfers")).isEqualTo(moved.size());
        assertThat(rowsFor(client, "fundings")).isEqualTo(funded.size() + ACCOUNTS);
        assertThat(audited(
                        "TRANSFER_CREATED",
                        moved.stream().map(t -> t.id().toString()).toList()))
                .isEqualTo(moved.size());
        assertThat(audited(
                        "FUNDING_CREATED",
                        funded.stream().map(f -> f.id().toString()).toList()))
                .isEqualTo(funded.size());

        // The checker saw one consistent snapshot each time, while postings were committing all around it.
        assertThat(checks).hasSizeGreaterThanOrEqualTo(MIN_INVARIANT_CHECKS);
        assertThat(checks)
                .allSatisfy(check -> assertThat(check.report().isClean())
                        .as("%s (seed %d)", check.report(), seed)
                        .isTrue());
        long duringTheLoad = checks.stream()
                .filter(check -> check.startedNanos() < run.finishedNanos())
                .count();
        assertThat(duringTheLoad)
                .as("checks that started while requests were in flight")
                .isPositive();
        assertThat(invariantChecker.check().isClean()).isTrue();

        System.out.printf(
                "ConcurrencyIT: %d requests on %d threads in %d ms: %s; %d invariant checks during the load (seed %d)%n",
                REQUESTS, THREADS, run.took().toMillis(), countByOutcome(outcomes), duringTheLoad, seed);
    }

    /**
     * Postings whose entries name the same three accounts in every possible order, at once, straight through the posting
     * service: no retry layer, so a deadlock would surface as an error here instead of being retried away. Locking in
     * ascending id order means none can happen, so every posting must succeed.
     */
    @Test
    void postingsThatNameTheSameAccountsInEveryOrderNeverDeadlock() throws Exception {
        List<AccountId> accounts = List.of(ledger.customer(USD), ledger.customer(USD), ledger.customer(USD));
        AccountId bank = ledger.bank(USD);
        accounts.forEach(account -> ledger.fund(bank, account, usd(1_000_000)));
        Random random = new Random(seed);

        List<Callable<Object>> postings = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            List<AccountId> order = new ArrayList<>(accounts);
            Collections.shuffle(order, random);
            // One account pays 2 cents, split between the other two; the entries are listed in the shuffled order.
            List<NewEntry> entries = new ArrayList<>(List.of(
                    NewEntry.debit(order.get(0), usd(2)),
                    NewEntry.credit(order.get(1), usd(1)),
                    NewEntry.credit(order.get(2), usd(1))));
            Collections.shuffle(entries, random);
            PostingRequest request = new PostingRequest(LedgerTransactionType.TRANSFER, null, entries);
            postings.add(() -> postingService.post(request));
        }

        List<Object> outcomes = runAtOnce(postings, () -> {}).outcomes();

        assertThat(outcomes)
                .as("seed %d", seed)
                .allSatisfy(outcome -> assertThat(outcome)
                        .as("every posting succeeds, with no deadlock (seed %d)", seed)
                        .isNotInstanceOf(Throwable.class));
        long total = accounts.stream()
                .mapToLong(account -> queries.balance(account).posted().minorUnits())
                .sum();
        assertThat(total).as("money only moved between the three").isEqualTo(3 * 1_000_000);
        assertThat(invariantChecker.check().isClean()).isTrue();
    }

    // --- building the requests ---

    /**
     * Seven in ten are transfers between two different accounts, of up to 500.00, so many overdraw. Two in ten are
     * fundings. One in ten repeats an earlier request with the same idempotency key, as a client retrying would.
     */
    private List<Callable<Object>> requests(ClientId client, List<AccountId> accounts, Random random) {
        List<Callable<Object>> requests = new ArrayList<>();
        List<Object> commands = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            int kind = random.nextInt(10);
            Object command;
            if (kind == 0 && !commands.isEmpty()) {
                command = commands.get(random.nextInt(commands.size()));
            } else if (kind <= 2) {
                AccountId account = accounts.get(random.nextInt(ACCOUNTS));
                command = new FundingCommand(
                        account, usd(1 + random.nextLong(MAX_FUNDING_CENTS)), "BANK-REF-" + i, key());
            } else {
                int from = random.nextInt(ACCOUNTS);
                int to = (from + 1 + random.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
                command = new TransferCommand(
                        accounts.get(from),
                        accounts.get(to),
                        usd(1 + random.nextLong(MAX_TRANSFER_CENTS)),
                        null,
                        key());
            }
            commands.add(command);
            // Each request, a repeat included, is a new HTTP request: its own caller and request id. Built here,
            // because the fixtures aren't thread-safe.
            if (command instanceof TransferCommand transfer) {
                Caller caller = ledger.caller(client);
                requests.add(() -> transfers.transfer(transfer, caller));
            } else {
                FundingCommand funding = (FundingCommand) command;
                Caller caller = admin(client);
                requests.add(() -> fundings.fund(funding, caller));
            }
        }
        return requests;
    }

    private Caller admin(ClientId client) {
        return ledger.caller(client, EnumSet.allOf(Scope.class));
    }

    // --- running them ---

    /** Each task's result or exception, in order, and when the tasks were released and when the last one finished. */
    private record Run(List<Object> outcomes, long startedNanos, long finishedNanos) {

        Duration took() {
            return Duration.ofNanos(finishedNanos - startedNanos);
        }
    }

    /**
     * Releases every task at once onto {@value #THREADS} threads. Meanwhile, {@code alongside} runs over and over on its
     * own thread, at least {@value #MIN_INVARIANT_CHECKS} times, until every task has finished.
     */
    private static Run runAtOnce(List<Callable<Object>> tasks, Runnable alongside) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        ExecutorService watcher = Executors.newSingleThreadExecutor();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        try {
            Future<Integer> watching = watcher.submit(() -> {
                start.await();
                int runs = 0;
                while (!finished.get() || runs < MIN_INVARIANT_CHECKS) {
                    alongside.run();
                    runs++;
                }
                return runs;
            });
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            long startedNanos = System.nanoTime();
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get(WAIT_LIMIT.toSeconds(), SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            long finishedNanos = System.nanoTime();
            finished.set(true);
            watching.get(WAIT_LIMIT.toSeconds(), SECONDS);
            return new Run(outcomes, startedNanos, finishedNanos);
        } finally {
            pool.shutdownNow();
            watcher.shutdownNow();
        }
    }

    /** One run of the invariant checker, and when it started. */
    private record InvariantCheck(long startedNanos, InvariantReport report) {

        static InvariantCheck now(InvariantChecker checker) {
            long started = System.nanoTime();
            return new InvariantCheck(started, checker.check());
        }
    }

    // --- reading the results ---

    private static <T> List<T> ofType(List<Object> outcomes, Class<T> type) {
        return outcomes.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static Map<String, Long> countByOutcome(List<Object> outcomes) {
        Map<String, Long> counts = new TreeMap<>();
        outcomes.forEach(outcome -> counts.merge(outcome.getClass().getSimpleName(), 1L, Long::sum));
        return counts;
    }

    private long rowsFor(ClientId client, String table) {
        return owner.jdbc()
                .sql("SELECT count(*) FROM " + table + " WHERE client_id = :clientId")
                .param("clientId", client.value())
                .query(Long.class)
                .single();
    }

    /** How many of these targets have an audit row for this action. Read as the owner: the app can't read the log. */
    private long audited(String action, List<String> targets) {
        if (targets.isEmpty()) {
            return 0;
        }
        return owner.jdbc()
                .sql("SELECT count(*) FROM audit_log WHERE action = :action AND target_id IN (:targets)")
                .param("action", action)
                .param("targets", targets)
                .query(Long.class)
                .single();
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("test-" + UUID.randomUUID());
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
