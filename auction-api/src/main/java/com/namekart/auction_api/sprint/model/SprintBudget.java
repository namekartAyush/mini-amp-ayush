package com.namekart.auction_api.sprint.model;

import jakarta.persistence.*;

/**
 * Shared budget entity for multi-instance closing sprint bidding.
 * Enforces atomic database-level budget deduction via conditional SQL update or optimistic locking.
 */
@Entity
@Table(name = "sprint_budgets")
public class SprintBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "budget_code", nullable = false, unique = true, length = 64)
    private String budgetCode;

    @Column(name = "total_cents", nullable = false)
    private Long totalCents;

    @Column(name = "remaining_cents", nullable = false)
    private Long remainingCents;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public SprintBudget() {
    }

    public SprintBudget(String budgetCode, Long totalCents, Long remainingCents) {
        this.budgetCode = budgetCode;
        this.totalCents = totalCents;
        this.remainingCents = remainingCents;
    }

    public Long getId() {
        return id;
    }

    public String getBudgetCode() {
        return budgetCode;
    }

    public void setBudgetCode(String budgetCode) {
        this.budgetCode = budgetCode;
    }

    public Long getTotalCents() {
        return totalCents;
    }

    public void setTotalCents(Long totalCents) {
        this.totalCents = totalCents;
    }

    public Long getRemainingCents() {
        return remainingCents;
    }

    public void setRemainingCents(Long remainingCents) {
        this.remainingCents = remainingCents;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
