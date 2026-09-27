package com.namekart.auction_api.registrar.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO representing an auction or domain listing returned by the external registrar.
 */
public record FakeRegistrarDomainDto(
        String name,
        String tld,
        BigDecimal estimatedValue,
        BigDecimal currentPrice,
        BigDecimal reservePrice,
        Instant endTime,
        String status
) {}
