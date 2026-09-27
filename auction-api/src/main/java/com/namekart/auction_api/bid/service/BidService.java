package com.namekart.auction_api.bid.service;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.bid.dto.BidResponse;
import com.namekart.auction_api.bid.dto.PlaceBidRequest;
import com.namekart.auction_api.bid.model.Bid;
import com.namekart.auction_api.bid.repository.BidRepository;
import com.namekart.auction_api.common.exception.BusinessRuleException;
import com.namekart.auction_api.common.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import com.namekart.auction_api.kafka.config.KafkaTopicConfig;
import com.namekart.auction_api.kafka.event.AuctionKafkaEvent;
import com.namekart.auction_api.kafka.producer.AuctionEventProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

@Service
@Transactional(readOnly = true)
public class BidService {

    private static final Logger log = LoggerFactory.getLogger(BidService.class);

    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;
    private final ObjectProvider<AuctionEventProducer> auctionEventProducerProvider;

    public BidService(BidRepository bidRepository,
                      AuctionRepository auctionRepository,
                      ObjectProvider<AuctionEventProducer> auctionEventProducerProvider) {
        this.bidRepository = bidRepository;
        this.auctionRepository = auctionRepository;
        this.auctionEventProducerProvider = auctionEventProducerProvider;
    }

    public Page<BidResponse> listBids(String bidderEmail, Pageable pageable) {
        Page<Bid> bids = (bidderEmail != null && !bidderEmail.isBlank())
                ? bidRepository.findByBidderEmail(bidderEmail, pageable)
                : bidRepository.findAll(pageable);
        return bids.map(BidResponse::fromEntity);
    }

    /**
     * Places a bid atomically within a single database transaction.
     * Validates current price, inserts the bid, updates the auction,
     * and relies on @Version on Auction for optimistic locking.
     */
    @Transactional
    public BidResponse placeBid(PlaceBidRequest request) {
        Auction auction = auctionRepository.findById(request.auctionId())
                .orElseThrow(() -> new ResourceNotFoundException("Auction", request.auctionId()));

        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new BusinessRuleException(String.format("Cannot place bid on auction in '%s' status; auction must be ACTIVE", auction.getStatus()));
        }

        if (auction.getEndTime().isBefore(Instant.now())) {
            throw new BusinessRuleException("Cannot place bid on an auction that has already ended");
        }

        BigDecimal currentPrice = auction.getCurrentHighestBid() != null
                ? auction.getCurrentHighestBid()
                : auction.getStartingPrice();

        if (auction.getCurrentHighestBid() != null) {
            if (request.amount().compareTo(currentPrice) <= 0) {
                throw new BusinessRuleException(String.format("Bid amount %s must be strictly higher than current highest bid %s", request.amount(), currentPrice));
            }
        } else {
            if (request.amount().compareTo(currentPrice) < 0) {
                throw new BusinessRuleException(String.format("Bid amount %s cannot be less than starting price %s", request.amount(), currentPrice));
            }
        }

        // 1. Insert the Bid
        Bid bid = new Bid(auction, request.bidderEmail().trim().toLowerCase(), request.amount());
        Bid savedBid = bidRepository.save(bid);

        // 2. Update the Auction with optimistic locking
        auction.setCurrentHighestBid(request.amount());
        auctionRepository.save(auction);

        // 3. Publish real-time AuctionKafkaEvent to Kafka
        auctionEventProducerProvider.ifAvailable(producer -> {
            try {
                String eventId = java.util.UUID.randomUUID().toString();
                String domainName = auction.getDomain() != null ? auction.getDomain().getName() : "auction-" + auction.getId();
                AuctionKafkaEvent event = new AuctionKafkaEvent(
                        eventId,
                        auction.getId(),
                        domainName,
                        "BID_PLACED",
                        request.amount(),
                        request.bidderEmail(),
                        Instant.now()
                );
                producer.sendAuctionEvent(KafkaTopicConfig.AUCTION_EVENTS_TOPIC, event);
                log.info("Successfully published BID_PLACED event {} for auction {}", eventId, auction.getId());
            } catch (Exception e) {
                log.warn("Failed to publish Kafka event for auction {}: {}", auction.getId(), e.getMessage());
            }
        });

        return BidResponse.fromEntity(savedBid);
    }
}
