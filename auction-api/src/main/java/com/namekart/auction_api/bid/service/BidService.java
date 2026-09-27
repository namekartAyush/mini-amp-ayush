package com.namekart.auction_api.bid.service;

import com.namekart.auction_api.bid.dto.BidResponse;
import com.namekart.auction_api.bid.model.Bid;
import com.namekart.auction_api.bid.repository.BidRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BidService {

    private final BidRepository bidRepository;

    public BidService(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    public Page<BidResponse> listBids(String bidderEmail, Pageable pageable) {
        Page<Bid> bids = (bidderEmail != null && !bidderEmail.isBlank())
                ? bidRepository.findByBidderEmail(bidderEmail, pageable)
                : bidRepository.findAll(pageable);
        return bids.map(BidResponse::fromEntity);
    }
}
