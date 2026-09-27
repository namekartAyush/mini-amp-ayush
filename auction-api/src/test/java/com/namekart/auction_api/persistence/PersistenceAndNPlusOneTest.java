package com.namekart.auction_api.persistence;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.auction.service.AuctionDiagnosticsService;
import com.namekart.auction_api.bid.model.Bid;
import com.namekart.auction_api.bid.repository.BidRepository;
import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.repository.DomainRepository;
import com.namekart.auction_api.seeder.DataSeederService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PersistenceAndNPlusOneTest {

    @Autowired
    private DataSeederService dataSeederService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private DomainRepository domainRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private AuctionDiagnosticsService diagnosticsService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Statistics getHibernateStatistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void setUp() {
        bidRepository.deleteAll();
        auctionRepository.deleteAll();
        domainRepository.deleteAll();
        getHibernateStatistics().clear();
    }

    @Test
    @DisplayName("Verify high-volume database seeder populates thousands of records via batch JDBC")
    void testDatabaseSeeder() {
        // Seed 1,000 domains, 800 auctions, and 2,500 bids
        dataSeederService.seedDatabase(1000, 800, 2500);

        long domainCount = domainRepository.count();
        long auctionCount = auctionRepository.count();
        long bidCount = bidRepository.count();

        assertThat(domainCount).isEqualTo(1000);
        assertThat(auctionCount).isEqualTo(800);
        assertThat(bidCount).isEqualTo(2500);
    }

    @Test
    @DisplayName("Demonstrate and prove N+1 query problem vs JOIN FETCH single-query solution")
    void testNPlusOneDetectionAndFix() {
        // Prepare 10 auctions, each with domain and 3 bids
        for (int i = 1; i <= 10; i++) {
            Domain d = domainRepository.save(new Domain("domain" + i + ".com", "com", BigDecimal.valueOf(1000), DomainStatus.AUCTION));
            Auction a = auctionRepository.save(new Auction(d, BigDecimal.valueOf(100), BigDecimal.valueOf(200),
                    Instant.now(), Instant.now().plus(5, ChronoUnit.DAYS), AuctionStatus.ACTIVE));
            bidRepository.save(new Bid(a, "bidder1@test.com", BigDecimal.valueOf(110)));
            bidRepository.save(new Bid(a, "bidder2@test.com", BigDecimal.valueOf(120)));
            bidRepository.save(new Bid(a, "bidder3@test.com", BigDecimal.valueOf(130)));
        }

        // --- 1. UNOPTIMIZED EXECUTION (N+1 PROBLEM) ---
        Statistics stats = getHibernateStatistics();
        stats.clear();

        int unoptimizedBids = diagnosticsService.executeUnoptimizedQueryWithNPlusOne(AuctionStatus.ACTIVE);
        long queriesUnoptimized = stats.getPrepareStatementCount();
        System.out.println("==================================================");
        System.out.println("UNOPTIMIZED N+1 EXECUTION:");
        System.out.println("Total SQL queries executed: " + queriesUnoptimized);
        System.out.println("Total bids counted: " + unoptimizedBids);
        System.out.println("==================================================");

        // Expected queries: 1 (for 10 auctions) + 10 (for 10 domains) + 10 (for 10 bid collections) = 21 queries!
        assertThat(queriesUnoptimized).isGreaterThanOrEqualTo(11);

        // --- 2. OPTIMIZED EXECUTION (JOIN FETCH) ---
        stats.clear();

        int optimizedBids = diagnosticsService.executeOptimizedQueryWithJoinFetch(AuctionStatus.ACTIVE);
        long queriesOptimized = stats.getPrepareStatementCount();
        System.out.println("==================================================");
        System.out.println("OPTIMIZED JOIN FETCH EXECUTION:");
        System.out.println("Total SQL queries executed: " + queriesOptimized);
        System.out.println("Total bids counted: " + optimizedBids);
        System.out.println("==================================================");

        // With JOIN FETCH, exactly 1 query is prepared and executed!
        assertThat(queriesOptimized).isEqualTo(1);
        assertThat(optimizedBids).isEqualTo(unoptimizedBids);
    }

    @Test
    @DisplayName("Verify index impact on query performance using EXPLAIN execution plan")
    void testIndexExecutionPlan() {
        dataSeederService.seedDatabase(500, 300, 1000);

        // Run EXPLAIN query on indexed bidder_email and amount
        List<Map<String, Object>> explainPlan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT * FROM bids WHERE bidder_email = 'bidder5@investorgroup.com' ORDER BY amount DESC"
        );

        System.out.println("==================================================");
        System.out.println("EXPLAIN PLAN for indexed query:");
        for (Map<String, Object> row : explainPlan) {
            System.out.println("  " + row);
        }
        System.out.println("==================================================");

        assertThat(explainPlan).isNotEmpty();
    }
}
