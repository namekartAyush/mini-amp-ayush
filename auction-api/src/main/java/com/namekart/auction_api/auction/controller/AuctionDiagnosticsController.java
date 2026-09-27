package com.namekart.auction_api.auction.controller;

import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.service.AuctionDiagnosticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/n-plus-one")
public class AuctionDiagnosticsController {

    private final AuctionDiagnosticsService diagnosticsService;

    public AuctionDiagnosticsController(AuctionDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping("/unoptimized")
    public ResponseEntity<Map<String, Object>> executeUnoptimized(
            @RequestParam(defaultValue = "ACTIVE") AuctionStatus status) {
        long startTime = System.currentTimeMillis();
        int bidsCounted = diagnosticsService.executeUnoptimizedQueryWithNPlusOne(status);
        long elapsed = System.currentTimeMillis() - startTime;

        return ResponseEntity.ok(Map.of(
                "strategy", "UNOPTIMIZED_N_PLUS_ONE",
                "status", status,
                "description", "Provokes N+1: 1 query for auctions + N queries for domain + N queries for bids (1 + 2N queries)",
                "bidsCounted", bidsCounted,
                "durationMs", elapsed
        ));
    }

    @GetMapping("/optimized")
    public ResponseEntity<Map<String, Object>> executeOptimized(
            @RequestParam(defaultValue = "ACTIVE") AuctionStatus status) {
        long startTime = System.currentTimeMillis();
        int bidsCounted = diagnosticsService.executeOptimizedQueryWithJoinFetch(status);
        long elapsed = System.currentTimeMillis() - startTime;

        return ResponseEntity.ok(Map.of(
                "strategy", "OPTIMIZED_JOIN_FETCH",
                "status", status,
                "description", "Eliminates N+1: Single SQL round-trip utilizing JPQL JOIN FETCH",
                "bidsCounted", bidsCounted,
                "durationMs", elapsed
        ));
    }
}
