package com.namekart.auction_api.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLTransientConnectionException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPoolExhaustionAndSizingTest {

    private static final String H2_URL = "jdbc:h2:mem:pooltest;DB_CLOSE_DELAY=-1";
    private static final int CONCURRENCY = 20;
    private static final int ROUNDS = 10;
    private static final int TOTAL_REQUESTS = CONCURRENCY * ROUNDS; // 200 requests

    private HikariDataSource createDataSource(int poolSize, long timeoutMs) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(H2_URL);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(poolSize);
        config.setConnectionTimeout(timeoutMs);
        config.setPoolName("BenchmarkPool-" + poolSize);
        return new HikariDataSource(config);
    }

    private BenchmarkResult runLoadBenchmark(HikariDataSource ds, int concurrency, int rounds, long simulatedWorkMs, boolean workInsideConnection) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        long benchmarkStartTime = System.currentTimeMillis();

        for (int r = 0; r < rounds; r++) {
            CountDownLatch latch = new CountDownLatch(concurrency);
            for (int c = 0; c < concurrency; c++) {
                executor.submit(() -> {
                    long start = System.nanoTime();
                    try {
                        if (workInsideConnection) {
                            // ANTIPATTERN: Connection held during work/sleep
                            try (Connection conn = ds.getConnection()) {
                                try (PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
                                    try (ResultSet rs = ps.executeQuery()) {
                                        rs.next();
                                    }
                                }
                                if (simulatedWorkMs > 0) {
                                    Thread.sleep(simulatedWorkMs);
                                }
                            }
                        } else {
                            // BEST PRACTICE: Work outside connection
                            try (Connection conn = ds.getConnection()) {
                                try (PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
                                    try (ResultSet rs = ps.executeQuery()) {
                                        rs.next();
                                    }
                                }
                            }
                            if (simulatedWorkMs > 0) {
                                Thread.sleep(simulatedWorkMs);
                            }
                        }
                        long durationMs = (System.nanoTime() - start) / 1_000_000;
                        latencies.add(durationMs);
                        successCount.incrementAndGet();
                    } catch (Exception ex) {
                        long durationMs = (System.nanoTime() - start) / 1_000_000;
                        latencies.add(durationMs);
                        failureCount.incrementAndGet();
                        errors.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(30, TimeUnit.SECONDS);
        }

        long totalDurationMs = System.currentTimeMillis() - benchmarkStartTime;
        executor.shutdown();

        Collections.sort(latencies);
        long min = latencies.isEmpty() ? 0 : latencies.get(0);
        long max = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        long p50 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.50));
        long p95 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99));
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double throughput = (successCount.get() * 1000.0) / totalDurationMs;

        return new BenchmarkResult(
                ds.getMaximumPoolSize(),
                totalDurationMs,
                successCount.get(),
                failureCount.get(),
                min, p50, p95, p99, max, avg, throughput,
                errors
        );
    }

    record BenchmarkResult(
            int poolSize,
            long totalWallTimeMs,
            int successCount,
            int failureCount,
            long minMs,
            long p50Ms,
            long p95Ms,
            long p99Ms,
            long maxMs,
            double avgMs,
            double throughputRps,
            List<String> errors
    ) {}

    @Test
    @DisplayName("L4 Steps 1-4: Benchmark pool sizes (2, 10, 50) under 20 concurrent requests across 10 rounds")
    void testPoolSizeLatencyComparison() throws Exception {
        System.out.println("=========================================================================================");
        System.out.println("L4 · HIKARICP POOL EXHAUSTION & SIZING BENCHMARK (200 requests @ 20 concurrency)");
        System.out.println("=========================================================================================");

        // Run Pool Size 2 (Timeout: 5,000ms = 5s)
        BenchmarkResult r2;
        try (HikariDataSource ds2 = createDataSource(2, 5000)) {
            r2 = runLoadBenchmark(ds2, CONCURRENCY, ROUNDS, 0, true);
        }

        // Run Pool Size 10 (Timeout: 5,000ms)
        BenchmarkResult r10;
        try (HikariDataSource ds10 = createDataSource(10, 5000)) {
            r10 = runLoadBenchmark(ds10, CONCURRENCY, ROUNDS, 0, true);
        }

        // Run Pool Size 50 (Timeout: 5,000ms)
        BenchmarkResult r50;
        try (HikariDataSource ds50 = createDataSource(50, 5000)) {
            r50 = runLoadBenchmark(ds50, CONCURRENCY, ROUNDS, 0, true);
        }

        printResultTable("Fast Database Read Query (0ms simulated delay)", List.of(r2, r10, r50));

        assertThat(r2.successCount()).isEqualTo(TOTAL_REQUESTS);
        assertThat(r10.successCount()).isEqualTo(TOTAL_REQUESTS);
        assertThat(r50.successCount()).isEqualTo(TOTAL_REQUESTS);
    }

    @Test
    @DisplayName("L4 Step 5: Compare 300ms sleep INSIDE transaction vs OUTSIDE transaction at pool size 10")
    void testSleepInsideVsOutsideTransaction() throws Exception {
        System.out.println("=========================================================================================");
        System.out.println("L4 Step 5 · 300ms Sleep INSIDE Transaction vs OUTSIDE Transaction (Pool Size 10)");
        System.out.println("=========================================================================================");

        // 300ms Sleep INSIDE connection / transaction (holds connection for 300ms)
        BenchmarkResult rInside;
        try (HikariDataSource ds = createDataSource(10, 5000)) {
            rInside = runLoadBenchmark(ds, CONCURRENCY, 3, 300, true);
        }

        // 300ms Sleep OUTSIDE connection / transaction (releases connection immediately)
        BenchmarkResult rOutside;
        try (HikariDataSource ds = createDataSource(10, 5000)) {
            rOutside = runLoadBenchmark(ds, CONCURRENCY, 3, 300, false);
        }

        System.out.println("\n-----------------------------------------------------------------------------------------");
        System.out.printf("| %-25s | Wall Time  | p50 Latency | p95 Latency | Throughput |\n", "Work Location (Pool 10)");
        System.out.println("-----------------------------------------------------------------------------------------");
        System.out.printf("| %-25s | %7d ms | %9d ms | %9d ms | %7.1f rps |\n",
                "INSIDE Transaction (Held)", rInside.totalWallTimeMs(), rInside.p50Ms(), rInside.p95Ms(), rInside.throughputRps());
        System.out.printf("| %-25s | %7d ms | %9d ms | %9d ms | %7.1f rps |\n",
                "OUTSIDE Transaction (Free)", rOutside.totalWallTimeMs(), rOutside.p50Ms(), rOutside.p95Ms(), rOutside.throughputRps());
        System.out.println("-----------------------------------------------------------------------------------------");

        // Outside transaction must achieve substantially higher throughput and lower wait times
        assertThat(rOutside.totalWallTimeMs()).isLessThanOrEqualTo(rInside.totalWallTimeMs());
    }

    @Test
    @DisplayName("L4 Step 1 Error Observation: Provoke pool exhaustion and capture exact timeout error")
    void testPoolExhaustionExactErrorMessage() throws Exception {
        // Pool size = 1, timeout = 250ms (HikariCP minimum)
        HikariDataSource smallPool = createDataSource(1, 250);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch acquiredLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> capturedErrors = Collections.synchronizedList(new ArrayList<>());

        // Thread 1: Acquire the only connection and hold it
        executor.submit(() -> {
            try (Connection conn = smallPool.getConnection()) {
                acquiredLatch.countDown();
                Thread.sleep(800); // Hold for 800ms
            } catch (Exception ignored) {}
        });

        acquiredLatch.await(5, TimeUnit.SECONDS);

        // Thread 2: Try to acquire connection while pool is exhausted -> times out after 250ms
        executor.submit(() -> {
            try (Connection conn = smallPool.getConnection()) {
                // Should not reach here
            } catch (Exception ex) {
                failureCount.incrementAndGet();
                capturedErrors.add(ex.getClass().getName() + ": " + ex.getMessage());
                System.out.println("==================================================");
                System.out.println("CAPTURED EXACT POOL EXHAUSTION ERROR:");
                System.out.println(ex.getClass().getName() + ": " + ex.getMessage());
                System.out.println("==================================================");
            } finally {
                doneLatch.countDown();
            }
        });

        doneLatch.await(5, TimeUnit.SECONDS);
        smallPool.close();
        executor.shutdown();

        assertThat(failureCount.get()).isEqualTo(1);
        assertThat(capturedErrors.get(0)).contains("Connection is not available, request timed out after");
    }

    private void printResultTable(String title, List<BenchmarkResult> results) {
        System.out.println("\nBENCHMARK RESULTS: " + title);
        System.out.println("-----------------------------------------------------------------------------------------------------------");
        System.out.printf("| Pool Size | Total Time | Success | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Throughput  |\n");
        System.out.println("-----------------------------------------------------------------------------------------------------------");
        for (BenchmarkResult r : results) {
            System.out.printf("| %9d | %8d ms | %7d | %8d | %8d | %8d | %8d | %8d | %7.1f rps |\n",
                    r.poolSize(), r.totalWallTimeMs(), r.successCount(),
                    r.minMs(), r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs(), r.throughputRps());
        }
        System.out.println("-----------------------------------------------------------------------------------------------------------");
    }
}
