package com.namekart.auction_api.domain.dto;

import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record DomainResponse(
        Long id,
        String name,
        String tld,
        BigDecimal estimatedValue,
        DomainStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static DomainResponse fromEntity(Domain domain) {
        return new DomainResponse(
                domain.getId(),
                domain.getName(),
                domain.getTld(),
                domain.getEstimatedValue(),
                domain.getStatus(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }
}
