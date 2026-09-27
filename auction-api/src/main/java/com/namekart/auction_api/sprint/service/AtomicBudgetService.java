package com.namekart.auction_api.sprint.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * L10 Step 3: High-throughput non-blocking in-memory budget service using AtomicLong and Compare-And-Set (CAS).
 * Uses hardware atomic instructions (CMPXCHG) without thread suspension or monitor acquisition.
 */
@Service
public class AtomicBudgetService {

    private final AtomicLong remainingBudget = new AtomicLong();
    private final AtomicLong totalAllocated = new AtomicLong();

    public void init(long initialBudget) {
        this.remainingBudget.set(initialBudget);
        this.totalAllocated.set(0);
    }

    /**
     * Lock-free CAS loop:
     * Reads current remaining budget, evaluates condition, and attempts atomic update.
     * Retries if another thread modified the value concurrently.
     */
    public boolean allocateBudget(long amount) {
        while (true) {
            long current = remainingBudget.get();
            if (current < amount) {
                return false;
            }
            if (remainingBudget.compareAndSet(current, current - amount)) {
                totalAllocated.addAndGet(amount);
                return true;
            }
        }
    }

    public long getRemainingBudget() {
        return remainingBudget.get();
    }

    public long getTotalAllocated() {
        return totalAllocated.get();
    }
}
