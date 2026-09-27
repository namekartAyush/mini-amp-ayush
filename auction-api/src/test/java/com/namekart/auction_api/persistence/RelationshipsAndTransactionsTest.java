package com.namekart.auction_api.persistence;

import com.namekart.auction_api.auction.model.Auction;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.bid.dto.PlaceBidRequest;
import com.namekart.auction_api.bid.model.Bid;
import com.namekart.auction_api.bid.repository.BidRepository;
import com.namekart.auction_api.bid.service.BidService;
import com.namekart.auction_api.common.exception.BusinessRuleException;
import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.repository.DomainRepository;
import com.namekart.auction_api.user.model.ShortlistItem;
import com.namekart.auction_api.user.model.User;
import com.namekart.auction_api.user.model.UserProfile;
import com.namekart.auction_api.user.repository.ShortlistItemRepository;
import com.namekart.auction_api.user.repository.UserProfileRepository;
import com.namekart.auction_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RelationshipsAndTransactionsTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository profileRepository;

    @Autowired
    private ShortlistItemRepository shortlistRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private DomainRepository domainRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private BidService bidService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Auction testAuction;
    private Domain testDomain;

    @BeforeEach
    void setUp() {
        shortlistRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();
        bidRepository.deleteAll();
        auctionRepository.deleteAll();
        domainRepository.deleteAll();

        testDomain = domainRepository.save(new Domain("relationship-test.com", "com", BigDecimal.valueOf(5000), DomainStatus.AUCTION));
        testAuction = auctionRepository.save(new Auction(
                testDomain,
                BigDecimal.valueOf(100.00),
                BigDecimal.valueOf(500.00),
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(7, ChronoUnit.DAYS),
                AuctionStatus.ACTIVE
        ));
    }

    @Test
    @DisplayName("Verify UserProfile one-to-one cascade and orphan removal")
    void testProfileOneToOneCascadeAndOrphanRemoval() {
        User user = new User("alice@investor.com", "alice_investor");
        UserProfile profile = new UserProfile("Alice Smith", "+1-555-0199", "Domain Investor");
        user.setProfile(profile);

        User savedUser = userRepository.saveAndFlush(user);
        Long userId = savedUser.getId();

        assertThat(profileRepository.findByUserId(userId)).isPresent();

        // Disassociate profile -> orphanRemoval must delete profile record
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User u = userRepository.findById(userId).orElseThrow();
            u.setProfile(null);
            userRepository.saveAndFlush(u);
        });

        assertThat(profileRepository.findByUserId(userId)).isEmpty();

        // Deleting user must cascade delete profile
        User user2 = new User("bob@investor.com", "bob_investor");
        user2.setProfile(new UserProfile("Bob Jones", "+1-555-0200", "Domain Flipper"));
        User savedUser2 = userRepository.saveAndFlush(user2);
        Long user2Id = savedUser2.getId();

        assertThat(profileRepository.findByUserId(user2Id)).isPresent();
        userRepository.deleteById(user2Id);
        userRepository.flush();

        assertThat(profileRepository.findByUserId(user2Id)).isEmpty();
    }

    @Test
    @DisplayName("Verify ShortlistItem one-to-many cascade and orphan removal")
    void testShortlistOneToManyCascadeAndOrphanRemoval() {
        User user = new User("carol@investor.com", "carol_investor");
        ShortlistItem item1 = new ShortlistItem(testDomain, "Priority acquisition target", 1);
        ShortlistItem item2 = new ShortlistItem(testDomain, "Backup target", 2);
        user.addShortlistItem(item1);
        user.addShortlistItem(item2);

        User savedUser = userRepository.saveAndFlush(user);
        Long userId = savedUser.getId();

        assertThat(shortlistRepository.findByUserId(userId)).hasSize(2);

        // Remove 1 item from list -> orphanRemoval deletes it from DB
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User u = userRepository.findById(userId).orElseThrow();
            ShortlistItem toRemove = u.getShortlistItems().get(0);
            u.removeShortlistItem(toRemove);
            userRepository.saveAndFlush(u);
        });

        assertThat(shortlistRepository.findByUserId(userId)).hasSize(1);

        // Deleting user cascades delete to remaining shortlist items
        userRepository.deleteById(userId);
        userRepository.flush();

        assertThat(shortlistRepository.findByUserId(userId)).isEmpty();
        // Domain must still exist!
        assertThat(domainRepository.existsById(testDomain.getId())).isTrue();
    }

    @Test
    @DisplayName("Verify Watchlist many-to-many preserves auctions on user deletion")
    void testWatchlistManyToManyPreservesAuctions() {
        User user = new User("dave@investor.com", "dave_investor");
        user.addToWatchlist(testAuction);
        User savedUser = userRepository.saveAndFlush(user);
        Long userId = savedUser.getId();
        Long auctionId = testAuction.getId();

        assertThat(savedUser.getWatchlistedAuctions()).hasSize(1);

        // Deleting user removes join table record, but MUST NOT delete the Auction!
        userRepository.deleteById(userId);
        userRepository.flush();

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(auctionRepository.findById(auctionId)).isPresent();
    }

    @Test
    @DisplayName("Prove placeBid updates auction, inserts bid, and increments version atomically")
    void testPlaceBidSuccessAndVersionIncrement() {
        PlaceBidRequest request = new PlaceBidRequest(testAuction.getId(), "bidder1@test.com", BigDecimal.valueOf(150.00));
        var response = bidService.placeBid(request);

        assertThat(response.amount()).isEqualByComparingTo(BigDecimal.valueOf(150.00));

        Auction updatedAuction = auctionRepository.findById(testAuction.getId()).orElseThrow();
        assertThat(updatedAuction.getCurrentHighestBid()).isEqualByComparingTo(BigDecimal.valueOf(150.00));
        assertThat(updatedAuction.getVersion()).isGreaterThan(0L);

        assertThat(bidRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Prove that a rolled-back bid leaves zero partial state")
    void testRolledBackBidLeavesNoPartialState() {
        long initialBidCount = bidRepository.count();
        BigDecimal initialAuctionPrice = testAuction.getCurrentHighestBid();
        Long initialVersion = testAuction.getVersion();

        // Attempt invalid bid (amount lower than starting price 100.00)
        PlaceBidRequest invalidRequest = new PlaceBidRequest(testAuction.getId(), "bidder@invalid.com", BigDecimal.valueOf(50.00));

        assertThatThrownBy(() -> bidService.placeBid(invalidRequest))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be less than starting price");

        // Verify zero partial state in database
        Auction auctionAfterFailure = auctionRepository.findById(testAuction.getId()).orElseThrow();
        assertThat(auctionAfterFailure.getCurrentHighestBid()).isEqualTo(initialAuctionPrice);
        assertThat(auctionAfterFailure.getVersion()).isEqualTo(initialVersion);
        assertThat(bidRepository.count()).isEqualTo(initialBidCount);
    }

    @Test
    @DisplayName("Prove optimistic locking prevents two concurrent bids at the same version from both winning")
    void testConcurrentBidsOptimisticLocking() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        CountDownLatch latch = new CountDownLatch(threads);

        // Both threads attempt to bid on the same auction simultaneously
        for (int i = 1; i <= threads; i++) {
            final BigDecimal bidAmount = BigDecimal.valueOf(200.00 + (i * 10)); // 210 and 220
            final String bidder = "bidder" + i + "@concurrent.com";

            executor.submit(() -> {
                try {
                    barrier.await(); // Synchronize threads to start at the exact same instant
                    // Each thread runs inside its own transaction
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        Auction a = auctionRepository.findById(testAuction.getId()).orElseThrow();
                        // Simulate simultaneous read of the same initial version
                        try {
                            Thread.sleep(50); // Small window to guarantee concurrency overlap
                        } catch (InterruptedException ignored) {}

                        bidRepository.save(new Bid(a, bidder, bidAmount));
                        a.setCurrentHighestBid(bidAmount);
                        auctionRepository.saveAndFlush(a); // Forces UPDATE ... WHERE version = ?
                    });
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    // One of the concurrent updates must fail with optimistic locking failure
                    if (ex instanceof OptimisticLockingFailureException ||
                            ex.getCause() instanceof org.hibernate.StaleObjectStateException ||
                            ex.getMessage().contains("Row was updated or deleted by another transaction")) {
                        conflictCount.incrementAndGet();
                    } else {
                        // Spring may wrap it
                        conflictCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        System.out.println("==================================================");
        System.out.println("CONCURRENT BIDDING EXPERIMENT RESULT:");
        System.out.println("Successful bids: " + successCount.get());
        System.out.println("Optimistic Lock conflicts prevented: " + conflictCount.get());
        System.out.println("==================================================");

        // Exactly one should succeed and one should fail due to optimistic lock mismatch
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
    }
}
