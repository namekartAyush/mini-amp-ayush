package com.namekart.auction_api.registrar.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Immutable typed configuration for registrar integrations.
 * Mapped to the 'registrar' prefix in application properties.
 */
@Validated
@ConfigurationProperties(prefix = "registrar")
public record RegistrarProperties(
        @NotBlank String defaultProvider,
        DynadotProperties dynadot,
        GoDaddyProperties godaddy
) {
    public record DynadotProperties(
            @NotBlank String apiKey,
            @NotBlank String baseUrl,
            @DefaultValue("5s") Duration timeout
    ) {}

    public record GoDaddyProperties(
            String apiKey,
            String apiSecret,
            String baseUrl
    ) {}
}
