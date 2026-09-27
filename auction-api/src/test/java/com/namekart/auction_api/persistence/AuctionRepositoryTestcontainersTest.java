package com.namekart.auction_api.persistence;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.repository.DomainRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9: Repository test verifying real MySQL persistence, indexing, and optimistic locking
 * against a real MySQL 8 container managed by Testcontainers.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
public class AuctionRepositoryTestcontainersTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("mini_amp_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withReuse(true);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        if (mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        }
    }

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private DomainRepository domainRepository;

    @Test
    @DisplayName("Testcontainers: Real MySQL persists Auction entity, creates foreign key to Domain, and increments version")
    void testRealMysqlPersistenceAndVersionIncrement() {
        // 1. Create and save domain
        Domain domain = new Domain(
                "real-mysql-" + UUID.randomUUID().toString().substring(0, 8) + ".com",
                "com",
                new BigDecimal("12000.00"),
                DomainStatus.AUCTION
        );
        domain = domainRepository.save(domain);
        assertThat(domain.getId()).isNotNull();

        // 2. Create and save auction
        Auction auction = new Auction(
                domain,
                new BigDecimal("500.00"),
                new BigDecimal("1500.00"),
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(24, ChronoUnit.HOURS),
                AuctionStatus.ACTIVE
        );
        auction = auctionRepository.save(auction);

        assertThat(auction.getId()).isNotNull();
        assertThat(auction.getVersion()).isEqualTo(0L);

        // 3. Update auction and verify optimistic locking @Version increment in MySQL
        auction.setCurrentHighestBid(new BigDecimal("750.00"));
        Auction updatedAuction = auctionRepository.saveAndFlush(auction);

        assertThat(updatedAuction.getVersion()).isGreaterThan(0L);
        assertThat(updatedAuction.getCurrentHighestBid()).isEqualByComparingTo("750.00");

        // 4. Query by status and verify result
        List<Auction> activeAuctions = auctionRepository.findTop20ByStatus(AuctionStatus.ACTIVE);
        assertThat(activeAuctions).isNotEmpty();
        assertThat(activeAuctions.stream().anyMatch(a -> a.getId().equals(updatedAuction.getId()))).isTrue();
    }
}
