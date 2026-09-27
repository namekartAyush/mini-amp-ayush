package com.namekart.auction_api.seeder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * High-speed database seeder using JdbcTemplate batch updates
 * capable of loading tens of thousands of domains, auctions, and bids in seconds.
 */
@Service
public class DataSeederService {

    private static final Logger log = LoggerFactory.getLogger(DataSeederService.class);
    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final Random random = new Random(42);

    private static final String[] TLDS = {"com", "net", "org", "io", "ai", "co", "app", "tech", "xyz"};
    private static final String[] PREFIXES = {
            "cloud", "meta", "smart", "cyber", "hyper", "omni", "prime", "apex", "swift", "nova",
            "fin", "pay", "crypto", "block", "mesh", "pulse", "vertex", "nexus", "echo", "flow"
    };
    private static final String[] ROOTS = {
            "scale", "stack", "hub", "node", "core", "vault", "link", "base", "forge", "craft",
            "wave", "drift", "grid", "dock", "zone", "byte", "sync", "line", "path", "cast"
    };

    public DataSeederService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void seedDatabase(int domainCount, int auctionCount, int bidCount) {
        log.info("Starting high-speed database seeding: {} domains, {} auctions, {} bids...",
                domainCount, auctionCount, bidCount);
        long startTime = System.currentTimeMillis();

        // 1. Clean existing records in reverse dependency order
        jdbcTemplate.execute("DELETE FROM bids");
        jdbcTemplate.execute("DELETE FROM auctions");
        jdbcTemplate.execute("DELETE FROM domains");

        // 2. Batch insert domains
        seedDomains(domainCount);

        // 3. Query domain IDs
        List<Long> domainIds = jdbcTemplate.queryForList("SELECT id FROM domains", Long.class);

        // 4. Batch insert auctions
        seedAuctions(domainIds, auctionCount);

        // 5. Query auction IDs
        List<Long> auctionIds = jdbcTemplate.queryForList("SELECT id FROM auctions", Long.class);

        // 6. Batch insert bids
        seedBids(auctionIds, bidCount);

        long totalElapsed = System.currentTimeMillis() - startTime;
        log.info("Seeding completed successfully in {} ms ({} seconds)!", totalElapsed, totalElapsed / 1000.0);
    }

    private void seedDomains(int count) {
        String sql = "INSERT INTO domains (name, tld, estimated_value, status, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        Timestamp now = Timestamp.from(Instant.now());

        for (int i = 1; i <= count; i++) {
            String prefix = PREFIXES[random.nextInt(PREFIXES.length)];
            String root = ROOTS[random.nextInt(ROOTS.length)];
            String tld = TLDS[random.nextInt(TLDS.length)];

            String name = prefix + root + i + "." + tld;
            BigDecimal estValue = BigDecimal.valueOf(100 + random.nextInt(50000));
            String status = (i % 3 == 0) ? "AUCTION" : (i % 10 == 0 ? "SOLD" : "AVAILABLE");

            batch.add(new Object[]{name, tld, estValue, status, now, now});

            if (batch.size() == BATCH_SIZE || i == count) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        log.info("Seeded {} domains.", count);
    }

    private int ROOTSLength() {
        return ROOTS.length;
    }

    private void seedAuctions(List<Long> domainIds, int count) {
        String sql = "INSERT INTO auctions (domain_id, starting_price, reserve_price, current_highest_bid, " +
                     "status, start_time, end_time, created_at, updated_at, version) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        String[] statuses = {"ACTIVE", "ACTIVE", "ACTIVE", "PENDING", "COMPLETED"};

        int max = Math.min(count, domainIds.size());
        for (int i = 0; i < max; i++) {
            Long domainId = domainIds.get(i);
            BigDecimal startPrice = BigDecimal.valueOf(50 + random.nextInt(1000));
            BigDecimal reservePrice = startPrice.add(BigDecimal.valueOf(100 + random.nextInt(2000)));
            BigDecimal highestBid = startPrice.add(BigDecimal.valueOf(random.nextInt(1500)));
            String status = statuses[random.nextInt(statuses.length)];
            Timestamp startTime = Timestamp.from(now.minus(random.nextInt(5), ChronoUnit.DAYS));
            Timestamp endTime = Timestamp.from(now.plus(1 + random.nextInt(10), ChronoUnit.DAYS));

            batch.add(new Object[]{domainId, startPrice, reservePrice, highestBid, status, startTime, endTime, nowTs, nowTs, 0L});

            if (batch.size() == BATCH_SIZE || i == max - 1) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        log.info("Seeded {} auctions.", max);
    }

    private void seedBids(List<Long> auctionIds, int count) {
        String sql = "INSERT INTO bids (auction_id, bidder_email, amount, created_at) VALUES (?, ?, ?, ?)";
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        Instant now = Instant.now();

        for (int i = 1; i <= count; i++) {
            Long auctionId = auctionIds.get(random.nextInt(auctionIds.size()));
            String bidderEmail = "bidder" + (1 + random.nextInt(500)) + "@investorgroup.com";
            BigDecimal amount = BigDecimal.valueOf(100 + random.nextInt(10000));
            Timestamp createdAt = Timestamp.from(now.minus(random.nextInt(72), ChronoUnit.HOURS));

            batch.add(new Object[]{auctionId, bidderEmail, amount, createdAt});

            if (batch.size() == BATCH_SIZE || i == count) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        log.info("Seeded {} bids.", count);
    }
}
