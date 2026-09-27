package com.namekart.auction_api.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares Kafka topics and partition counts for the auction platform.
 */
@Configuration
@Profile("!test")
public class KafkaTopicConfig {

    public static final String AUCTION_EVENTS_TOPIC = "auction.events";
    public static final String AUCTION_EVENTS_DLT_TOPIC = "auction.events.DLT";
    public static final String L9_EXPERIMENTS_TOPIC = "l9.experiments";
    public static final String L9_EXPERIMENTS_DLT_TOPIC = "l9.experiments.DLT";

    @Bean
    public NewTopic auctionEventsTopic() {
        return TopicBuilder.name(AUCTION_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic auctionEventsDltTopic() {
        return TopicBuilder.name(AUCTION_EVENTS_DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic l9ExperimentsTopic() {
        return TopicBuilder.name(L9_EXPERIMENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic l9ExperimentsDltTopic() {
        return TopicBuilder.name(L9_EXPERIMENTS_DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
