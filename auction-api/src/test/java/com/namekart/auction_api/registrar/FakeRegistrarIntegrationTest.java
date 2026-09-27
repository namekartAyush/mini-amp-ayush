package com.namekart.auction_api.registrar;

import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.domain.repository.DomainRepository;
import com.namekart.auction_api.registrar.client.FakeRegistrarClient;
import com.namekart.auction_api.registrar.client.FeignFakeRegistrarClient;
import com.namekart.auction_api.registrar.client.RestClientFakeRegistrarClient;
import com.namekart.auction_api.registrar.config.RegistrarCacheConfig;
import com.namekart.auction_api.registrar.controller.FakeRegistrarController;
import com.namekart.auction_api.registrar.dto.FakeRegistrarBidRequest;
import com.namekart.auction_api.registrar.dto.FakeRegistrarBidResponse;
import com.namekart.auction_api.registrar.dto.FakeRegistrarCheckDto;
import com.namekart.auction_api.registrar.dto.FakeRegistrarDomainDto;
import com.namekart.auction_api.registrar.exception.RegistrarBlockedException;
import com.namekart.auction_api.registrar.service.AuctionSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class FakeRegistrarIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private FakeRegistrarController fakeRegistrarController;

    @Autowired
    private RestClientFakeRegistrarClient restClientRegistrarClient;

    @Autowired
    @Qualifier("feignFakeRegistrarClient")
    private FeignFakeRegistrarClient feignRegistrarClient;

    @Autowired
    private AuctionSyncService auctionSyncService;

    @Autowired
    private DomainRepository domainRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        String testUrl = "http://localhost:" + port;
        restClientRegistrarClient.setBaseUrl(testUrl);
        feignRegistrarClient.setBaseUrl(testUrl);

        fakeRegistrarController.resetCounts();
        if (cacheManager.getCache(RegistrarCacheConfig.REGISTRAR_AUCTIONS_CACHE) != null) {
            cacheManager.getCache(RegistrarCacheConfig.REGISTRAR_AUCTIONS_CACHE).clear();
        }
    }

    @Test
    @DisplayName("Verify RestClient and Feign both successfully fetch active domain auctions")
    void testBothClientsFetchAuctions() {
        List<FakeRegistrarDomainDto> restClientAuctions = restClientRegistrarClient.fetchAuctions(Collections.emptyMap());
        assertThat(restClientAuctions).isNotEmpty();
        assertThat(restClientAuctions.get(0).name()).isEqualTo("quantumcloud.io");

        List<FakeRegistrarDomainDto> feignAuctions = feignRegistrarClient.fetchAuctions(Collections.emptyMap());
        assertThat(feignAuctions).isNotEmpty();
        assertThat(feignAuctions.get(0).name()).isEqualTo("quantumcloud.io");
    }

    @Test
    @DisplayName("Verify HTTP 200 with BLOCKED payload is detected and throws RegistrarBlockedException")
    void testBlockedResponseDetectionArrivingAsHttp200() {
        // RestClient detection
        assertThatThrownBy(() -> restClientRegistrarClient.fetchAuctions(Map.of("simulateBlocked", "true")))
                .isInstanceOf(RegistrarBlockedException.class)
                .hasMessageContaining("Rate limit exceeded or IP blocked");

        // Feign detection
        assertThatThrownBy(() -> feignRegistrarClient.fetchAuctions(Map.of("simulateBlocked", "true")))
                .isInstanceOf(RegistrarBlockedException.class)
                .hasMessageContaining("Rate limit exceeded or IP blocked");
    }

    @Test
    @DisplayName("Verify safe idempotent read retries with exponential backoff on transient 500 error")
    void testIdempotentReadRetriesWithBackoff() {
        fakeRegistrarController.resetCounts();

        // Server fails first 2 requests with HTTP 500, succeeds on 3rd attempt
        List<FakeRegistrarDomainDto> results = restClientRegistrarClient.fetchAuctions(Map.of("failFirstN", "2"));

        assertThat(results).isNotEmpty();
        // Verifies exactly 3 HTTP attempts took place before succeeding
        assertThat(fakeRegistrarController.getGetDomainsRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Verify non-idempotent mutation (POST bid) is NEVER retried on failure")
    void testNonIdempotentMutationDoesNotRetry() {
        fakeRegistrarController.resetCounts();

        FakeRegistrarBidRequest request = new FakeRegistrarBidRequest("quantumcloud.io", "bidder@example.com", BigDecimal.valueOf(5000));

        // When external bid returns 500
        assertThatThrownBy(() -> restClientRegistrarClient.placeExternalBid(request, 500))
                .isNotNull();

        // MUST be called exactly once; NO automatic retries to prevent duplicate bidding!
        assertThat(fakeRegistrarController.getPostBidRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify @Cacheable on registrar list caches result and respects deliberate expiry")
    void testCacheableOnRegistrarList() {
        fakeRegistrarController.resetCounts();

        // 1st invocation: Cache miss -> Hits fake registrar
        List<FakeRegistrarDomainDto> firstCall = auctionSyncService.getCachedRegistrarAuctions();
        assertThat(firstCall).isNotEmpty();
        assertThat(fakeRegistrarController.getGetDomainsRequestCount()).isEqualTo(1);

        // 2nd invocation: Cache hit -> Returned from Caffeine cache without hitting fake registrar
        List<FakeRegistrarDomainDto> secondCall = auctionSyncService.getCachedRegistrarAuctions();
        assertThat(secondCall).isNotEmpty();
        assertThat(fakeRegistrarController.getGetDomainsRequestCount()).isEqualTo(1); // STILL 1!

        // Clear cache (simulating deliberate TTL expiry)
        cacheManager.getCache(RegistrarCacheConfig.REGISTRAR_AUCTIONS_CACHE).clear();

        // 3rd invocation: Cache miss again -> Hits fake registrar
        List<FakeRegistrarDomainDto> thirdCall = auctionSyncService.getCachedRegistrarAuctions();
        assertThat(thirdCall).isNotEmpty();
        assertThat(fakeRegistrarController.getGetDomainsRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Verify scheduled sync cannot overlap with itself when concurrent triggers occur")
    void testSyncCannotOverlapWithItself() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successfulTriggerCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    // Introduce artificial delay in registrar response so sync takes time
                    boolean triggered = auctionSyncService.triggerSync(Map.of("delay", "100"));
                    if (triggered) {
                        successfulTriggerCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Due to AtomicBoolean guard and non-overlapping guarantee, concurrent triggers are safely rejected
        assertThat(successfulTriggerCount.get()).isLessThan(threads);
        assertThat(auctionSyncService.isSyncRunning()).isFalse();
    }

    @Test
    @DisplayName("Verify sync survives registrar blocked/rate-limit error without crashing the application")
    void testSyncSurvivesRegistrarBlockedErrorGracefully() {
        int initialBlockedCount = auctionSyncService.getBlockedIncidentCount();

        // Trigger sync against blocked registrar
        boolean result = auctionSyncService.triggerSync(Map.of("simulateBlocked", "true"));

        // Sync returned false (handled error gracefully)
        assertThat(result).isFalse();
        // Blocked incident recorded, application did NOT crash
        assertThat(auctionSyncService.getBlockedIncidentCount()).isEqualTo(initialBlockedCount + 1);
        assertThat(auctionSyncService.isSyncRunning()).isFalse();
    }
}
