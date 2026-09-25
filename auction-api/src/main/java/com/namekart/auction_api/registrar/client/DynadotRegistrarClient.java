package com.namekart.auction_api.registrar.client;

import com.namekart.auction_api.registrar.config.RegistrarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dynadot registrar API client implementation.
 * Uses constructor injection to receive immutable typed configuration properties.
 */
@Component
public class DynadotRegistrarClient implements RegistrarClient {

    private static final Logger log = LoggerFactory.getLogger(DynadotRegistrarClient.class);

    private final RegistrarProperties properties;

    public DynadotRegistrarClient(RegistrarProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getProviderName() {
        return "dynadot";
    }

    @Override
    public boolean isDomainAvailable(String domainName) {
        log.info("Checking domain availability for '{}' via Dynadot endpoint '{}' with timeout {}",
                domainName,
                properties.dynadot() != null ? properties.dynadot().baseUrl() : "N/A",
                properties.dynadot() != null ? properties.dynadot().timeout() : "N/A");
        // Simulated response; in future milestones, calls external Dynadot HTTP API
        return true;
    }
}
