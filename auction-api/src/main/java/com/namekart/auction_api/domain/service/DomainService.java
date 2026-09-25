package com.namekart.auction_api.domain.service;

import com.namekart.auction_api.common.exception.BusinessRuleException;
import com.namekart.auction_api.common.exception.ResourceNotFoundException;
import com.namekart.auction_api.domain.dto.CreateDomainRequest;
import com.namekart.auction_api.domain.dto.DomainResponse;
import com.namekart.auction_api.domain.dto.UpdateDomainRequest;
import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.repository.DomainRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DomainService {

    private final DomainRepository domainRepository;

    public DomainService(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    public DomainResponse createDomain(CreateDomainRequest request) {
        String normalizedName = request.name().trim().toLowerCase();
        if (domainRepository.existsByNameIgnoreCase(normalizedName)) {
            throw new BusinessRuleException(String.format("Domain with name '%s' already exists", normalizedName));
        }

        String tld = "";
        int dotIndex = normalizedName.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < normalizedName.length() - 1) {
            tld = normalizedName.substring(dotIndex + 1);
        }

        Domain domain = new Domain(
                normalizedName,
                tld,
                request.estimatedValue(),
                request.status() != null ? request.status() : DomainStatus.AVAILABLE
        );

        Domain saved = domainRepository.save(domain);
        return DomainResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public DomainResponse getDomainById(Long id) {
        Domain domain = domainRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Domain", id));
        return DomainResponse.fromEntity(domain);
    }

    @Transactional(readOnly = true)
    public Page<DomainResponse> listDomains(DomainStatus status, Pageable pageable) {
        Page<Domain> domains = (status != null)
                ? domainRepository.findByStatus(status, pageable)
                : domainRepository.findAll(pageable);
        return domains.map(DomainResponse::fromEntity);
    }

    public DomainResponse updateDomain(Long id, UpdateDomainRequest request) {
        Domain domain = domainRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Domain", id));

        if (request.estimatedValue() != null) {
            domain.setEstimatedValue(request.estimatedValue());
        }
        if (request.status() != null) {
            domain.setStatus(request.status());
        }

        Domain updated = domainRepository.save(domain);
        return DomainResponse.fromEntity(updated);
    }
 
    public void deleteDomain(Long id) {
        Domain domain = domainRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Domain", id));
        domainRepository.delete(domain);
    }
}
