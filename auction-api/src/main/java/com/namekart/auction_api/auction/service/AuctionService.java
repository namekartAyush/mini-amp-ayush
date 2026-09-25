package com.namekart.auction_api.auction.service;

import com.namekart.auction_api.auction.dto.AuctionResponse;
import com.namekart.auction_api.auction.dto.CreateAuctionRequest;
import com.namekart.auction_api.auction.dto.UpdateAuctionRequest;
import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.common.exception.BusinessRuleException;
import com.namekart.auction_api.common.exception.ResourceNotFoundException;
import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.repository.DomainRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class AuctionService {

    private final AuctionRepository auctionRepository;
    private final DomainRepository domainRepository;

    public AuctionService(AuctionRepository auctionRepository, DomainRepository domainRepository) {
        this.auctionRepository = auctionRepository;
        this.domainRepository = domainRepository;
    }

    public AuctionResponse createAuction(CreateAuctionRequest request) {
        if (request.endTime().isBefore(request.startTime())) {
            throw new BusinessRuleException("Auction end time must be after start time");
        }
        if (request.reservePrice() != null && request.reservePrice().compareTo(request.startingPrice()) < 0) {
            throw new BusinessRuleException("Reserve price cannot be less than starting price");
        }

        Domain domain = domainRepository.findById(request.domainId())
                .orElseThrow(() -> new ResourceNotFoundException("Domain", request.domainId()));

        if (domain.getStatus() == DomainStatus.SOLD) {
            throw new BusinessRuleException(String.format("Domain '%s' is marked as SOLD and cannot be auctioned", domain.getName()));
        }

        List<Auction> activeAuctions = auctionRepository.findByDomainIdAndStatusIn(
                domain.getId(), List.of(AuctionStatus.PENDING, AuctionStatus.ACTIVE));
        if (!activeAuctions.isEmpty()) {
            throw new BusinessRuleException(String.format("Domain '%s' already has an ongoing or pending auction", domain.getName()));
        }

        Auction auction = new Auction(
                domain,
                request.startingPrice(),
                request.reservePrice(),
                request.startTime(),
                request.endTime(),
                request.status() != null ? request.status() : AuctionStatus.PENDING
        );

        domain.setStatus(DomainStatus.AUCTION);
        domainRepository.save(domain);

        Auction saved = auctionRepository.save(auction);
        return AuctionResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public AuctionResponse getAuctionById(Long id) {
        Auction auction = auctionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Auction", id));
        return AuctionResponse.fromEntity(auction);
    }

    @Transactional(readOnly = true)
    public Page<AuctionResponse> listAuctions(AuctionStatus status, Pageable pageable) {
        Page<Auction> auctions = (status != null)
                ? auctionRepository.findByStatus(status, pageable)
                : auctionRepository.findAll(pageable);
        return auctions.map(AuctionResponse::fromEntity);
    }

    public AuctionResponse updateAuction(Long id, UpdateAuctionRequest request) {
        Auction auction = auctionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Auction", id));

        if (request.startingPrice() != null) {
            auction.setStartingPrice(request.startingPrice());
        }
        if (request.reservePrice() != null) {
            if (auction.getStartingPrice() != null && request.reservePrice().compareTo(auction.getStartingPrice()) < 0) {
                throw new BusinessRuleException("Reserve price cannot be less than starting price");
            }
            auction.setReservePrice(request.reservePrice());
        }
        if (request.startTime() != null) {
            auction.setStartTime(request.startTime());
        }
        if (request.endTime() != null) {
            if (request.endTime().isBefore(auction.getStartTime())) {
                throw new BusinessRuleException("Auction end time must be after start time");
            }
            auction.setEndTime(request.endTime());
        }
        if (request.status() != null) {
            auction.setStatus(request.status());
            if (request.status() == AuctionStatus.COMPLETED || request.status() == AuctionStatus.CANCELLED) {
                auction.getDomain().setStatus(DomainStatus.AVAILABLE);
                domainRepository.save(auction.getDomain());
            }
        }

        Auction updated = auctionRepository.save(auction);
        return AuctionResponse.fromEntity(updated);
    }

    public void deleteAuction(Long id) {
        Auction auction = auctionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Auction", id));
        if (auction.getStatus() == AuctionStatus.ACTIVE) {
            throw new BusinessRuleException("Cannot delete an active auction; cancel or complete it first");
        }
        auction.getDomain().setStatus(DomainStatus.AVAILABLE);
        domainRepository.save(auction.getDomain());
        auctionRepository.delete(auction);
    }
}
