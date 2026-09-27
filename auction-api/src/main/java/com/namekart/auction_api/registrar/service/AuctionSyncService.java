package com.namekart.auction_api.registrar.service;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.repository.DomainRepository;
import com.namekart.auction_api.registrar.client.FakeRegistrarClient;
import com.namekart.auction_api.registrar.config.RegistrarCacheConfig;
import com.namekart.auction_api.registrar.dto.FakeRegistrarDomainDto;
import com.namekart.auction_api.registrar.exception.RegistrarBlockedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled sync service that pulls external auctions every minute and synchronizes them into local storage.
 * Enforces:
 * 1. Non-overlapping execution: fixedDelay ensures next run waits for prior run + AtomicBoolean runtime guard.
 * 2. Caching with deliberate expiry via @Cacheable.
 * 3. Graceful degradation: Survives external registrar latency, 5xx errors, rate limits, and HTTP 200 blocked responses.
 */
@Service
@EnableScheduling
public class AuctionSyncService {

    private static final Logger log = LoggerFactory.getLogger(AuctionSyncService.class);

    private final FakeRegistrarClient registrarClient;
    private final DomainRepository domainRepository;
    private final AuctionRepository auctionRepository;

    private final AtomicBoolean isSyncRunning = new AtomicBoolean(false);
    private final AtomicInteger syncExecutionCount = new AtomicInteger(0);
    private final AtomicInteger successfulSyncCount = new AtomicInteger(0);
    private final AtomicInteger blockedIncidentCount = new AtomicInteger(0);

    public AuctionSyncService(
            FakeRegistrarClient registrarClient,
            DomainRepository domainRepository,
            AuctionRepository auctionRepository) {
        this.registrarClient = registrarClient;
        this.domainRepository = domainRepository;
        this.auctionRepository = auctionRepository;
    }

    /**
     * Cached registrar auction list with deliberate 5-minute TTL.
     * Subsequent calls within the TTL return cached data without hitting external registrar.
     */
    @Cacheable(value = RegistrarCacheConfig.REGISTRAR_AUCTIONS_CACHE, key = "'all'")
    public List<FakeRegistrarDomainDto> getCachedRegistrarAuctions() {
        log.info("Cache miss for '{}': fetching fresh auctions from registrar via {}",
                RegistrarCacheConfig.REGISTRAR_AUCTIONS_CACHE, registrarClient.getClientType());
        return registrarClient.fetchAuctions(Collections.emptyMap());
    }

    /**
     * Scheduled synchronization executing every 60 seconds.
     * fixedDelay = 60,000 ensures that the 60-second delay starts AFTER the current run completes,
     * guaranteeing execution cannot overlap with itself.
     */
    @Scheduled(fixedDelay = 60000, initialDelayString = "${app.sync.initial-delay-ms:5000}")
    public void syncAuctionsFromRegistrar() {
        // Explicit Concurrency Guard: Cannot overlap with itself
        if (!isSyncRunning.compareAndSet(false, true)) {
            log.warn("Scheduled sync attempted while previous sync is still running! Skipping execution to avoid overlap.");
            return;
        }

        int execIndex = syncExecutionCount.incrementAndGet();
        long startTime = System.currentTimeMillis();
        log.info("Starting scheduled auction sync cycle #{}", execIndex);

        try {
            // Safe fetch with resilience against external failure modes
            List<FakeRegistrarDomainDto> externalAuctions = registrarClient.fetchAuctions(Collections.emptyMap());
            int synced = persistAuctions(externalAuctions);
            successfulSyncCount.incrementAndGet();
            log.info("Auction sync cycle #{} completed successfully. Synced {} auctions in {}ms",
                    execIndex, synced, (System.currentTimeMillis() - startTime));

        } catch (RegistrarBlockedException rbe) {
            blockedIncidentCount.incrementAndGet();
            // Resilient handling: do NOT crash or hammer registrar, log warning and back off until next scheduled tick
            log.warn("Auction sync cycle #{} encountered registrar rate limit / BLOCKED firewall challenge: {}. Backing off safely.",
                    execIndex, rbe.getMessage());

        } catch (Exception e) {
            // General network / timeout / 500 error resilience: application thread remains completely alive
            log.error("Auction sync cycle #{} failed due to external registrar error: {}. Will retry on next scheduled cycle.",
                    execIndex, e.getMessage());

        } finally {
            isSyncRunning.set(false);
        }
    }

    /**
     * Manually triggers a sync (useful for testing or on-demand refresh).
     * Subject to the same non-overlapping atomic guard and error resilience.
     */
    public boolean triggerSync(Map<String, String> queryParams) {
        if (!isSyncRunning.compareAndSet(false, true)) {
            log.warn("Sync execution already in progress!");
            return false;
        }

        try {
            List<FakeRegistrarDomainDto> externalAuctions = registrarClient.fetchAuctions(queryParams);
            persistAuctions(externalAuctions);
            successfulSyncCount.incrementAndGet();
            return true;
        } catch (RegistrarBlockedException rbe) {
            blockedIncidentCount.incrementAndGet();
            log.warn("Manual sync blocked by registrar: {}", rbe.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Manual sync failed: {}", e.getMessage());
            return false;
        } finally {
            isSyncRunning.set(false);
        }
    }

    @Transactional
    public int persistAuctions(List<FakeRegistrarDomainDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (FakeRegistrarDomainDto dto : dtos) {
            Domain domain = domainRepository.findByName(dto.name()).orElseGet(() -> {
                Domain newDomain = new Domain(dto.name(), dto.tld(), dto.estimatedValue(), DomainStatus.AUCTION);
                return domainRepository.save(newDomain);
            });

            // Update or create auction
            Auction auction = auctionRepository.findByDomainId(domain.getId()).orElseGet(() -> {
                Auction newAuction = new Auction();
                newAuction.setDomain(domain);
                newAuction.setStartingPrice(dto.currentPrice());
                newAuction.setReservePrice(dto.reservePrice());
                newAuction.setCurrentHighestBid(dto.currentPrice());
                newAuction.setStatus(AuctionStatus.ACTIVE);
                newAuction.setStartTime(Instant.now());
                newAuction.setEndTime(dto.endTime() != null ? dto.endTime() : Instant.now().plusSeconds(86400));
                return newAuction;
            });

            auction.setCurrentHighestBid(dto.currentPrice());
            auctionRepository.save(auction);
            count++;
        }
        return count;
    }

    public boolean isSyncRunning() {
        return isSyncRunning.get();
    }

    public int getSyncExecutionCount() {
        return syncExecutionCount.get();
    }

    public int getSuccessfulSyncCount() {
        return successfulSyncCount.get();
    }

    public int getBlockedIncidentCount() {
        return blockedIncidentCount.get();
    }
}
