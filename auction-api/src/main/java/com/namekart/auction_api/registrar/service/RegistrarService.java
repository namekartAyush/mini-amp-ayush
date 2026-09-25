package com.namekart.auction_api.registrar.service;

import com.namekart.auction_api.registrar.client.RegistrarClient;
import com.namekart.auction_api.registrar.config.RegistrarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service orchestrating registrar operations and provider configuration.
 * Strictly uses constructor injection.
 */
@Service
public class RegistrarService {

    private static final Logger log = LoggerFactory.getLogger(RegistrarService.class);

    private final RegistrarProperties properties;
    private final RegistrarClient registrarClient;

    public RegistrarService(RegistrarProperties properties, RegistrarClient registrarClient) {
        this.properties = properties;
        this.registrarClient = registrarClient;
    }

    public String getDefaultProvider() {
        return properties.defaultProvider();
    }

    public boolean checkAvailability(String domainName) {
        log.info("Delegating availability check for '{}' to client: {}", domainName, registrarClient.getProviderName());
        return registrarClient.isDomainAvailable(domainName);
    }
}
