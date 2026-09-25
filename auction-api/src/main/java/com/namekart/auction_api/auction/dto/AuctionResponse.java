package com.namekart.auction_api.auction.dto;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AuctionResponse(
        Long id,
        Long domainId,
        String domainName,
        BigDecimal startingPrice,
        BigDecimal reservePrice,
        BigDecimal currentHighestBid,
        AuctionStatus status,
        Instant startTime,
        Instant endTime,
        Instant createdAt,
        Instant updatedAt
) {
    public static AuctionResponse fromEntity(Auction auction) {
        return new AuctionResponse(
                auction.getId(),
                auction.getDomain().getId(),
                auction.getDomain().getName(),
                auction.getStartingPrice(),
                auction.getReservePrice(),
                auction.getCurrentHighestBid(),
                auction.getStatus(),
                auction.getStartTime(),
                auction.getEndTime(),
                auction.getCreatedAt(),
                auction.getUpdatedAt()
        );
    }
}
