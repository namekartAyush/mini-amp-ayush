package com.namekart.auction_api.warmup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DomainWarmupService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<DomainRecord> loadDomains() throws Exception {
        // Try reading from current working directory first, fallback to classpath
        File file = new File("domains.json");
        if (file.exists()) {
            return objectMapper.readValue(file, new TypeReference<List<DomainRecord>>() {});
        }

        try (InputStream is = getClass().getResourceAsStream("/domains.json")) {
            if (is == null) {
                throw new IllegalStateException("domains.json not found in working directory or classpath");
            }
            return objectMapper.readValue(is, new TypeReference<List<DomainRecord>>() {});
        }
    }

    public void processAndPrintStats(List<DomainRecord> domains) {
        System.out.println("===============================================================================");
        System.out.println("                       P1: JAVA WARM-UP STATISTICS                            ");
        System.out.println("===============================================================================");
        System.out.println("Total domain records loaded : " + domains.size());

        // 1. Group by TLD and count
        Map<String, Long> tldCounts = domains.stream()
                .collect(Collectors.groupingBy(DomainRecord::tld, Collectors.counting()));

        System.out.println("\n1. Domain Distribution by TLD:");
        tldCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf("   - .%-8s : %d domains%n", e.getKey(), e.getValue()));

        // 2. Filter High-Value / Premium Domains (> $10,000)
        List<DomainRecord> premiumDomains = domains.stream()
                .filter(d -> d.estimatedValue() >= 10000.0)
                .sorted(Comparator.comparingDouble(DomainRecord::estimatedValue).reversed())
                .toList();

        System.out.printf("%n2. Premium Domains (Valuation >= $10,000): Count = %d%n", premiumDomains.size());
        System.out.println("   Top 5 Premium Domains:");
        premiumDomains.stream().limit(5).forEach(d -> 
            System.out.printf("   * %-25s | TLD: .%-5s | Value: $%,10.2f | Status: %s%n",
                    d.name(), d.tld(), d.estimatedValue(), d.status())
        );

        // 3. Status Breakdown
        Map<String, Long> statusCounts = domains.stream()
                .collect(Collectors.groupingBy(DomainRecord::status, Collectors.counting()));

        System.out.println("\n3. Portfolio Breakdown by Status:");
        statusCounts.forEach((status, count) -> 
            System.out.printf("   - %-12s : %d domains (%.1f%%)%n", 
                    status, count, (count * 100.0 / domains.size()))
        );

        // 4. Valuation Statistics
        DoubleSummaryStatistics stats = domains.stream()
                .mapToDouble(DomainRecord::estimatedValue)
                .summaryStatistics();

        System.out.println("\n4. Portfolio Financial Summary:");
        System.out.printf("   - Total Valuation   : $%,.2f%n", stats.getSum());
        System.out.printf("   - Average Valuation : $%,.2f%n", stats.getAverage());
        System.out.printf("   - Lowest Valuation  : $%,.2f%n", stats.getMin());
        System.out.printf("   - Highest Valuation : $%,.2f%n", stats.getMax());
        System.out.println("===============================================================================");
    }
}
