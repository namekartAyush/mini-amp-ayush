package com.namekart.auction_api.sprint.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * L10 Step 4: Demonstrates a classic check-then-act race bug on ConcurrentHashMap,
 * and fixes it using atomic compute / merge operations.
 */
@Service
public class PerAuctionCounterService {

    // Map storing total placed bids per auctionId
    private final ConcurrentHashMap<Long, Integer> flawedCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> fixedCounts = new ConcurrentHashMap<>();

    public void clear() {
        flawedCounts.clear();
        fixedCounts.clear();
    }

    /**
     * BOGUS / FLAWED Check-Then-Act:
     * While ConcurrentHashMap methods are individually thread-safe,
     * the compound sequence of containsKey() -> get() -> put() is NOT atomic!
     * Two threads can concurrently see the same count and overwrite each other's increment,
     * resulting in lost updates.
     */
    public void incrementFlawed(Long auctionId) {
        if (!flawedCounts.containsKey(auctionId)) {
            Thread.yield(); // Provoke race window
            flawedCounts.put(auctionId, 1);
        } else {
            int current = flawedCounts.get(auctionId);
            Thread.yield(); // Provoke race window
            flawedCounts.put(auctionId, current + 1);
        }
    }

    /**
     * FIXED ATOMIC OPERATION:
     * Uses ConcurrentHashMap.merge() which performs the check, computation, and update
     * inside the bucket lock atomically.
     */
    public void incrementFixedWithMerge(Long auctionId) {
        fixedCounts.merge(auctionId, 1, Integer::sum);
    }

    /**
     * FIXED ATOMIC OPERATION:
     * Alternatively uses ConcurrentHashMap.compute() with identical atomic semantics.
     */
    public void incrementFixedWithCompute(Long auctionId) {
        fixedCounts.compute(auctionId, (key, value) -> (value == null) ? 1 : value + 1);
    }

    public int getFlawedCount(Long auctionId) {
        return flawedCounts.getOrDefault(auctionId, 0);
    }

    public int getFixedCount(Long auctionId) {
        return fixedCounts.getOrDefault(auctionId, 0);
    }
}
