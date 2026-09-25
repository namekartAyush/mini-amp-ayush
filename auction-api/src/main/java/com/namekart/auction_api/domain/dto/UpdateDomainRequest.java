package com.namekart.auction_api.domain.dto;

import com.namekart.auction_api.domain.model.DomainStatus;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record UpdateDomainRequest(
        @PositiveOrZero(message = "Estimated value must be non-negative")
        BigDecimal estimatedValue,

        DomainStatus status
) {}
