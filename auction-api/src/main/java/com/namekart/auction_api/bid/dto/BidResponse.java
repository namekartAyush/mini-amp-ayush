package com.namekart.auction_api.bid.dto;

import com.namekart.auction_api.bid.model.Bid;

import java.math.BigDecimal;
import java.time.Instant;

public record BidResponse(
        Long id,
        Long auctionId,
        String bidderEmail,
        BigDecimal amount,
        Instant createdAt
) {
    public static BidResponse fromEntity(Bid bid) {
        return new BidResponse(
                bid.getId(),
                bid.getAuction().getId(),
                bid.getBidderEmail(),
                bid.getAmount(),
                bid.getCreatedAt()
        );
    }
}
