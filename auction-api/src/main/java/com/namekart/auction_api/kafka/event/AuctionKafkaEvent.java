package com.namekart.auction_api.kafka.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event published to Kafka for auction lifecycle events.
 */
public record AuctionKafkaEvent(
        String eventId,
        Long auctionId,
        String domainName,
        String eventType, // AUCTION_CREATED, BID_PLACED, AUCTION_CLOSED
        BigDecimal amount,
        String bidderEmail,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp
) {}
