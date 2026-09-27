package com.namekart.auction_api.registrar.dto;

import java.math.BigDecimal;

/**
 * DTO representing domain availability check response from the registrar.
 */
public record FakeRegistrarCheckDto(
        String domain,
        boolean available,
        BigDecimal price
) {}
