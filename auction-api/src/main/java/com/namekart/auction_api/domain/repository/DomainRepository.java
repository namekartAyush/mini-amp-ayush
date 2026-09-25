package com.namekart.auction_api.domain.repository;

import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DomainRepository extends JpaRepository<Domain, Long> {
    Optional<Domain> findByNameIgnoreCase(String name);
    boolean existsByNa meIgnoreCase(String name);
    Page<Domain> findByStatus(DomainStatus status, Pageable pageable);
}
