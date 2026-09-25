package com.namekart.auction_api.registrar.client;

/**
 * Common contract for external domain registrar clients.
 */
public interface RegistrarClient {

    /**
     * @return provider name (e.g. dynadot, godaddy)
     */
    String getProviderName();

    /**
     * Check if a domain name is available for registration.
     */
    boolean isDomainAvailable(String domainName);
}
