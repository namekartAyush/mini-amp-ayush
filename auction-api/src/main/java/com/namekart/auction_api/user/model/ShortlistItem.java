package com.namekart.auction_api.user.model;

import com.namekart.auction_api.domain.model.Domain;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "shortlist_items", indexes = {
        @Index(name = "idx_shortlist_user_id", columnList = "user_id"),
        @Index(name = "idx_shortlist_domain_id", columnList = "domain_id")
})
public class ShortlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "domain_id", nullable = false)
    private Domain domain;

    @Column(length = 255)
    private String notes;

    @Column(nullable = false)
    private int priority = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ShortlistItem() {}

    public ShortlistItem(Domain domain, String notes, int priority) {
        this.domain = domain;
        this.notes = notes;
        this.priority = priority;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Domain getDomain() {
        return domain;
    }

    public void setDomain(Domain domain) {
        this.domain = domain;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
