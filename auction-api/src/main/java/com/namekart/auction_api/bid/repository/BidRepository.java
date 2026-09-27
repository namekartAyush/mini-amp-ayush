package com.namekart.auction_api.bid.repository;

import com.namekart.auction_api.bid.model.Bid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BidRepository extends JpaRepository<Bid, Long> {
    List<Bid> findByAuctionIdOrderByAmountDesc(Long auctionId);
    Page<Bid> findByBidderEmail(String bidderEmail, Pageable pageable);

    @Query("SELECT b FROM Bid b WHERE b.bidderEmail = :email ORDER BY b.amount DESC")
    List<Bid> findTopBidsByBidder(@Param("email") String email);
}
