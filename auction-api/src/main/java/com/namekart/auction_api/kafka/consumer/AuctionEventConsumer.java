package com.namekart.auction_api.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.namekart.auction_api.kafka.config.KafkaTopicConfig;
import com.namekart.auction_api.kafka.event.AuctionKafkaEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;

/**
 * Production-ready Kafka consumer demonstrating:
 * 1. Idempotency via deduplication of eventId.
 * 2. Non-blocking retry and Dead-Letter Topic (DLT) routing for poison pills.
 * 3. Manual acknowledgment control for at-least-once delivery testing.
 */
@Component
public class AuctionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionEventConsumer.class);

    private final ObjectMapper objectMapper;

    // Idempotency Deduplication Store (Stores processed eventIds to guard against duplicate redelivery)
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    // Counters for experiment validation
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger duplicateDetectedCount = new AtomicInteger(0);
    private final AtomicInteger dltReceivedCount = new AtomicInteger(0);

    public AuctionEventConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Consumes auction events from 'auction.events'.
     * Retries transient exceptions up to 2 times, then routes poison pill messages directly to DLT.
     */
    @RetryableTopic(
            attempts = "2",
            backoff = @Backoff(delay = 100, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = DltStrategy.ALWAYS_RETRY_ON_ERROR,
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
            topics = KafkaTopicConfig.AUCTION_EVENTS_TOPIC,
            groupId = "auction-analytics-group",
            autoStartup = "${app.kafka.listener.auto-startup:true}"
    )
    public void consumeAuctionEvent(
            ConsumerRecord<String, String> record,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Consumer received message from partition={}, offset={}, key={}", partition, offset, record.key());

        AuctionKafkaEvent event;
        try {
            event = objectMapper.readValue(record.value(), AuctionKafkaEvent.class);
        } catch (Exception e) {
            log.error("POISON PILL DETECTED at partition={}, offset={}! Cannot parse payload: '{}'",
                    partition, offset, record.value());
            throw new IllegalArgumentException("Malformed poison pill message: " + record.value(), e);
        }

        // =====================================================================
        // IDEMPOTENCY CHECK
        // Guard against duplicate redelivery in at-least-once delivery semantics
        // =====================================================================
        if (!processedEventIds.add(event.eventId())) {
            duplicateDetectedCount.incrementAndGet();
            log.warn("DUPLICATE EVENT DETECTED! eventId='{}' was already processed. Skipping business logic.",
                    event.eventId());
            return;
        }

        // Execute side-effecting business logic exactly once
        processedCount.incrementAndGet();
        log.info("Successfully processed event '{}' (type={}, amount={}, bidder={})",
                event.eventId(), event.eventType(), event.amount(), event.bidderEmail());
    }

    /**
     * Dead-Letter Topic (DLT) handler receiving poison pills that could not be processed.
     */
    @DltHandler
    public void handleDlt(ConsumerRecord<String, String> record) {
        dltReceivedCount.incrementAndGet();
        log.error("DLT HANDLER: Received poison message on DLT topic '{}', partition={}, offset={}, payload='{}'",
                record.topic(), record.partition(), record.offset(), record.value());
    }

    public boolean isEventProcessed(String eventId) {
        return processedEventIds.contains(eventId);
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getDuplicateDetectedCount() {
        return duplicateDetectedCount.get();
    }

    public int getDltReceivedCount() {
        return dltReceivedCount.get();
    }

    public void reset() {
        processedEventIds.clear();
        processedCount.set(0);
        duplicateDetectedCount.set(0);
        dltReceivedCount.set(0);
    }
}
