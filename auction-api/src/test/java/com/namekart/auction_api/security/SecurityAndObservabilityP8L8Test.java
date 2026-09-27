package com.namekart.auction_api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.bid.dto.PlaceBidRequest;
import com.namekart.auction_api.bid.repository.BidRepository;
import com.namekart.auction_api.common.metrics.AuctionMetricsService;
import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.repository.DomainRepository;
import com.namekart.auction_api.kafka.event.AuctionKafkaEvent;
import com.namekart.auction_api.kafka.producer.AuctionEventProducer;
import com.namekart.auction_api.security.model.UserRole;
import com.namekart.auction_api.security.service.JwtService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ActiveProfiles("test")
@Import(SecurityAndObservabilityP8L8Test.TestKafkaConfig.class)
public class SecurityAndObservabilityP8L8Test {

    private static final List<AuctionKafkaEvent> publishedEvents = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean
        public AuctionEventProducer auctionEventProducer() {
            return new AuctionEventProducer(null, new ObjectMapper()) {
                @Override
                public CompletableFuture<SendResult<String, String>> sendAuctionEvent(String topic, AuctionKafkaEvent event) {
                    publishedEvents.add(event);
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuctionMetricsService auctionMetricsService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DomainRepository domainRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    private Long activeAuctionId;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        bidRepository.deleteAll();
        auctionRepository.deleteAll();
        domainRepository.deleteAll();

        Domain domain = domainRepository.save(new Domain(
                "p8-security-" + UUID.randomUUID().toString().substring(0, 8) + ".com",
                "com",
                BigDecimal.valueOf(5000),
                DomainStatus.AUCTION
        ));

        Auction auction = auctionRepository.save(new Auction(
                domain,
                BigDecimal.valueOf(100.00),
                BigDecimal.valueOf(500.00),
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(7, ChronoUnit.DAYS),
                AuctionStatus.ACTIVE
        ));

        activeAuctionId = auction.getId();
    }

    @Test
    @DisplayName("JWT Service: Generates, validates, and parses roles correctly")
    void testJwtGenerationAndValidation() {
        String token = jwtService.generateToken("alice-bidder", UserRole.BIDDER);
        assertThat(token).isNotBlank();

        assertThat(jwtService.validateToken(token)).isTrue();
        assertThat(jwtService.extractUsername(token)).isEqualTo("alice-bidder");
        List<String> bidderRoles = jwtService.extractRoles(token);
        assertThat(bidderRoles).contains("ROLE_BIDDER");

        String adminToken = jwtService.generateToken("admin-user", UserRole.ADMIN);
        List<String> adminRoles = jwtService.extractRoles(adminToken);
        assertThat(adminRoles).contains("ROLE_ADMIN");

        String viewerToken = jwtService.generateToken("viewer-guest", UserRole.VIEWER);
        List<String> viewerRoles = jwtService.extractRoles(viewerToken);
        assertThat(viewerRoles).contains("ROLE_VIEWER");
    }

    @Test
    @DisplayName("P8 Auth Matrix: VIEWER can read auctions, but cannot bid (403) or manage registrars (403)")
    void testViewerRolePermissions() throws Exception {
        String viewerToken = jwtService.generateToken("viewer-guest", UserRole.VIEWER);

        // 1. Can read auctions
        mockMvc.perform(get("/api/auctions")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        // 2. Cannot bid (Method-level @PreAuthorize rejects VIEWER)
        PlaceBidRequest bidRequest = new PlaceBidRequest(activeAuctionId, "viewer@example.com", new BigDecimal("150.00"));
        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bidRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403));

        // 3. Cannot manage registrars
        mockMvc.perform(get("/api/registrar/provider")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P8 Auth Matrix: BIDDER can bid (201), publishes Kafka event, but cannot manage registrars (403)")
    void testBidderRolePermissions() throws Exception {
        String bidderToken = jwtService.generateToken("bob-bidder", UserRole.BIDDER);

        // 1. Can place bid
        PlaceBidRequest bidRequest = new PlaceBidRequest(activeAuctionId, "bob@example.com", new BigDecimal("120.00"));
        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer " + bidderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bidRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(120.00))
                .andExpect(jsonPath("$.bidderEmail").value("bob@example.com"));

        // Verify Kafka event published to topic
        assertThat(publishedEvents).hasSize(1);
        AuctionKafkaEvent publishedEvent = publishedEvents.get(0);
        assertThat(publishedEvent.eventType()).isEqualTo("BID_PLACED");
        assertThat(publishedEvent.auctionId()).isEqualTo(activeAuctionId);
        assertThat(publishedEvent.amount()).isEqualByComparingTo("120.00");
        assertThat(publishedEvent.bidderEmail()).isEqualTo("bob@example.com");

        // 2. Cannot manage registrars
        mockMvc.perform(get("/api/registrar/provider")
                        .header("Authorization", "Bearer " + bidderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P8 Auth Matrix: ADMIN can bid (201) and manage registrars (200)")
    void testAdminRolePermissions() throws Exception {
        String adminToken = jwtService.generateToken("admin-root", UserRole.ADMIN);

        // 1. Can place bid
        PlaceBidRequest bidRequest = new PlaceBidRequest(activeAuctionId, "admin@example.com", new BigDecimal("130.00"));
        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bidRequest)))
                .andExpect(status().isCreated());

        // 2. Can manage registrars
        mockMvc.perform(get("/api/registrar/provider")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("P8 Auth: Unauthenticated requests to protected endpoints return 401 ProblemDetail")
    void testUnauthenticatedAccessReturns401() throws Exception {
        PlaceBidRequest bidRequest = new PlaceBidRequest(activeAuctionId, "anon@example.com", new BigDecimal("110.00"));

        mockMvc.perform(post("/api/bids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bidRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("L8 Observability: Actuator Prometheus endpoint exposes metrics following the naming contract")
    void testPrometheusEndpointAndMetricsContract() throws Exception {
        // Record test metrics
        auctionMetricsService.recordSyncSuccess();
        auctionMetricsService.setSyncIntervalSeconds(60);
        auctionMetricsService.recordRegistrarRequest();
        auctionMetricsService.recordRegistrarLatency(Duration.ofMillis(50));
        auctionMetricsService.recordRegistrarError();

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    // Contract: <service>_<subject>_<unit>
                    assertThat(body).contains("auction_api_liveness_status");
                    assertThat(body).contains("auction_api_sync_last_success_timestamp_seconds");
                    assertThat(body).contains("auction_api_sync_interval_seconds");
                    assertThat(body).contains("auction_api_registrar_requests_total");
                    assertThat(body).contains("auction_api_registrar_errors_total");
                    assertThat(body).contains("auction_api_registrar_latency_seconds");
                });
    }

    @Test
    @DisplayName("L8 High Cardinality Experiment: Adding user_id label multiplies series count, removing restores sanity")
    void testHighCardinalityMetricExplosionAndRemoval() {
        int initialMeters = meterRegistry.getMeters().size();

        // 1. Simulate high-cardinality anti-pattern: user_id label with 50 unique users
        for (int i = 1; i <= 50; i++) {
            Counter.builder("auction_api_bids_cardinality_test")
                    .tag("user_id", "user-uuid-" + i)
                    .register(meterRegistry)
                    .increment();
        }

        int explodedMeters = meterRegistry.getMeters().size();
        assertThat(explodedMeters).isGreaterThanOrEqualTo(initialMeters + 50);

        // 2. Remove high-cardinality metric series from registry
        meterRegistry.getMeters().stream()
                .filter(m -> "auction_api_bids_cardinality_test".equals(m.getId().getName()))
                .toList()
                .forEach(meterRegistry::remove);

        int restoredMeters = meterRegistry.getMeters().size();
        assertThat(restoredMeters).isLessThan(explodedMeters);
    }

    @Test
    @DisplayName("TraceId Filter: Injects X-Request-ID into response and MDC")
    void testTraceIdHeaderPropagation() throws Exception {
        String customTraceId = "trace-" + UUID.randomUUID();

        mockMvc.perform(get("/actuator/health")
                        .header("X-Request-ID", customTraceId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", customTraceId));

        // When not provided, a UUID is auto-generated
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"));
    }
}
