package com.namekart.auction_api.common.diagnostics;

import com.namekart.auction_api.auction.repository.AuctionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class PoolDiagnosticsService {

    private final AuctionRepository auctionRepository;

    public PoolDiagnosticsService(AuctionRepository auctionRepository) {
        this.auctionRepository = auctionRepository;
    }

    /**
     * Fast read within a minimal transaction.
     */
    @Transactional(readOnly = true)
    public long readFast() {
        return auctionRepository.count();
    }

    /**
     * ANTIPATTERN: Slow work INSIDE transaction.
     * Connection is held from method entry to exit, tying up the connection pool during sleep!
     */
    @Transactional
    public Map<String, Object> workInsideTransaction(long sleepMs) {
        long start = System.currentTimeMillis();
        long count = auctionRepository.count(); // Acquires JDBC connection from pool
        try {
            Thread.sleep(sleepMs); // Connection is HELD IDLE in transaction!
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long duration = System.currentTimeMillis() - start;
        return Map.of("count", count, "durationMs", duration, "mode", "INSIDE_TX");
    }

    /**
     * BEST PRACTICE: Slow work OUTSIDE transaction.
     * Brief query executes in separate short transaction, releases connection back to pool immediately,
     * and sleep occurs with ZERO database connections held!
     */
    public Map<String, Object> workOutsideTransaction(long sleepMs) {
        long start = System.currentTimeMillis();
        long count = executeShortRead(); // Connection acquired and released immediately
        try {
            Thread.sleep(sleepMs); // Performed with ZERO database connections held
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long duration = System.currentTimeMillis() - start;
        return Map.of("count", count, "durationMs", duration, "mode", "OUTSIDE_TX");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long executeShortRead() {
        return auctionRepository.count();
    }
}
