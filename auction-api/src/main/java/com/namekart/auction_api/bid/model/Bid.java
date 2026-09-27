package com.namekart.auction_api.bid.model;

import com.namekart.auction_api.auction.model.Auction;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bids", indexes = {
        @Index(name = "idx_bid_auction_id", columnList = "auction_id"),
        @Index(name = "idx_bid_bidder_email", columnList = "bidder_email"),
        @Index(name = "idx_bid_bidder_amount", columnList = "bidder_email, amount")
})
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(name = "bidder_email", nullable = false, length = 255)
    private String bidderEmail;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Bid() {}

    public Bid(Auction auction, String bidderEmail, BigDecimal amount) {
        this.auction = auction;
        this.bidderEmail = bidderEmail;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Auction getAuction() {
        return auction;
    }

    public void setAuction(Auction auction) {
        this.auction = auction;
    }

    public String getBidderEmail() {
        return bidderEmail;
    }

    public void setBidderEmail(String bidderEmail) {
        this.bidderEmail = bidderEmail;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
