package com.namekart.auction_api.seeder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * CommandLineRunner to trigger high-volume database seeding.
 * Triggered via CLI argument '--seed' or '--seed-large'.
 */
@Component
public class DatabaseSeederRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeederRunner.class);

    private final DataSeederService dataSeederService;
    private final Environment environment;

    public DatabaseSeederRunner(DataSeederService dataSeederService, Environment environment) {
        this.dataSeederService = dataSeederService;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        boolean hasSeed = Arrays.stream(args).anyMatch(arg -> arg.contains("--seed"));
        if (!hasSeed) {
            log.info("DatabaseSeederRunner ready. Run with '--seed' argument to populate tens of thousands of records.");
            return;
        }

        boolean isLarge = Arrays.stream(args).anyMatch(arg -> arg.contains("--seed-large"));
        int domainCount = isLarge ? 25000 : 10000;
        int auctionCount = isLarge ? 20000 : 8000;
        int bidCount = isLarge ? 50000 : 25000;

        log.info("Seeding flag detected! Seeding {} domains, {} auctions, {} bids...",
                domainCount, auctionCount, bidCount);
        dataSeederService.seedDatabase(domainCount, auctionCount, bidCount);
    }
}
