package com.namekart.auction_api.auction.repository;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface AuctionRepository extends JpaRepository<Auction, Long> {
    Page<Auction> findByStatus(AuctionStatus status, Pageable pageable);
    Optional<Auction> findByDomainId(Long domainId);
    List<Auction> findByDomainIdAndStatusIn(Long domainId, List<AuctionStatus> statuses);

    // Unoptimized query (causes N+1 when iterating domain and bids)
    List<Auction> findTop20ByStatus(AuctionStatus status);

    // OPTIMIZED QUERY (Fixes N+1 with explicit JOIN FETCH in a single round-trip)
    @Query("SELECT DISTINCT a FROM Auction a " +
           "JOIN FETCH a.domain " +
           "LEFT JOIN FETCH a.bids " +
           "WHERE a.status = :status")
    List<Auction> findTop20ByStatusWithDomainAndBidsOptimized(@Param("status") AuctionStatus status);
}

