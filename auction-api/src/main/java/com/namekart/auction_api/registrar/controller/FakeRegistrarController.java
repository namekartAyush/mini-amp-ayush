package com.namekart.auction_api.registrar.controller;

import com.namekart.auction_api.registrar.dto.FakeRegistrarBidRequest;
import com.namekart.auction_api.registrar.dto.FakeRegistrarBidResponse;
import com.namekart.auction_api.registrar.dto.FakeRegistrarCheckDto;
import com.namekart.auction_api.registrar.dto.FakeRegistrarDomainDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controller simulating the external "fake registrar" HTTP API.
 * Demonstrates latency, transient 5xx errors, rate limiting, and HTTP 200 "blocked" responses.
 */
@RestController
@RequestMapping("/api/fake-registrar")
public class FakeRegistrarController {

    private static final Logger log = LoggerFactory.getLogger(FakeRegistrarController.class);

    private final AtomicInteger getDomainsRequestCount = new AtomicInteger(0);
    private final AtomicInteger postBidRequestCount = new AtomicInteger(0);

    @GetMapping("/domains")
    public ResponseEntity<?> getDomains(
            @RequestParam(required = false, defaultValue = "0") long delay,
            @RequestParam(required = false) Integer simulateError,
            @RequestParam(required = false, defaultValue = "false") boolean simulateBlocked,
            @RequestParam(required = false, defaultValue = "0") int failFirstN) {

        int currentCount = getDomainsRequestCount.incrementAndGet();
        log.info("FakeRegistrar GET /domains called (call #{}, delay={}ms, error={}, blocked={}, failFirstN={})",
                currentCount, delay, simulateError, simulateBlocked, failFirstN);

        // 1. Simulated Latency
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 2. Simulated "Blocked" response that arrives as HTTP 200
        if (simulateBlocked) {
            log.warn("FakeRegistrar emitting HTTP 200 with BLOCKED payload");
            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "code", "BLOCKED",
                    "message", "Rate limit exceeded or IP blocked by registrar firewall"
            ));
        }

        // 3. Transient error simulation (e.g. fail first N requests, then succeed)
        if (failFirstN > 0 && currentCount <= failFirstN) {
            log.warn("FakeRegistrar failing transiently (attempt {} <= {})", currentCount, failFirstN);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Simulated transient registrar failure (500)"));
        }

        if (simulateError != null && simulateError > 0) {
            return ResponseEntity.status(simulateError)
                    .body(Map.of("error", "Simulated error: " + simulateError));
        }

        // 4. Successful domains response
        List<FakeRegistrarDomainDto> domains = List.of(
                new FakeRegistrarDomainDto("quantumcloud.io", "io", BigDecimal.valueOf(15000), BigDecimal.valueOf(1200), BigDecimal.valueOf(2500), Instant.now().plus(2, ChronoUnit.DAYS), "ACTIVE"),
                new FakeRegistrarDomainDto("aipipeline.com", "com", BigDecimal.valueOf(35000), BigDecimal.valueOf(4500), BigDecimal.valueOf(8000), Instant.now().plus(4, ChronoUnit.DAYS), "ACTIVE"),
                new FakeRegistrarDomainDto("cybermesh.ai", "ai", BigDecimal.valueOf(8500), BigDecimal.valueOf(800), BigDecimal.valueOf(1500), Instant.now().plus(1, ChronoUnit.DAYS), "ACTIVE")
        );

        return ResponseEntity.ok(domains);
    }

    @GetMapping("/check")
    public ResponseEntity<?> checkDomain(
            @RequestParam String domain,
            @RequestParam(required = false, defaultValue = "0") long delay,
            @RequestParam(required = false, defaultValue = "false") boolean simulateBlocked) {

        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (simulateBlocked) {
            return ResponseEntity.ok(Map.of(
                    "status", 200,
                    "code", "BLOCKED",
                    "message", "Bot behavior detected on domain check endpoint"
            ));
        }

        return ResponseEntity.ok(new FakeRegistrarCheckDto(domain, true, BigDecimal.valueOf(250)));
    }

    @PostMapping("/bids")
    public ResponseEntity<?> placeBid(
            @RequestBody FakeRegistrarBidRequest request,
            @RequestParam(required = false) Integer simulateError) {

        int count = postBidRequestCount.incrementAndGet();
        log.info("FakeRegistrar POST /bids mutation called (call #{}, domain={}, amount={})",
                count, request.domain(), request.amount());

        if (simulateError != null && simulateError > 0) {
            return ResponseEntity.status(simulateError)
                    .body(Map.of("error", "External bid mutation failed with " + simulateError));
        }

        return ResponseEntity.ok(new FakeRegistrarBidResponse(
                request.domain(),
                true,
                request.amount(),
                "TX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
        ));
    }

    public int getGetDomainsRequestCount() {
        return getDomainsRequestCount.get();
    }

    public int getPostBidRequestCount() {
        return postBidRequestCount.get();
    }

    public void resetCounts() {
        getDomainsRequestCount.set(0);
        postBidRequestCount.set(0);
    }
}
