package com.namekart.auction_api.sprint.service;

import org.springframework.stereotype.Service;

/**
 * L10 Step 2: Thread-safe in-memory budget service using JVM intrinsic monitor locks (synchronized).
 * Enforces mutual exclusion: only one thread executes allocateBudget at a time.
 */
@Service
public class SynchronizedBudgetService {

    private long remainingBudget;
    private long totalAllocated;

    public synchronized void init(long initialBudget) {
        this.remainingBudget = initialBudget;
        this.totalAllocated = 0;
    }

    public synchronized boolean allocateBudget(long amount) {
        if (remainingBudget >= amount) {
            remainingBudget -= amount;
            totalAllocated += amount;
            return true;
        }
        return false;
    }

    public synchronized long getRemainingBudget() {
        return remainingBudget;
    }

    public synchronized long getTotalAllocated() {
        return totalAllocated;
    }
}
