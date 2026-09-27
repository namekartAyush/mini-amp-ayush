package com.namekart.auction_api.common.diagnostics;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/pool")
public class PoolDiagnosticsController {

    private final PoolDiagnosticsService poolDiagnosticsService;

    public PoolDiagnosticsController(PoolDiagnosticsService poolDiagnosticsService) {
        this.poolDiagnosticsService = poolDiagnosticsService;
    }

    @GetMapping("/read")
    public ResponseEntity<Map<String, Object>> readFast() {
        long start = System.currentTimeMillis();
        long count = poolDiagnosticsService.readFast();
        return ResponseEntity.ok(Map.of(
                "totalAuctions", count,
                "latencyMs", System.currentTimeMillis() - start
        ));
    }

    @GetMapping("/work-inside-tx")
    public ResponseEntity<Map<String, Object>> workInsideTx(
            @RequestParam(defaultValue = "300") long sleepMs) {
        return ResponseEntity.ok(poolDiagnosticsService.workInsideTransaction(sleepMs));
    }

    @GetMapping("/work-outside-tx")
    public ResponseEntity<Map<String, Object>> workOutsideTx(
            @RequestParam(defaultValue = "300") long sleepMs) {
        return ResponseEntity.ok(poolDiagnosticsService.workOutsideTransaction(sleepMs));
    }
}
