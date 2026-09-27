package com.namekart.auction_api.bid.controller;

import com.namekart.auction_api.bid.dto.BidResponse;
import com.namekart.auction_api.bid.service.BidService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bids")
public class BidController {

    private final BidService bidService;

    public BidController(BidService bidService) {
        this.bidService = bidService;
    }

    @GetMapping
    public ResponseEntity<Page<BidResponse>> listBids(
            @RequestParam(required = false) String bidderEmail,
            @PageableDefault(size = 20, sort = "amount", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(bidService.listBids(bidderEmail, pageable));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BIDDER', 'ADMIN')")
    @org.springframework.web.bind.annotation.PostMapping
    public ResponseEntity<BidResponse> placeBid(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody com.namekart.auction_api.bid.dto.PlaceBidRequest request) {
        BidResponse response = bidService.placeBid(request);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(response);
    }
}
