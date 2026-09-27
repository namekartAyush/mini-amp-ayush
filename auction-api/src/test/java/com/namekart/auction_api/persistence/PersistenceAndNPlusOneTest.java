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
@org.springframework.test.context.ActiveProfiles("test")
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

    @Autowired
    private javax.sql.DataSource dataSource;

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

    @Test
    @DisplayName("Verify Domain composite index (tld, estimated_value) execution plan")
    void testDomainIndexExecutionPlan() {
        dataSeederService.seedDatabase(300, 100, 200);

        List<Map<String, Object>> explainPlan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT * FROM domains WHERE tld = 'com' AND estimated_value >= 5000 ORDER BY estimated_value DESC"
        );

        System.out.println("==================================================");
        System.out.println("EXPLAIN PLAN for domain search (tld + estimated_value):");
        for (Map<String, Object> row : explainPlan) {
            System.out.println("  " + row);
        }
        System.out.println("==================================================");

        assertThat(explainPlan).isNotEmpty();
    }

    @Test
    @DisplayName("Demonstrate two concurrent database sessions, MVCC snapshot isolation and dirty read prevention")
    void testTwoSessionTransactionIsolationExperiment() throws Exception {
        // Setup initial auction
        Domain domain = domainRepository.save(new Domain("isolation-test.com", "com", BigDecimal.valueOf(5000), DomainStatus.AUCTION));
        Auction auction = auctionRepository.save(new Auction(domain, BigDecimal.valueOf(100), BigDecimal.valueOf(200),
                Instant.now(), Instant.now().plus(5, ChronoUnit.DAYS), AuctionStatus.ACTIVE));
        auction.setCurrentHighestBid(BigDecimal.valueOf(500.00));
        auctionRepository.saveAndFlush(auction);

        Long auctionId = auction.getId();
        System.out.println("==================================================");
        System.out.println("TRANSACTION ISOLATION EXPERIMENT (Session 1 vs Session 2)");
        System.out.println("Initial current_highest_bid = " + auction.getCurrentHighestBid());

        try (java.sql.Connection session1 = dataSource.getConnection();
             java.sql.Connection session2 = dataSource.getConnection()) {

            session1.setAutoCommit(false);
            session2.setAutoCommit(false);

            // Step 1: Session 1 mutates the row without committing
            System.out.println("Session 1: START TRANSACTION; UPDATE auctions SET current_highest_bid = 9999.00 WHERE id = " + auctionId);
            try (java.sql.PreparedStatement psUpdate = session1.prepareStatement(
                    "UPDATE auctions SET current_highest_bid = 9999.00 WHERE id = ?")) {
                psUpdate.setLong(1, auctionId);
                int updated = psUpdate.executeUpdate();
                assertThat(updated).isEqualTo(1);
            }

            // Step 2: Session 2 reads the same row while Session 1 is uncommitted
            BigDecimal session2ReadBeforeCommit;
            try (java.sql.PreparedStatement psSelect = session2.prepareStatement(
                    "SELECT current_highest_bid FROM auctions WHERE id = ?")) {
                psSelect.setLong(1, auctionId);
                try (java.sql.ResultSet rs = psSelect.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    session2ReadBeforeCommit = rs.getBigDecimal("current_highest_bid");
                }
            }
            System.out.println("Session 2 (Read BEFORE Session 1 commit): " + session2ReadBeforeCommit);
            // Session 2 should see old value 500.00 (Dirty Read is prevented)
            assertThat(session2ReadBeforeCommit).isEqualByComparingTo(BigDecimal.valueOf(500.00));

            // Step 3: Session 1 commits
            System.out.println("Session 1: COMMIT;");
            session1.commit();

            // Step 4: Session 2 reads again within its active transaction
            BigDecimal session2ReadAfterCommit;
            try (java.sql.PreparedStatement psSelect = session2.prepareStatement(
                    "SELECT current_highest_bid FROM auctions WHERE id = ?")) {
                psSelect.setLong(1, auctionId);
                try (java.sql.ResultSet rs = psSelect.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    session2ReadAfterCommit = rs.getBigDecimal("current_highest_bid");
                }
            }
            System.out.println("Session 2 (Read AFTER Session 1 commit, same transaction): " + session2ReadAfterCommit);

            // Step 5: Session 2 commits its transaction and reads in a fresh transaction
            session2.commit();
            BigDecimal session2FreshRead;
            try (java.sql.PreparedStatement psSelect = session2.prepareStatement(
                    "SELECT current_highest_bid FROM auctions WHERE id = ?")) {
                psSelect.setLong(1, auctionId);
                try (java.sql.ResultSet rs = psSelect.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    session2FreshRead = rs.getBigDecimal("current_highest_bid");
                }
            }
            System.out.println("Session 2 (Fresh transaction after commit): " + session2FreshRead);
            assertThat(session2FreshRead).isEqualByComparingTo(BigDecimal.valueOf(9999.00));
            System.out.println("==================================================");
        }
    }
}

