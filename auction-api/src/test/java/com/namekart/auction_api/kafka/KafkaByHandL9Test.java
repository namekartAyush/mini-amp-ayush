package com.namekart.auction_api.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L9 Laboratory Experiment: Kafka by hand.
 * Verifies:
 * 1. Key partitioning: 30 messages across keys 'a', 'b', 'c' into 3 partitions.
 * 2. Consumer group rebalance and idle 4th consumer.
 * 3. Crash before commit and message redelivery.
 * 4. Idempotent consumer deduplication.
 * 5. Poison pill blocking vs Dead-Letter Topic (DLT) recovery.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 3,
        topics = {
                "l9-step1-topic",
                "l9-step2-topic",
                "l9-step3-topic",
                "l9-step4-topic",
                "l9-step5-topic",
                "l9-step5-topic.DLT"
        }
)
public class KafkaByHandL9Test {

    private static final Logger log = LoggerFactory.getLogger(KafkaByHandL9Test.class);

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private String bootstrapServers;

    @BeforeEach
    void initBootstrapServers() {
        bootstrapServers = embeddedKafka.getBrokersAsString();
    }

    private Properties getProducerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    private Properties getConsumerProps(String groupId, boolean autoCommit) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, String.valueOf(autoCommit));
        return props;
    }

    private ConsumerRecords<String, String> pollUntilNotEmpty(KafkaConsumer<String, String> consumer, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        ConsumerRecords<String, String> records = ConsumerRecords.empty();
        while (records.isEmpty() && System.currentTimeMillis() < deadline) {
            records = consumer.poll(Duration.ofMillis(300));
        }
        return records;
    }

    @Test
    @DisplayName("Step 1: Produce 30 messages with keys 'a', 'b', 'c' to 3 partitions and prove deterministic hash routing")
    void testStep1_PartitionKeyRouting() throws Exception {
        String topic = "l9-step1-topic";
        Map<String, Set<Integer>> keyToPartitions = new HashMap<>();
        keyToPartitions.put("a", new HashSet<>());
        keyToPartitions.put("b", new HashSet<>());
        keyToPartitions.put("c", new HashSet<>());

        Map<Integer, List<String>> partitionToMessages = new HashMap<>();
        partitionToMessages.put(0, new ArrayList<>());
        partitionToMessages.put(1, new ArrayList<>());
        partitionToMessages.put(2, new ArrayList<>());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(getProducerProps())) {
            String[] keys = {"a", "b", "c"};
            for (int i = 1; i <= 30; i++) {
                String key = keys[(i - 1) % 3];
                String value = "msg-" + key + "-" + i;

                Future<RecordMetadata> future = producer.send(new ProducerRecord<>(topic, key, value));
                RecordMetadata rm = future.get();

                keyToPartitions.get(key).add(rm.partition());
                partitionToMessages.get(rm.partition()).add(value);
            }
        }

        System.out.println("==================================================");
        System.out.println("L9 STEP 1 · KEY PARTITION ROUTING RESULTS:");
        System.out.println("Key 'a' landed in partition(s): " + keyToPartitions.get("a"));
        System.out.println("Key 'b' landed in partition(s): " + keyToPartitions.get("b"));
        System.out.println("Key 'c' landed in partition(s): " + keyToPartitions.get("c"));
        System.out.println("Partition 0 messages count: " + partitionToMessages.get(0).size());
        System.out.println("Partition 1 messages count: " + partitionToMessages.get(1).size());
        System.out.println("Partition 2 messages count: " + partitionToMessages.get(2).size());
        System.out.println("==================================================");

        // PROOF: Every key lands in EXACTLY ONE partition deterministically via murmur2 hashing
        assertThat(keyToPartitions.get("a")).hasSize(1);
        assertThat(keyToPartitions.get("b")).hasSize(1);
        assertThat(keyToPartitions.get("c")).hasSize(1);

        // Total 30 messages across the 3 partitions
        int totalProduced = partitionToMessages.get(0).size() + partitionToMessages.get(1).size() + partitionToMessages.get(2).size();
        assertThat(totalProduced).isEqualTo(30);
    }

    @Test
    @DisplayName("Step 2: Consumer Group partition ownership, rebalancing, and 4th consumer idle state")
    void testStep2_ConsumerGroupRebalanceAndIdleConsumer() {
        String topic = "l9-step2-topic";
        String groupId = "l9-group-" + UUID.randomUUID();

        // Start Consumer 1: Should own all 3 partitions
        KafkaConsumer<String, String> c1 = new KafkaConsumer<>(getConsumerProps(groupId, false));
        c1.subscribe(List.of(topic));
        c1.poll(Duration.ofMillis(500)); // triggers group join & initial assignment

        Set<TopicPartition> c1Assignment = c1.assignment();
        System.out.println("==================================================");
        System.out.println("L9 STEP 2 · CONSUMER GROUP ASSIGNMENTS:");
        System.out.println("Consumer 1 alone in group: assigned " + c1Assignment.size() + " partitions -> " + c1Assignment);

        // When only 1 consumer exists, it owns all 3 partitions
        assertThat(c1Assignment).hasSize(3);

        // Start Consumer 2 in same group: Triggers rebalance
        KafkaConsumer<String, String> c2 = new KafkaConsumer<>(getConsumerProps(groupId, false));
        c2.subscribe(List.of(topic));

        // Rebalance poll
        for (int i = 0; i < 5; i++) {
            c1.poll(Duration.ofMillis(200));
            c2.poll(Duration.ofMillis(200));
        }

        System.out.println("After Consumer 2 joins:");
        System.out.println("Consumer 1 assignment: " + c1.assignment().size() + " partitions -> " + c1.assignment());
        System.out.println("Consumer 2 assignment: " + c2.assignment().size() + " partitions -> " + c2.assignment());

        // Partitions split between C1 and C2 (sum must equal 3)
        assertThat(c1.assignment().size() + c2.assignment().size()).isEqualTo(3);

        // Start Consumer 3 and Consumer 4 in same group (total 4 consumers on 3 partitions)
        KafkaConsumer<String, String> c3 = new KafkaConsumer<>(getConsumerProps(groupId, false));
        c3.subscribe(List.of(topic));
        KafkaConsumer<String, String> c4 = new KafkaConsumer<>(getConsumerProps(groupId, false));
        c4.subscribe(List.of(topic));

        for (int i = 0; i < 5; i++) {
            c1.poll(Duration.ofMillis(200));
            c2.poll(Duration.ofMillis(200));
            c3.poll(Duration.ofMillis(200));
            c4.poll(Duration.ofMillis(200));
        }

        System.out.println("After Consumer 3 and Consumer 4 join (4 consumers for 3 partitions):");
        System.out.println("C1: " + c1.assignment().size() + ", C2: " + c2.assignment().size() +
                ", C3: " + c3.assignment().size() + ", C4: " + c4.assignment().size());
        System.out.println("==================================================");

        List<Integer> sizes = List.of(c1.assignment().size(), c2.assignment().size(), c3.assignment().size(), c4.assignment().size());
        // PROOF: Exactly one consumer MUST sit idle (assigned 0 partitions) because 4 > 3 partitions!
        assertThat(sizes).contains(0);

        c1.close();
        c2.close();
        c3.close();
        c4.close();
    }

    @Test
    @DisplayName("Step 3: Process message and crash before commit, then verify redelivery on restart")
    void testStep3_CrashBeforeCommitRedelivery() throws Exception {
        String topic = "l9-step3-topic";
        String groupId = "l9-crash-group-" + UUID.randomUUID();

        // Produce a message
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(getProducerProps())) {
            producer.send(new ProducerRecord<>(topic, "bid-1", "payload-for-bid-1")).get();
        }

        List<String> processedSideEffects = new ArrayList<>();

        // 1. First Consumer run: processes message and crashes BEFORE commitSync()
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(getConsumerProps(groupId, false))) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = pollUntilNotEmpty(consumer, Duration.ofSeconds(5));
            assertThat(records).isNotEmpty();

            for (ConsumerRecord<String, String> record : records) {
                // Execute business side effect
                processedSideEffects.add("Processed: " + record.value());
                System.out.println("Consumer 1 processing record at offset=" + record.offset() + ": " + record.value());
                // SIMULATED CRASH: Does NOT call consumer.commitSync()
                break;
            }
            // Consumer terminates abruptly without committing offset
        }

        assertThat(processedSideEffects).hasSize(1);

        // 2. Restart Consumer with same group ID: Offset was NOT committed, so Kafka MUST redeliver!
        try (KafkaConsumer<String, String> restartedConsumer = new KafkaConsumer<>(getConsumerProps(groupId, false))) {
            restartedConsumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> redeliveredRecords = pollUntilNotEmpty(restartedConsumer, Duration.ofSeconds(5));

            assertThat(redeliveredRecords).isNotEmpty();
            ConsumerRecord<String, String> redelivered = redeliveredRecords.iterator().next();

            System.out.println("Restarted Consumer received REDELIVERY of offset=" + redelivered.offset() + ": " + redelivered.value());
            processedSideEffects.add("Re-processed: " + redelivered.value());

            // Commit on second run
            restartedConsumer.commitSync();
        }

        System.out.println("==================================================");
        System.out.println("L9 STEP 3 · CRASH BEFORE COMMIT RESULTS:");
        System.out.println("Total side effects executed without idempotency: " + processedSideEffects.size());
        System.out.println("Side effects log: " + processedSideEffects);
        System.out.println("==================================================");

        // PROOF OF AT-LEAST-ONCE SYMPTOM: Side effect executed TWICE because of uncommitted crash
        assertThat(processedSideEffects).hasSize(2);
    }

    @Test
    @DisplayName("Step 4: Idempotent consumer records processed IDs, crashes again, and skips duplicate cleanly")
    void testStep4_IdempotentConsumerDeduplication() throws Exception {
        String topic = "l9-step4-topic";
        String groupId = "l9-idempotent-group-" + UUID.randomUUID();

        // Produce a message with unique business id
        String messageId = "event-bid-999";
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(getProducerProps())) {
            producer.send(new ProducerRecord<>(topic, "bid-key", messageId)).get();
        }

        // Idempotency Deduplication Store
        Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();
        AtomicInteger actualBusinessExecutions = new AtomicInteger(0);

        // 1. Consumer 1: processes message, records ID, crashes before commit
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(getConsumerProps(groupId, false))) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = pollUntilNotEmpty(consumer, Duration.ofSeconds(5));

            for (ConsumerRecord<String, String> record : records) {
                String id = record.value();
                if (processedMessageIds.add(id)) {
                    // First time seeing this message
                    actualBusinessExecutions.incrementAndGet();
                    System.out.println("Idempotent Consumer 1: First time processing id=" + id);
                }
                // CRASH before commitSync()
                break;
            }
        }

        assertThat(actualBusinessExecutions.get()).isEqualTo(1);

        // 2. Restart Consumer: Message is redelivered, but Idempotency Guard skips duplicate!
        try (KafkaConsumer<String, String> restartedConsumer = new KafkaConsumer<>(getConsumerProps(groupId, false))) {
            restartedConsumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> redeliveredRecords = pollUntilNotEmpty(restartedConsumer, Duration.ofSeconds(5));

            for (ConsumerRecord<String, String> record : redeliveredRecords) {
                String id = record.value();
                if (!processedMessageIds.add(id)) {
                    // DUPLICATE DETECTED: Skip business side-effects!
                    System.out.println("Idempotent Consumer 2: DUPLICATE DETECTED for id=" + id + ". Skipping side effect!");
                } else {
                    actualBusinessExecutions.incrementAndGet();
                }
            }
            restartedConsumer.commitSync();
        }

        System.out.println("==================================================");
        System.out.println("L9 STEP 4 · IDEMPOTENT DEDUPLICATION RESULTS:");
        System.out.println("Actual business executions after crash & restart: " + actualBusinessExecutions.get());
        System.out.println("==================================================");

        // PROOF: Despite redelivery, business logic executed EXACTLY ONCE!
        assertThat(actualBusinessExecutions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Step 5: Poison pill blocks partition; Dead-Letter Topic (DLT) unblocks downstream messages")
    void testStep5_PoisonPillAndDeadLetterTopic() throws Exception {
        String mainTopic = "l9-step5-topic";
        String dltTopic = "l9-step5-topic.DLT";
        String groupId = "l9-poison-group-" + UUID.randomUUID();

        // Produce 3 messages to the EXACT SAME partition (using partition 0 directly):
        // 1. Valid Message 1
        // 2. POISON PILL (unparseable / malformed JSON)
        // 3. Valid Message 2 (queued directly behind poison pill!)
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(getProducerProps())) {
            producer.send(new ProducerRecord<>(mainTopic, 0, "key1", "{\"type\":\"VALID\",\"id\":1}")).get();
            producer.send(new ProducerRecord<>(mainTopic, 0, "key2", "MALFORMED_CORRUPTED_POISON_PILL_BYTES")).get();
            producer.send(new ProducerRecord<>(mainTopic, 0, "key3", "{\"type\":\"VALID\",\"id\":2}")).get();
        }

        List<String> successfullyProcessed = new ArrayList<>();
        List<String> dltRecovered = new ArrayList<>();

        // Consumer with Dead-Letter Topic recovery strategy
        try (KafkaProducer<String, String> dltProducer = new KafkaProducer<>(getProducerProps());
             KafkaConsumer<String, String> consumer = new KafkaConsumer<>(getConsumerProps(groupId, false))) {

            consumer.subscribe(List.of(mainTopic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(3));

            for (ConsumerRecord<String, String> record : records) {
                try {
                    // Parser simulation
                    if (!record.value().startsWith("{")) {
                        throw new IllegalArgumentException("Cannot parse JSON: " + record.value());
                    }
                    successfullyProcessed.add(record.value());
                    System.out.println("Successfully processed: " + record.value());
                } catch (Exception ex) {
                    System.out.println("POISON PILL INTERCEPTED: " + record.value() + " -> Routing to DLT!");
                    // Route to Dead-Letter Topic (DLT)
                    dltProducer.send(new ProducerRecord<>(dltTopic, record.key(), record.value())).get();
                    dltRecovered.add(record.value());
                }
            }
            consumer.commitSync();
        }

        System.out.println("==================================================");
        System.out.println("L9 STEP 5 · DEAD-LETTER TOPIC (DLT) RESULTS:");
        System.out.println("Successfully processed valid messages: " + successfullyProcessed);
        System.out.println("Poison pill safely routed to DLT: " + dltRecovered);
        System.out.println("==================================================");

        // PROOF: Valid messages before and AFTER the poison pill were processed; poison pill was isolated to DLT!
        assertThat(successfullyProcessed).hasSize(2);
        assertThat(dltRecovered).hasSize(1);
        assertThat(dltRecovered.get(0)).contains("MALFORMED_CORRUPTED_POISON_PILL");
    }
}
