package com.namekart.auction_api.auction.dto;

import com.namekart.auction_api.auction.model.AuctionStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateAuctionRequest(
        @NotNull(message = "Domain ID is required")
        Long domainId,

        @NotNull(message = "Starting price is required")
        @DecimalMin(value = "0.01", message = "Starting price must be greater than 0")
        BigDecimal startingPrice,

        @DecimalMin(value = "0.01", message = "Reserve price must be greater than 0")
        BigDecimal reservePrice,

        @NotNull(message = "Start time is required")
        Instant startTime,

        @NotNull(message = "End time is required")
        @Future(message = "End time must be in the future")
        Instant endTime,

        AuctionStatus status
) {}
