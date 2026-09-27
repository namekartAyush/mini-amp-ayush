package com.namekart.auction_api.registrar.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        @NotBlank(message = "Default registrar provider cannot be blank")
        String defaultProvider,

        @Valid
        @NotNull(message = "Dynadot properties must be configured")
        DynadotProperties dynadot,

        @Valid
        GoDaddyProperties godaddy
) {
    public record DynadotProperties(
            @NotBlank(message = "Dynadot API key cannot be blank")
            String apiKey,

            @NotBlank(message = "Dynadot Base URL cannot be blank")
            String baseUrl,

            @NotNull(message = "Dynadot timeout must be specified")
            @DefaultValue("5s")
            Duration timeout
    ) {}

    public record GoDaddyProperties(
            String apiKey,
            String apiSecret,
            String baseUrl
    ) {}
}
