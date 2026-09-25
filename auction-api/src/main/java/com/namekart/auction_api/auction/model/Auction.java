package com.namekart.auction_api.auction.model;

import com.namekart.auction_api.domain.model.Domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "auctions", indexes = {
        @Index(name = "idx_auction_domain_id", columnList = "domain_id"),
        @Index(name = "idx_auction_status", columnList = "status"),
        @Index(name = "idx_auction_end_time", columnList = "end_time")
})
public class Auction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "domain_id", nullable = false)
    private Domain domain;

    @Column(name = "starting_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal startingPrice;

    @Column(name = "reserve_price", precision = 12, scale = 2)
    private BigDecimal reservePrice;

    @Column(name = "current_highest_bid", precision = 12, scale = 2)
    private BigDecimal currentHighestBid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuctionStatus status = AuctionStatus.PENDING;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Auction() {}

    public Auction(Domain domain, BigDecimal startingPrice, BigDecimal reservePrice, Instant startTime, Instant endTime, AuctionStatus status) {
        this.domain = domain;
        this.startingPrice = startingPrice;
        this.reservePrice = reservePrice;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status != null ? status : AuctionStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Domain getDomain() {
        return domain;
    }

    public void setDomain(Domain domain) {
        this.domain = domain;
    }

    public BigDecimal getStartingPrice() {
        return startingPrice;
    }

    public void setStartingPrice(BigDecimal startingPrice) {
        this.startingPrice = startingPrice;
    }

    public BigDecimal getReservePrice() {
        return reservePrice;
    }

    public void setReservePrice(BigDecimal reservePrice) {
        this.reservePrice = reservePrice;
    }

    public BigDecimal getCurrentHighestBid() {
        return currentHighestBid;
    }

    public void setCurrentHighestBid(BigDecimal currentHighestBid) {
        this.currentHighestBid = currentHighestBid;
    }

    public AuctionStatus getStatus() {
        return status;
    }

    public void setStatus(AuctionStatus status) {
        this.status = status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
