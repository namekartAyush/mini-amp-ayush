package com.namekart.auction_api.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L8 Observability: Platform metrics adhering to the <service>_<subject>_<unit> contract.
 *
 * Archetypes implemented:
 * 1. Liveness: auction_api_liveness_status (Gauge: 1 = UP, 0 = DOWN)
 * 2. Freshness: auction_api_sync_last_success_timestamp_seconds (Gauge) & auction_api_sync_interval_seconds (Gauge)
 * 3. Error Rate: auction_api_registrar_errors_total (Counter) & auction_api_registrar_requests_total (Counter)
 * 4. Dependency Latency: auction_api_registrar_latency_seconds (Timer)
 */
@Service
public class AuctionMetricsService {

    private final MeterRegistry registry;

    // Archetype 1: Liveness
    private final AtomicInteger livenessStatus = new AtomicInteger(1);

    // Archetype 2: Sync Freshness
    private final AtomicLong lastSyncSuccessTimestampSeconds = new AtomicLong(Instant.now().getEpochSecond());
    private final AtomicLong syncIntervalSeconds = new AtomicLong(60);

    // Archetype 3: Error Rate & Request Volume
    private final Counter registrarRequestsTotal;
    private final Counter registrarErrorsTotal;

    // Archetype 4: Dependency Latency
    private final Timer registrarLatencyTimer;

    public AuctionMetricsService(MeterRegistry registry) {
        this.registry = registry;

        // 1. Liveness
        registry.gauge("auction_api_liveness_status", livenessStatus, AtomicInteger::get);

        // 2. Sync Freshness
        registry.gauge("auction_api_sync_last_success_timestamp_seconds", lastSyncSuccessTimestampSeconds, AtomicLong::get);
        registry.gauge("auction_api_sync_interval_seconds", syncIntervalSeconds, AtomicLong::get);

        // 3. Error Rate & Request Volume
        this.registrarRequestsTotal = Counter.builder("auction_api_registrar_requests_total")
                .description("Total number of external registrar HTTP requests")
                .tag("service", "auction-api")
                .register(registry);

        this.registrarErrorsTotal = Counter.builder("auction_api_registrar_errors_total")
                .description("Total number of failed external registrar HTTP requests")
                .tag("service", "auction-api")
                .register(registry);

        // 4. Dependency Latency
        this.registrarLatencyTimer = Timer.builder("auction_api_registrar_latency_seconds")
                .description("Latency distribution of external registrar HTTP requests")
                .tag("service", "auction-api")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void setLiveness(boolean isUp) {
        livenessStatus.set(isUp ? 1 : 0);
    }

    public void recordSyncSuccess() {
        lastSyncSuccessTimestampSeconds.set(Instant.now().getEpochSecond());
    }

    public void setSyncIntervalSeconds(long seconds) {
        syncIntervalSeconds.set(seconds);
    }

    public void recordRegistrarRequest() {
        registrarRequestsTotal.increment();
    }

    public void recordRegistrarError() {
        registrarErrorsTotal.increment();
    }

    public void recordRegistrarLatency(Duration duration) {
        registrarLatencyTimer.record(duration);
    }

    public double getRegistrarRequests() {
        return registrarRequestsTotal.count();
    }

    public double getRegistrarErrors() {
        return registrarErrorsTotal.count();
    }

    public long getLastSyncSuccessTimestamp() {
        return lastSyncSuccessTimestampSeconds.get();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
