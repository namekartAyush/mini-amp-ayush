package com.namekart.auction_api.domain.dto;

import com.namekart.auction_api.domain.model.DomainStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CreateDomainRequest(
        @NotBlank(message = "Domain name is required")
        @Pattern(regexp = "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.[a-zA-Z]{2,}$", message = "Invalid domain name format")
        String name,

        @PositiveOrZero(message = "Estimated value must be non-negative")
        BigDecimal estimatedValue,

        DomainStatus status
) {}
