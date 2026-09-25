package com.namekart.auction_api.auction.dto;

import com.namekart.auction_api.auction.model.AuctionStatus;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.Instant;

public record UpdateAuctionRequest(
        @DecimalMin(value = "0.01", message = "Starting price must be greater than 0")
        BigDecimal startingPrice,

        @DecimalMin(value = "0.01", message = "Reserve price must be greater than 0")
        BigDecimal reservePrice,

        Instant startTime,
        Instant endTime,
        AuctionStatus status
) {}
