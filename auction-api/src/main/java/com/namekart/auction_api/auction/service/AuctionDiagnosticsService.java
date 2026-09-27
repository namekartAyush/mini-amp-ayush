package com.namekart.auction_api.auction.service;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Diagnostics service demonstrating N+1 query problem vs JOIN FETCH optimization.
 */
@Service
public class AuctionDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(AuctionDiagnosticsService.class);

    private final AuctionRepository auctionRepository;

    public AuctionDiagnosticsService(AuctionRepository auctionRepository) {
        this.auctionRepository = auctionRepository;
    }

    /**
     * UNOPTIMIZED: Executes 1 query to fetch auctions,
     * then executes 1 query per auction to load Domain (N queries)
     * plus 1 query per auction to load Bids (N queries).
     * Total = 1 + 2N queries!
     */
    @Transactional(readOnly = true)
    public int executeUnoptimizedQueryWithNPlusOne(AuctionStatus status) {
        log.info("--> Executing unoptimized query for status: {}", status);
        List<Auction> auctions = auctionRepository.findTop20ByStatus(status);

        int totalBidsCounted = 0;
        for (Auction auction : auctions) {
            // Triggers lazy loading of Domain (N queries)
            String domainName = auction.getDomain().getName();
            // Triggers lazy loading of Bids collection (N queries)
            int bidCount = auction.getBids().size();
            totalBidsCounted += bidCount;
            log.debug("Auction #{} domain={}, bids={}", auction.getId(), domainName, bidCount);
        }
        return totalBidsCounted;
    }

    /**
     * OPTIMIZED: Uses JPQL JOIN FETCH to load Auction, Domain, and Bids in ONE single query.
     * Total = 1 query!
     */
    @Transactional(readOnly = true)
    public int executeOptimizedQueryWithJoinFetch(AuctionStatus status) {
        log.info("--> Executing optimized JOIN FETCH query for status: {}", status);
        List<Auction> auctions = auctionRepository.findTop20ByStatusWithDomainAndBidsOptimized(status);

        int totalBidsCounted = 0;
        for (Auction auction : auctions) {
            // Already loaded in the initial JOIN FETCH - 0 additional queries!
            String domainName = auction.getDomain().getName();
            int bidCount = auction.getBids().size();
            totalBidsCounted += bidCount;
            log.debug("Auction #{} domain={}, bids={}", auction.getId(), domainName, bidCount);
        }
        return totalBidsCounted;
    }
}
