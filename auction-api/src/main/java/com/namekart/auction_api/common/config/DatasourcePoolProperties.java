package com.namekart.auction_api.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Validated immutable configuration for database connection pool settings.
 * Fails fast on startup if values are missing, non-positive, or out of reasonable bounds.
 */
@Validated
@ConfigurationProperties(prefix = "app.datasource")
public record DatasourcePoolProperties(
        @NotNull(message = "Database connection pool size must be configured")
        @Min(value = 2, message = "Database pool size must be at least 2")
        @Max(value = 100, message = "Database pool size cannot exceed 100")
        Integer poolSize,

        @NotNull(message = "Connection timeout must be specified")
        @DefaultValue("30s")
        Duration connectionTimeout,

        @NotNull(message = "Idle timeout must be specified")
        @DefaultValue("10m")
        Duration idleTimeout
) {}
