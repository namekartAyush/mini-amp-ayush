package com.namekart.auction_api.sprint.service;

import org.springframework.stereotype.Service;

/**
 * L10 Step 1: Flawed in-memory budget service storing remaining budget as a plain long field.
 * Demonstrates check-then-act race condition under concurrent access.
 */
@Service
public class PlainBudgetService {

    private long remainingBudget;
    private long totalAllocated;

    public void init(long initialBudget) {
        this.remainingBudget = initialBudget;
        this.totalAllocated = 0;
    }

    /**
     * Check-then-act race condition:
     * Multiple threads evaluate (remainingBudget >= amount) concurrently,
     * pass the check, and subtract from remainingBudget, causing an overspend.
     */
    public boolean allocateBudget(long amount) {
        if (remainingBudget >= amount) {
            // Thread context switch window / interleaving provocation
            Thread.yield();
            remainingBudget -= amount;
            totalAllocated += amount;
            return true;
        }
        return false;
    }

    public long getRemainingBudget() {
        return remainingBudget;
    }

    public long getTotalAllocated() {
        return totalAllocated;
    }
}
