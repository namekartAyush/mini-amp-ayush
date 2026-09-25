package com.namekart.auction_api.warmup;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class DomainWarmupRunner implements CommandLineRunner {

    private final DomainWarmupService warmupService;

    public DomainWarmupRunner(DomainWarmupService warmupService) {
        this.warmupService = warmupService;
    }

    @Override
    public void run(String... args) throws Exception {
        boolean explicitWarmupFlag = Arrays.asList(args).contains("--warmup");
        if (explicitWarmupFlag) {
            List<DomainRecord> domains = warmupService.loadDomains();
            warmupService.processAndPrintStats(domains);
        } else {
            System.out.println("DomainWarmupRunner ready. Run with '--warmup' argument or via Maven CLI to execute P1 Java Warm-up.");
        }
    }
}
