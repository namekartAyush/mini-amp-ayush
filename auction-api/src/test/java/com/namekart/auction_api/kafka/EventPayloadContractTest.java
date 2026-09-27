package com.namekart.auction_api.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.namekart.auction_api.kafka.event.AuctionKafkaEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * P9: Contract test ensuring the event payload shape emitted by auction-api
 * strictly matches the schema and type expectations of the notifier Node.js service.
 */
public class EventPayloadContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("Contract: AuctionKafkaEvent serializes with exact field names and types expected by notifier")
    void testAuctionKafkaEventSerializationContract() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-09-28T00:15:00Z");

        AuctionKafkaEvent event = new AuctionKafkaEvent(
                eventId,
                42L,
                "domain-contract.com",
                "BID_PLACED",
                new BigDecimal("250.75"),
                "contract-bidder@example.com",
                now
        );

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        // Required fields checked by notifier consumer
        assertThat(node.hasNonNull("eventId")).isTrue();
        assertThat(node.get("eventId").asText()).isEqualTo(eventId);

        assertThat(node.hasNonNull("auctionId")).isTrue();
        assertThat(node.get("auctionId").asLong()).isEqualTo(42L);

        assertThat(node.hasNonNull("domainName")).isTrue();
        assertThat(node.get("domainName").asText()).isEqualTo("domain-contract.com");

        assertThat(node.hasNonNull("eventType")).isTrue();
        assertThat(node.get("eventType").asText()).isEqualTo("BID_PLACED");

        assertThat(node.hasNonNull("amount")).isTrue();
        assertThat(node.get("amount").asDouble()).isEqualTo(250.75);

        assertThat(node.hasNonNull("bidderEmail")).isTrue();
        assertThat(node.get("bidderEmail").asText()).isEqualTo("contract-bidder@example.com");

        assertThat(node.hasNonNull("timestamp")).isTrue();
        // Ensure timestamp is valid ISO-8601 string parseable by Javascript Date(timestamp)
        String timestampStr = node.get("timestamp").asText();
        assertThatNoException().isThrownBy(() -> Instant.parse(timestampStr));
    }

    @Test
    @DisplayName("Contract: Deserialization tolerates notifier ingestion payload")
    void testDeserializationFromRawJson() throws Exception {
        String rawJson = """
                {
                  "eventId": "evt-test-12345",
                  "auctionId": 105,
                  "domainName": "incoming-feed.ai",
                  "eventType": "BID_PLACED",
                  "amount": 999.50,
                  "bidderEmail": "bidder@incoming.org",
                  "timestamp": "2026-09-28T12:00:00Z"
                }
                """;

        AuctionKafkaEvent event = objectMapper.readValue(rawJson, AuctionKafkaEvent.class);

        assertThat(event.eventId()).isEqualTo("evt-test-12345");
        assertThat(event.auctionId()).isEqualTo(105L);
        assertThat(event.domainName()).isEqualTo("incoming-feed.ai");
        assertThat(event.eventType()).isEqualTo("BID_PLACED");
        assertThat(event.amount()).isEqualByComparingTo("999.50");
        assertThat(event.bidderEmail()).isEqualTo("bidder@incoming.org");
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-09-28T12:00:00Z"));
    }
}
