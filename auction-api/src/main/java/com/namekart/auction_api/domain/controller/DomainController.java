package com.namekart.auction_api.domain.controller;

import com.namekart.auction_api.domain.dto.CreateDomainRequest;
import com.namekart.auction_api.domain.dto.DomainResponse;
import com.namekart.auction_api.domain.dto.UpdateDomainRequest;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.service.DomainService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/domains")
public class DomainController {

    private final DomainService domainService;

    public DomainController(DomainService domainService) {
        this.domainService = domainService;
    }

    @PostMapping
    public ResponseEntity<DomainResponse> createDomain(@Valid @RequestBody CreateDomainRequest request) {
        DomainResponse response = domainService.createDomain(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DomainResponse> getDomainById(@PathVariable Long id) {
        return ResponseEntity.ok(domainService.getDomainById(id));
    }

    @GetMapping
    public ResponseEntity<Page<DomainResponse>> listDomains(
            @RequestParam(required = false) DomainStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(domainService.listDomains(status, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DomainResponse> updateDomain(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDomainRequest request) {
        return ResponseEntity.ok(domainService.updateDomain(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDomain(@PathVariable Long id) {
        domainService.deleteDomain(id);
        return ResponseEntity.noContent().build();
    }
}
