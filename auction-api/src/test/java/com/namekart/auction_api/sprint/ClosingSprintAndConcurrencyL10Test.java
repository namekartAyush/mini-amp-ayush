package com.namekart.auction_api.sprint;

import com.namekart.auction_api.sprint.model.SprintBudget;
import com.namekart.auction_api.sprint.repository.SprintBudgetRepository;
import com.namekart.auction_api.sprint.service.AtomicBudgetService;
import com.namekart.auction_api.sprint.service.PerAuctionCounterService;
import com.namekart.auction_api.sprint.service.PlainBudgetService;
import com.namekart.auction_api.sprint.service.SynchronizedBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class ClosingSprintAndConcurrencyL10Test {

    private static final Logger log = LoggerFactory.getLogger(ClosingSprintAndConcurrencyL10Test.class);

    @Autowired
    private PlainBudgetService plainBudgetService;

    @Autowired
    private SynchronizedBudgetService synchronizedBudgetService;

    @Autowired
    private AtomicBudgetService atomicBudgetService;

    @Autowired
    private PerAuctionCounterService perAuctionCounterService;

    @Autowired
    private SprintBudgetRepository sprintBudgetRepository;

    @Autowired
    private com.namekart.auction_api.sprint.service.ClosingSprintService closingSprintService;

    @Autowired
    private com.namekart.auction_api.auction.repository.AuctionRepository auctionRepository;

    @Autowired
    private com.namekart.auction_api.domain.repository.DomainRepository domainRepository;

    @Autowired
    private com.namekart.auction_api.bid.repository.BidRepository bidRepository;

    @BeforeEach
    void setUp() {
        bidRepository.deleteAll();
        auctionRepository.deleteAll();
        domainRepository.deleteAll();
        sprintBudgetRepository.deleteAll();
    }

    /**
     * L10 Step 1: Plain long field on singleton service.
     * 50 concurrent bids against a budget that allows 20.
     * Repeated across iterations: Count how often budget is overspent!
     */
    @Test
    @DisplayName("Step 1: Plain long singleton field suffers check-then-act race and overspends budget")
    void step1_plainLongSuffersRaceConditionAndOverspends() throws InterruptedException {
        int iterations = 1000;
        int overspendCount = 0;
        int totalExcessBids = 0;

        for (int i = 0; i < iterations; i++) {
            plainBudgetService.init(20); // Allows exactly 20 bids of $1
            int threads = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch readyLatch = new CountDownLatch(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successfulBids = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                    } catch (InterruptedException ignored) {}

                    if (plainBudgetService.allocateBudget(1)) {
                        successfulBids.incrementAndGet();
                    }
                });
            }

            readyLatch.await();
            startLatch.countDown();
            pool.shutdown();
            pool.awaitTermination(3, TimeUnit.SECONDS);

            if (successfulBids.get() > 20) {
                overspendCount++;
                totalExcessBids += (successfulBids.get() - 20);
            }
        }

        System.out.println("==================================================");
        System.out.println("L10 STEP 1 · PLAIN LONG RACE RESULTS:");
        System.out.println("Iterations run: " + iterations);
        System.out.println("Overspend occurrences: " + overspendCount + " / " + iterations + " (" + (overspendCount * 100.0 / iterations) + "%)");
        System.out.println("Total excess bids permitted: " + totalExcessBids);
        System.out.println("==================================================");

        assertThat(overspendCount)
                .as("Plain long field without synchronization MUST exhibit race condition overspends")
                .isGreaterThan(0);
    }

    /**
     * L10 Step 2: Fix with synchronized. Measure throughput and verify 0 overspends.
     */
    @Test
    @DisplayName("Step 2: Synchronized fix eliminates overspends; throughput measured")
    void step2_synchronizedFixEliminatesOverspends() throws InterruptedException {
        int iterations = 500;
        int overspendCount = 0;
        long startNanos = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            synchronizedBudgetService.init(20);
            int threads = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successfulBids = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException ignored) {}

                    if (synchronizedBudgetService.allocateBudget(1)) {
                        successfulBids.incrementAndGet();
                    }
                });
            }

            startLatch.countDown();
            pool.shutdown();
            pool.awaitTermination(3, TimeUnit.SECONDS);

            if (successfulBids.get() > 20) {
                overspendCount++;
            }
            assertThat(successfulBids.get()).isLessThanOrEqualTo(20);
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        double throughput = (iterations * 50.0) / (elapsedMs / 1000.0);

        System.out.println("==================================================");
        System.out.println("L10 STEP 2 · SYNCHRONIZED FIX RESULTS:");
        System.out.println("Overspend count: " + overspendCount + " / " + iterations + " (0% overspend)");
        System.out.println("Wall time: " + elapsedMs + " ms");
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " operations/sec");
        System.out.println("==================================================");

        assertThat(overspendCount).isEqualTo(0);
    }

    /**
     * L10 Step 3: Fix with AtomicLong CAS loop. Measure throughput and verify 0 overspends.
     */
    @Test
    @DisplayName("Step 3: AtomicLong CAS loop eliminates overspends with high throughput")
    void step3_atomicLongCasLoopEliminatesOverspends() throws InterruptedException {
        int iterations = 500;
        int overspendCount = 0;
        long startNanos = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            atomicBudgetService.init(20);
            int threads = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successfulBids = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException ignored) {}

                    if (atomicBudgetService.allocateBudget(1)) {
                        successfulBids.incrementAndGet();
                    }
                });
            }

            startLatch.countDown();
            pool.shutdown();
            pool.awaitTermination(3, TimeUnit.SECONDS);

            if (successfulBids.get() > 20) {
                overspendCount++;
            }
            assertThat(successfulBids.get()).isLessThanOrEqualTo(20);
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        double throughput = (iterations * 50.0) / (elapsedMs / 1000.0);

        System.out.println("==================================================");
        System.out.println("L10 STEP 3 · ATOMIC CAS FIX RESULTS:");
        System.out.println("Overspend count: " + overspendCount + " / " + iterations + " (0% overspend)");
        System.out.println("Wall time: " + elapsedMs + " ms");
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " operations/sec");
        System.out.println("==================================================");

        assertThat(overspendCount).isEqualTo(0);
    }

    /**
     * L10 Step 4: Check-then-act bug on ConcurrentHashMap per-auction bid counts.
     * Demonstrates lost updates with containsKey + put, and fixes with merge / compute.
     */
    @Test
    @DisplayName("Step 4: ConcurrentHashMap check-then-act bug loses updates vs atomic merge/compute")
    void step4_concurrentHashMapCheckThenActBugAndFix() throws InterruptedException {
        perAuctionCounterService.clear();
        Long auctionId = 1001L;
        int threads = 50;
        int incrementsPerThread = 20;
        int expectedTotal = threads * incrementsPerThread; // 1,000 increments

        // 1. Run flawed check-then-act
        ExecutorService flawedPool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch1 = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            flawedPool.submit(() -> {
                try {
                    startLatch1.await();
                } catch (InterruptedException ignored) {}
                for (int i = 0; i < incrementsPerThread; i++) {
                    perAuctionCounterService.incrementFlawed(auctionId);
                }
            });
        }
        startLatch1.countDown();
        flawedPool.shutdown();
        flawedPool.awaitTermination(5, TimeUnit.SECONDS);
        int flawedFinalCount = perAuctionCounterService.getFlawedCount(auctionId);

        // 2. Run fixed atomic merge
        ExecutorService fixedPool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch2 = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            fixedPool.submit(() -> {
                try {
                    startLatch2.await();
                } catch (InterruptedException ignored) {}
                for (int i = 0; i < incrementsPerThread; i++) {
                    perAuctionCounterService.incrementFixedWithMerge(auctionId);
                }
            });
        }
        startLatch2.countDown();
        fixedPool.shutdown();
        fixedPool.awaitTermination(5, TimeUnit.SECONDS);
        int fixedFinalCount = perAuctionCounterService.getFixedCount(auctionId);

        System.out.println("==================================================");
        System.out.println("L10 STEP 4 · CONCURRENTHASHMAP CHECK-THEN-ACT RESULTS:");
        System.out.println("Expected total increments: " + expectedTotal);
        System.out.println("Flawed count (containsKey + put): " + flawedFinalCount + " (Lost updates: " + (expectedTotal - flawedFinalCount) + ")");
        System.out.println("Fixed count (merge): " + fixedFinalCount + " (Lost updates: 0)");
        System.out.println("==================================================");

        assertThat(flawedFinalCount)
                .as("Flawed check-then-act on ConcurrentHashMap MUST suffer lost updates")
                .isLessThan(expectedTotal);
        assertThat(fixedFinalCount)
                .as("Atomic merge on ConcurrentHashMap MUST preserve exact counts without data loss")
                .isEqualTo(expectedTotal);
    }

    /**
     * L10 Step 5: Run sprint across 3 executor topologies:
     * - Cached thread pool
     * - Fixed pool of 10
     * - Virtual threads (Project Loom)
     * Record wall time and peak thread count for each.
     */
    @Test
    @DisplayName("Step 5: Compare Cached Thread Pool vs Fixed(10) vs Virtual Threads")
    void step5_compareExecutorTopologies() throws InterruptedException {
        int taskCount = 100;
        int simulatedIoDelayMs = 50;

        // A. Cached Thread Pool
        TopologyResult cachedResult = benchmarkExecutor("CachedThreadPool", Executors.newCachedThreadPool(), taskCount, simulatedIoDelayMs);

        // B. Fixed Thread Pool (10)
        TopologyResult fixedResult = benchmarkExecutor("FixedThreadPool(10)", Executors.newFixedThreadPool(10), taskCount, simulatedIoDelayMs);

        // C. Virtual Threads (Project Loom)
        TopologyResult virtualResult = benchmarkExecutor("VirtualThreads", Executors.newVirtualThreadPerTaskExecutor(), taskCount, simulatedIoDelayMs);

        System.out.println("==================================================");
        System.out.println("L10 STEP 5 · EXECUTOR TOPOLOGY BENCHMARK (100 tasks @ 50ms simulated I/O):");
        System.out.printf("| %-22s | %-12s | %-16s |\n", "Executor Type", "Wall Time", "Peak Unique Threads");
        System.out.println("|------------------------|--------------|---------------------|");
        System.out.printf("| %-22s | %-10d ms | %-19d |\n", cachedResult.name(), cachedResult.wallTimeMs(), cachedResult.peakThreadCount());
        System.out.printf("| %-22s | %-10d ms | %-19d |\n", fixedResult.name(), fixedResult.wallTimeMs(), fixedResult.peakThreadCount());
        System.out.printf("| %-22s | %-10d ms | %-19d |\n", virtualResult.name(), virtualResult.wallTimeMs(), virtualResult.peakThreadCount());
        System.out.println("==================================================");

        // Fixed pool of 10 will serialize tasks in chunks of 10 -> at least ~500ms
        assertThat(fixedResult.wallTimeMs()).isGreaterThan(400);
        // Virtual threads and cached pool complete in ~1-2 cycles (~50-150ms)
        assertThat(virtualResult.wallTimeMs()).isLessThan(300);
        assertThat(fixedResult.peakThreadCount()).isLessThanOrEqualTo(10);
    }

    private record TopologyResult(String name, long wallTimeMs, int peakThreadCount) {}

    private TopologyResult benchmarkExecutor(String name, ExecutorService executor, int taskCount, int delayMs) throws InterruptedException {
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(taskCount);
        long start = System.currentTimeMillis();

        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                threadNames.add(Thread.currentThread().getName());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {}
                latch.countDown();
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        long wallTime = System.currentTimeMillis() - start;
        executor.shutdown();
        return new TopologyResult(name, wallTime, threadNames.size());
    }

    /**
     * L10 Step 6: CompletableFuture.supplyAsync WITHOUT an executor.
     * Find out which threads ran the work (ForkJoinPool.commonPool-worker-*).
     * Make one bid throw and demonstrate where the exception went (swallowed unless handled/joined).
     */
    @Test
    @DisplayName("Step 6: supplyAsync without executor uses ForkJoinPool; unjoined exception is silently swallowed")
    void step6_supplyAsyncDefaultExecutorAndSwallowedException() throws ExecutionException, InterruptedException {
        // 1. Thread inspection
        CompletableFuture<String> futureThread = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName());
        String executingThreadName = futureThread.join();

        System.out.println("==================================================");
        System.out.println("L10 STEP 6 · DEFAULT EXECUTOR THREAD:");
        System.out.println("Executing thread: " + executingThreadName);
        System.out.println("==================================================");

        assertThat(executingThreadName).contains("ForkJoinPool.commonPool-worker");

        // 2. Swallowed exception demonstration:
        // When a CompletableFuture throws an exception and caller DOES NOT call join() / get(),
        // the exception is completely swallowed and silent (no stack trace to stderr).
        CompletableFuture<String> failingFuture = CompletableFuture.supplyAsync(() -> {
            throw new IllegalStateException("Simulated bid registrar failure!");
        });

        // The background thread threw, but no exception was raised in this thread!
        Thread.sleep(100);
        assertThat(failingFuture.isCompletedExceptionally()).isTrue();

        // The exception only materializes when joined or handled:
        List<Throwable> caughtExceptions = new ArrayList<>();
        failingFuture.handle((res, ex) -> {
            if (ex != null) {
                caughtExceptions.add(ex);
            }
            return null;
        }).join();

        assertThat(caughtExceptions).hasSize(1);
        assertThat(caughtExceptions.get(0)).isInstanceOf(CompletionException.class);
        assertThat(caughtExceptions.get(0).getCause()).isInstanceOf(IllegalStateException.class);
        System.out.println("Exception safely intercepted via handle(): " + caughtExceptions.get(0).getCause().getMessage());
    }

    /**
     * L10 Step 7: orTimeout fast failure.
     * Prevents a slow registrar call from hanging the sprint.
     */
    @Test
    @DisplayName("Step 7: orTimeout fails fast when external call exceeds deadline")
    void step7_orTimeoutPreventsHangs() {
        long start = System.currentTimeMillis();

        CompletableFuture<String> slowCall = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(2000); // 2 second slow registrar response
            } catch (InterruptedException ignored) {}
            return "SUCCESS";
        }).orTimeout(200, TimeUnit.MILLISECONDS); // Timeout after 200ms

        List<Throwable> captured = new ArrayList<>();
        slowCall.exceptionally(ex -> {
            captured.add(ex);
            return "FALLBACK_TIMEOUT";
        }).join();

        long duration = System.currentTimeMillis() - start;

        System.out.println("==================================================");
        System.out.println("L10 STEP 7 · orTimeout RESULTS:");
        System.out.println("Execution finished in: " + duration + " ms (Target: ~200ms, not 2,000ms!)");
        Throwable root = captured.get(0).getCause() != null ? captured.get(0).getCause() : captured.get(0);
        System.out.println("Captured exception: " + captured.get(0).getClass().getName() + " -> root: " + root.getClass().getName());
        System.out.println("==================================================");

        assertThat(duration).isLessThan(800);
        assertThat(root).isInstanceOf(TimeoutException.class);
    }

    /**
     * L10 Step 8: Multi-instance failure & Database atomic fix.
     * Starts two simulated instances sharing the same budget.
     * In-memory fix fails (each instance uses its own AtomicLong, doubling the budget spend).
     * Fixed at database with atomic conditional update (UPDATE ... WHERE remaining_cents >= :amount).
     */
    @Test
    @DisplayName("Step 8: Two instances break in-memory fix, but database conditional update holds strictly")
    void step8_twoInstanceFailureAndDatabaseAtomicFix() throws InterruptedException {
        String budgetCode = "SPRINT-SHARED-2026";
        long totalBudgetCents = 20_00L; // $20.00 total (allows 20 bids of $1.00)
        sprintBudgetRepository.save(new SprintBudget(budgetCode, totalBudgetCents, totalBudgetCents));

        // Part A: Demonstrate failure of in-JVM fix across two instances
        // Instance A has its own AtomicBudgetService; Instance B has its own AtomicBudgetService.
        AtomicBudgetService instanceABudget = new AtomicBudgetService();
        instanceABudget.init(20);
        AtomicBudgetService instanceBBudget = new AtomicBudgetService();
        instanceBBudget.init(20);

        // Instance A executes 20 bids; Instance B executes 20 bids
        int instanceABids = 0;
        for (int i = 0; i < 25; i++) {
            if (instanceABudget.allocateBudget(1)) instanceABids++;
        }
        int instanceBBids = 0;
        for (int i = 0; i < 25; i++) {
            if (instanceBBudget.allocateBudget(1)) instanceBBids++;
        }
        int totalInMemoryBids = instanceABids + instanceBBids;

        System.out.println("==================================================");
        System.out.println("L10 STEP 8 · TWO-INSTANCE IN-MEMORY FAILURE:");
        System.out.println("Allowed total budget bids: 20");
        System.out.println("Instance A accepted bids: " + instanceABids);
        System.out.println("Instance B accepted bids: " + instanceBBids);
        System.out.println("TOTAL ACCEPTED BIDS ACROSS INSTANCES: " + totalInMemoryBids + " (OVERSPENT BY " + (totalInMemoryBids - 20) + " BIDS!)");
        System.out.println("==================================================");

        assertThat(totalInMemoryBids)
                .as("In-JVM fix CANNOT coordinate across separate instances: both spend full budget!")
                .isEqualTo(40);

        // Part B: Database-level atomic conditional update across 2 concurrent instances
        int threadsPerInstance = 25; // 50 concurrent threads total across instances A and B
        ExecutorService multiInstancePool = Executors.newFixedThreadPool(threadsPerInstance * 2);
        CountDownLatch readyLatch = new CountDownLatch(threadsPerInstance * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successfulDbBids = new AtomicInteger(0);

        for (int t = 0; t < threadsPerInstance * 2; t++) {
            multiInstancePool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException ignored) {}

                try {
                    // Atomic conditional database deduction
                    int rowsUpdated = sprintBudgetRepository.deductBudgetConditional(budgetCode, 1_00L);
                    if (rowsUpdated > 0) {
                        successfulDbBids.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Error executing deductBudgetConditional in test thread: ", e);
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        multiInstancePool.shutdown();
        multiInstancePool.awaitTermination(5, TimeUnit.SECONDS);

        SprintBudget finalBudget = sprintBudgetRepository.findByBudgetCode(budgetCode).orElseThrow();

        System.out.println("==================================================");
        System.out.println("L10 STEP 8 · DATABASE ATOMIC CONDITIONAL UPDATE RESULTS:");
        System.out.println("Total concurrent bid attempts across instances: 50");
        System.out.println("Successful database bids: " + successfulDbBids.get());
        System.out.println("Final remaining cents in DB: " + finalBudget.getRemainingCents());
        System.out.println("==================================================");

        assertThat(successfulDbBids.get())
                .as("Database conditional update must strictly limit total successful bids to budget (20)")
                .isEqualTo(20);
        assertThat(finalBudget.getRemainingCents())
                .as("Remaining budget in database must never drop below 0")
                .isEqualTo(0L);
    }

    /**
     * P7 / L10 Step 9: Closing Sprint End-to-End Execution.
     * 5 auctions ending in 30 seconds.
     * Shared budget allows exactly 3 bids.
     * Proves:
     * - CompletableFuture executes bids concurrently on Virtual Threads.
     * - Shared budget is strictly enforced (3 accepted, 2 rejected as BUDGET_EXHAUSTED).
     * - Budget is never exceeded.
     */
    @Test
    @DisplayName("Step 9: Closing sprint places concurrent bids within shared budget without overspend")
    void step9_closingSprintEndToEndWithAuctions() {
        String budgetCode = "SPRINT-CLOSING-AUCTIONS";
        long totalBudgetCents = 30_00L; // $30.00 allows exactly 3 bids of $10.00
        sprintBudgetRepository.save(new SprintBudget(budgetCode, totalBudgetCents, totalBudgetCents));

        java.time.Instant now = java.time.Instant.now();
        java.time.Instant endingSoon = now.plusSeconds(30);

        List<com.namekart.auction_api.auction.model.Auction> seededAuctions = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            com.namekart.auction_api.domain.model.Domain domain = domainRepository.save(new com.namekart.auction_api.domain.model.Domain(
                    "sprint-target-" + i + ".com",
                    "com",
                    java.math.BigDecimal.valueOf(100.00),
                    com.namekart.auction_api.domain.model.DomainStatus.AUCTION
            ));

            com.namekart.auction_api.auction.model.Auction auction = auctionRepository.save(new com.namekart.auction_api.auction.model.Auction(
                    domain,
                    java.math.BigDecimal.valueOf(50.00),
                    java.math.BigDecimal.valueOf(100.00),
                    now.minusSeconds(3600),
                    endingSoon,
                    com.namekart.auction_api.auction.model.AuctionStatus.ACTIVE
            ));
            seededAuctions.add(auction);
        }

        // Run closing sprint concurrently on Virtual Threads
        com.namekart.auction_api.sprint.service.ClosingSprintService.SprintSummary summary =
                closingSprintService.executeClosingSprint(
                        budgetCode,
                        java.math.BigDecimal.valueOf(10.00),
                        Executors.newVirtualThreadPerTaskExecutor(),
                        java.time.Duration.ofSeconds(2),
                        true
                );

        SprintBudget finalBudget = sprintBudgetRepository.findByBudgetCode(budgetCode).orElseThrow();

        System.out.println("==================================================");
        System.out.println("P7 / L10 STEP 9 · CLOSING SPRINT END-TO-END RESULTS:");
        System.out.println("Total auctions ending in next minute: " + summary.totalAttempted());
        System.out.println("Successful sprint bids: " + summary.successfulBids());
        System.out.println("Budget exhausted rejections: " + summary.budgetExhaustedBids());
        System.out.println("Timeouts: " + summary.timeoutBids());
        System.out.println("Sprint duration: " + summary.durationMs() + " ms");
        System.out.println("Executing threads: " + summary.threadNames());
        System.out.println("Final remaining budget in DB: " + finalBudget.getRemainingCents() + " cents");
        System.out.println("==================================================");

        assertThat(summary.totalAttempted()).isEqualTo(5);
        assertThat(summary.successfulBids()).isEqualTo(3);
        assertThat(summary.budgetExhaustedBids()).isEqualTo(2);
        assertThat(summary.failedBids()).isEqualTo(0);
        assertThat(finalBudget.getRemainingCents()).isEqualTo(0L);
    }
}

