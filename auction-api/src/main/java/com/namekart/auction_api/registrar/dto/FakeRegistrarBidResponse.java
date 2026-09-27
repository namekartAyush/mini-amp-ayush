package com.namekart.auction_api.registrar.dto;

import java.math.BigDecimal;

/**
 * Response for bid mutation on external registrar.
 */
public record FakeRegistrarBidResponse(
        String domain,
        boolean success,
        BigDecimal amount,
        String txId
) {}
