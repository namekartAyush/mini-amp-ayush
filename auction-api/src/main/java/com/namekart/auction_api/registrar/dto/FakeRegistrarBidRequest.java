package com.namekart.auction_api.registrar.dto;

import java.math.BigDecimal;

/**
 * Non-idempotent bid mutation request to external registrar.
 */
public record FakeRegistrarBidRequest(
        String domain,
        String bidderEmail,
        BigDecimal amount
) {}
