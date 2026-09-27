package com.namekart.auction_api.bid.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PlaceBidRequest(
        @NotNull(message = "Auction ID is required")
        Long auctionId,

        @NotBlank(message = "Bidder email cannot be blank")
        @Email(message = "Invalid email format")
        String bidderEmail,

        @NotNull(message = "Bid amount is required")
        @DecimalMin(value = "0.01", message = "Bid amount must be positive")
        BigDecimal amount
) {}
