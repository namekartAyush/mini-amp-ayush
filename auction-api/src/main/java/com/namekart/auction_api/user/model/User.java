package com.namekart.auction_api.user.model;

import com.namekart.auction_api.auction.model.Auction;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_user_email", columnList = "email", unique = true),
        @Index(name = "idx_user_username", columnList = "username", unique = true)
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * Profile One-to-One:
     * Deliberate choice: CascadeType.ALL and orphanRemoval = true.
     * A UserProfile has no independent existence without its owning User.
     * When a User is deleted or the profile disassociated, the profile is permanently deleted.
     */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private UserProfile profile;

    /**
     * Shortlist One-to-Many:
     * Deliberate choice: CascadeType.ALL and orphanRemoval = true.
     * Shortlist items represent private user saved items with custom notes.
     * If an item is removed from the list or the User is deleted, the shortlist item is deleted.
     */
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ShortlistItem> shortlistItems = new ArrayList<>();

    /**
     * Watchlist Many-to-Many:
     * Deliberate choice: CascadeType.PERSIST, CascadeType.MERGE ONLY.
     * NO CascadeType.REMOVE and NO orphanRemoval.
     * Auctions exist independently of user watchlists.
     * If a user is deleted, watched auctions must NEVER be deleted.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_watchlist",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "auction_id")
    )
    private Set<Auction> watchlistedAuctions = new HashSet<>();

    public User() {}

    public User(String email, String username) {
        this.email = email;
        this.username = username;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UserProfile getProfile() {
        return profile;
    }

    public void setProfile(UserProfile profile) {
        this.profile = profile;
        if (profile != null) {
            profile.setUser(this);
        }
    }

    public List<ShortlistItem> getShortlistItems() {
        return shortlistItems;
    }

    public void addShortlistItem(ShortlistItem item) {
        shortlistItems.add(item);
        item.setUser(this);
    }

    public void removeShortlistItem(ShortlistItem item) {
        shortlistItems.remove(item);
        item.setUser(null);
    }

    public Set<Auction> getWatchlistedAuctions() {
        return watchlistedAuctions;
    }

    public void addToWatchlist(Auction auction) {
        watchlistedAuctions.add(auction);
    }

    public void removeFromWatchlist(Auction auction) {
        watchlistedAuctions.remove(auction);
    }
}
