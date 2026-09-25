package com.namekart.auction_api.warmup;

import java.util.List;

/**
 * Standalone entry point for P1 Java warm-up.
 * Runnable directly from command line via Maven:
 * ./mvnw compile exec:java -Dexec.mainClass="com.namekart.auction_api.warmup.DomainWarmupApp"
 */
public class DomainWarmupApp {

    public static void main(String[] args) {
        try {
            DomainWarmupService service = new DomainWarmupService();
            List<DomainRecord> domains = service.loadDomains();
            service.processAndPrintStats(domains);
        } catch (Exception e) {
            System.err.println("Error running P1 Domain Warm-up: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
