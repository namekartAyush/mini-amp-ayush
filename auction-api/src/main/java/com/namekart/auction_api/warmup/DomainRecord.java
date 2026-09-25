package com.namekart.auction_api.warmup;

/**
 * DomainRecord representing a domain item in the auction system portfolio.
 * Implemented as a Java 17+ / 21 Record.
 *
 * @param name           e.g. "cloudtech.com"
 * @param tld            e.g. "com", "io", "ai", "org"
 * @param length         length of the domain name
 * @param estimatedValue estimated market valuation in USD
 * @param status         status e.g. "AVAILABLE", "AUCTION", "SOLD", "RESERVED"
 */
public record DomainRecord(
    String name,
    String tld,
    int length,
    double estimatedValue,
    String status
) {}
