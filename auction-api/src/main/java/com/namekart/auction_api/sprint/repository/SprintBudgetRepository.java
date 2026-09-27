package com.namekart.auction_api.sprint.repository;

import com.namekart.auction_api.sprint.model.SprintBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SprintBudgetRepository extends JpaRepository<SprintBudget, Long> {

    Optional<SprintBudget> findByBudgetCode(String budgetCode);

    /**
     * Atomic conditional database update that eliminates race conditions across multiple JVM instances.
     * Decrements remaining_cents ONLY IF remaining_cents >= amount.
     * Returns 1 if deduction succeeded, 0 if insufficient budget.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE SprintBudget b SET b.remainingCents = b.remainingCents - :amount " +
           "WHERE b.budgetCode = :code AND b.remainingCents >= :amount")
    int deductBudgetConditional(@Param("code") String code, @Param("amount") long amount);
}
