package com.namekart.auction_api.registrar.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Cache configuration with deliberate TTL expiry using Caffeine.
 */
@Configuration
@EnableCaching
public class RegistrarCacheConfig {

    public static final String REGISTRAR_AUCTIONS_CACHE = "registrarAuctions";
    public static final String REGISTRAR_CHECK_CACHE = "registrarCheck";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(REGISTRAR_AUCTIONS_CACHE, REGISTRAR_CHECK_CACHE);
        // Deliberate TTL: 5 minutes expiry after write, maximum 500 entries
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500)
                .recordStats());
        return cacheManager;
    }
}
