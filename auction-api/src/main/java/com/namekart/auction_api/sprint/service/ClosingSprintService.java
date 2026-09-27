package com.namekart.auction_api.sprint.service;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.bid.dto.PlaceBidRequest;
import com.namekart.auction_api.bid.service.BidService;
import com.namekart.auction_api.sprint.repository.SprintBudgetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P7 / L10 Closing Sprint Service:
 * Automatically places final bids concurrently on all auctions ending in the next minute,
 * bounded by an executor and a shared total budget.
 */
@Service
public class ClosingSprintService {

    private static final Logger log = LoggerFactory.getLogger(ClosingSprintService.class);

    private final AuctionRepository auctionRepository;
    private final BidService bidService;
    private final SprintBudgetRepository sprintBudgetRepository;
    private final AtomicBudgetService atomicBudgetService;

    public ClosingSprintService(AuctionRepository auctionRepository,
                                BidService bidService,
                                SprintBudgetRepository sprintBudgetRepository,
                                AtomicBudgetService atomicBudgetService) {
        this.auctionRepository = auctionRepository;
        this.bidService = bidService;
        this.sprintBudgetRepository = sprintBudgetRepository;
        this.atomicBudgetService = atomicBudgetService;
    }

    public record SprintBidResult(
            Long auctionId,
            boolean success,
            String reason,
            String threadName,
            long executionTimeMs
    ) {}

    public record SprintSummary(
            int totalAttempted,
            int successfulBids,
            int budgetExhaustedBids,
            int timeoutBids,
            int failedBids,
            long durationMs,
            Set<String> threadNames,
            List<Throwable> capturedExceptions
    ) {}

    /**
     * Executes the closing sprint across all auctions ending in the next minute.
     * Concurrently submits bids using CompletableFuture on the specified executor,
     * protected by orTimeout() to prevent slow registrar calls from hanging the sprint.
     */
    public SprintSummary executeClosingSprint(
            String budgetCode,
            BigDecimal bidIncrement,
            Executor executor,
            Duration callTimeout,
            boolean useDatabaseBudget) {

        long startTime = System.currentTimeMillis();
        Instant now = Instant.now();
        Instant endingSoon = now.plus(1, ChronoUnit.MINUTES);

        List<Auction> activeAuctions = auctionRepository.findByStatusAndEndTimeBetween(AuctionStatus.ACTIVE, now, endingSoon);
        if (activeAuctions.isEmpty()) {
            log.info("No auctions ending in the next minute. Closing sprint completed.");
            return new SprintSummary(0, 0, 0, 0, 0, 0, Collections.emptySet(), Collections.emptyList());
        }

        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        List<Throwable> capturedExceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger budgetExhaustedCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long bidAmountCents = bidIncrement.multiply(BigDecimal.valueOf(100)).longValue();

        List<CompletableFuture<SprintBidResult>> futures = new ArrayList<>();

        for (Auction auction : activeAuctions) {
            CompletableFuture<SprintBidResult> future;

            // Submit asynchronously
            if (executor != null) {
                future = CompletableFuture.supplyAsync(
                        () -> executeSingleSprintBid(auction, bidIncrement, bidAmountCents, budgetCode, useDatabaseBudget, threadNames),
                        executor
                );
            } else {
                // Step 6: CompletableFuture.supplyAsync without executor (runs on ForkJoinPool.commonPool())
                future = CompletableFuture.supplyAsync(
                        () -> executeSingleSprintBid(auction, bidIncrement, bidAmountCents, budgetCode, useDatabaseBudget, threadNames)
                );
            }

            // Step 7: Apply orTimeout so slow calls cannot stall the sprint
            if (callTimeout != null && !callTimeout.isZero()) {
                future = future.orTimeout(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            // Catch and handle exceptions (Step 6 & 7: Where does the exception go?)
            CompletableFuture<SprintBidResult> handledFuture = future.handle((result, ex) -> {
                if (ex != null) {
                    capturedExceptions.add(ex);
                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        timeoutCount.incrementAndGet();
                        log.warn("Sprint bid on auction {} timed out after {}ms", auction.getId(), callTimeout.toMillis());
                        return new SprintBidResult(auction.getId(), false, "TIMEOUT", Thread.currentThread().getName(), 0);
                    } else {
                        failureCount.incrementAndGet();
                        log.error("Sprint bid on auction {} failed with error: {}", auction.getId(), cause.getMessage());
                        return new SprintBidResult(auction.getId(), false, "ERROR: " + cause.getMessage(), Thread.currentThread().getName(), 0);
                    }
                }

                if (result.success()) {
                    successCount.incrementAndGet();
                } else if ("BUDGET_EXHAUSTED".equals(result.reason())) {
                    budgetExhaustedCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }
                return result;
            });

            futures.add(handledFuture);
        }

        // Wait for all sprint bids to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long durationMs = System.currentTimeMillis() - startTime;

        log.info("Closing sprint finished in {}ms: attempted={}, success={}, budgetExhausted={}, timeouts={}, failures={}",
                durationMs, activeAuctions.size(), successCount.get(), budgetExhaustedCount.get(), timeoutCount.get(), failureCount.get());

        return new SprintSummary(
                activeAuctions.size(),
                successCount.get(),
                budgetExhaustedCount.get(),
                timeoutCount.get(),
                failureCount.get(),
                durationMs,
                threadNames,
                capturedExceptions
        );
    }

    private SprintBidResult executeSingleSprintBid(
            Auction auction,
            BigDecimal bidIncrement,
            long bidAmountCents,
            String budgetCode,
            boolean useDatabaseBudget,
            Set<String> threadNames) {

        long start = System.currentTimeMillis();
        threadNames.add(Thread.currentThread().getName());

        // 1. Allocate budget atomically
        boolean budgetAllocated;
        if (useDatabaseBudget) {
            // Step 8: Multi-instance database-level atomic conditional update
            budgetAllocated = sprintBudgetRepository.deductBudgetConditional(budgetCode, bidAmountCents) > 0;
        } else {
            // In-memory atomic budget allocation
            budgetAllocated = atomicBudgetService.allocateBudget(bidAmountCents);
        }

        if (!budgetAllocated) {
            return new SprintBidResult(auction.getId(), false, "BUDGET_EXHAUSTED", Thread.currentThread().getName(), System.currentTimeMillis() - start);
        }

        // 2. Place bid on auction
        try {
            BigDecimal currentPrice = auction.getCurrentHighestBid() != null ? auction.getCurrentHighestBid() : auction.getStartingPrice();
            BigDecimal newBidAmount = currentPrice.add(bidIncrement);

            bidService.placeBid(new PlaceBidRequest(
                    auction.getId(),
                    "sprint-bot@miniamp.internal",
                    newBidAmount
            ));

            return new SprintBidResult(auction.getId(), true, "SUCCESS", Thread.currentThread().getName(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Failed to place bid on auction {}: {}", auction.getId(), e.getMessage());
            return new SprintBidResult(auction.getId(), false, e.getMessage(), Thread.currentThread().getName(), System.currentTimeMillis() - start);
        }
    }
}
