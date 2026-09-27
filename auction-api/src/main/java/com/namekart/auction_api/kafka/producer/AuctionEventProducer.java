package com.namekart.auction_api.kafka.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.namekart.auction_api.kafka.event.AuctionKafkaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Event producer publishing domain auction events to Kafka.
 * Enforces business key partitioning: partition key = auctionId.
 */
@Component
public class AuctionEventProducer {

    private static final Logger log = LoggerFactory.getLogger(AuctionEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public AuctionEventProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<SendResult<String, String>> sendAuctionEvent(String topic, AuctionKafkaEvent event) {
        // Business key decision: Partition by auctionId to guarantee FIFO ordering per auction!
        String key = String.valueOf(event.auctionId());
        try {
            String payload = objectMapper.writeValueAsString(event);
            log.info("Publishing Kafka event '{}' to topic '{}' with key '{}'", event.eventId(), topic, key);
            return kafkaTemplate.send(topic, key, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize and send Kafka event", e);
        }
    }

    public CompletableFuture<SendResult<String, String>> sendRaw(String topic, String key, String payload) {
        log.info("Publishing raw Kafka message to topic '{}' with key '{}'", topic, key);
        return kafkaTemplate.send(topic, key, payload);
    }
}
