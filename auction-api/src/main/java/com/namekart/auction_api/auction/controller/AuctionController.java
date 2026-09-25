package com.namekart.auction_api.auction.controller;

import com.namekart.auction_api.auction.dto.AuctionResponse;
import com.namekart.auction_api.auction.dto.CreateAuctionRequest;
import com.namekart.auction_api.auction.dto.UpdateAuctionRequest;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.service.AuctionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auctions")
public class AuctionController {

    private final AuctionService auctionService;

    public AuctionController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    @PostMapping
    public ResponseEntity<AuctionResponse> createAuction(@Valid @RequestBody CreateAuctionRequest request) {
        AuctionResponse response = auctionService.createAuction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuctionResponse> getAuctionById(@PathVariable Long id) {
        return ResponseEntity.ok(auctionService.getAuctionById(id));
    }

    @GetMapping
    public ResponseEntity<Page<AuctionResponse>> listAuctions(
            @RequestParam(required = false) AuctionStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(auctionService.listAuctions(status, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AuctionResponse> updateAuction(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAuctionRequest request) {
        return ResponseEntity.ok(auctionService.updateAuction(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAuction(@PathVariable Long id) {
        auctionService.deleteAuction(id);
        return ResponseEntity.noContent().build();
    }
}
