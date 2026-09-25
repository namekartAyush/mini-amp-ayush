package com.namekart.auction_api.auction.repository;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuctionRepository extends JpaRepository<Auction, Long> {
    Page<Auction> findByStatus(AuctionStatus status, Pageable pageable);
    List<Auction> findByDomainIdAndStatusIn(Long domainId, List<AuctionStatus> statuses);
}
