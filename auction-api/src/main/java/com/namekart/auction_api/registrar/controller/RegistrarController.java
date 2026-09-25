package com.namekart.auction_api.registrar.controller;

import com.namekart.auction_api.registrar.service.RegistrarService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for registrar-related operations.
 * Strictly uses constructor injection.
 */
@RestController
@RequestMapping("/api/registrar")
public class RegistrarController {

    private final RegistrarService registrarService;

    public RegistrarController(RegistrarService registrarService) {
        this.registrarService = registrarService;
    }

    @GetMapping("/provider")
    public ResponseEntity<Map<String, String>> getDefaultProvider() {
        return ResponseEntity.ok(Map.of("defaultProvider", registrarService.getDefaultProvider()));
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkDomainAvailability(@RequestParam String domain) {
        boolean available = registrarService.checkAvailability(domain);
        return ResponseEntity.ok(Map.of(
                "domain", domain,
                "available", available,
                "provider", registrarService.getDefaultProvider()
        ));
    }
}
