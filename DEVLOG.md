# Development Log

## P0 · Skeleton
- **Built:** Local repository structure (`.gitignore`, `.env.example`, `README.md`, `DEVLOG.md`), Docker Compose setup with healthchecks for MySQL 8.0, KRaft Kafka, and Kafka UI, and a Spring Boot 3 app (`auction-api`) configured with Web, Data JPA, Validation, Actuator, and MySQL Driver.
- **Surprised me:** How KRaft mode eliminates the need for ZooKeeper entirely and simplifies broker configuration.
- **Verification:** Actuator health endpoint (`/actuator/health`) returns HTTP 200 with `{"status":"UP"}` indicating clean DB connectivity.

## P1 · Java Warm-up
- **Built:** Java 21 Record model `DomainRecord`, `DomainWarmupService`, `DomainWarmupRunner` (`CommandLineRunner`), `DomainWarmupApp` (standalone CLI main class), and a 2,500 domain JSON dataset (`domains.json`).
- **Functionality:** Parsed domain dataset using Jackson, performed Java Stream operations to filter high-value/premium domains, group domains by TLD, compute status distributions, and calculate portfolio valuation statistics (total, average, min, max).
- **Surprised me:** How compact and readable Java Records and Stream Collectors (`groupingBy`, `summaryStatistics`) make complex data transformations compared to legacy Java boilerplate.
- **Verification:** Executable from Maven CLI via `./mvnw compile exec:java "-Dexec.mainClass=com.namekart.auction_api.warmup.DomainWarmupApp"` and via Spring Boot arguments `--warmup`.

## P2 · Architecture, Packaging & Bean Graph
- **Built:** Package-by-Feature layout, typed configuration record `@ConfigurationProperties` (`RegistrarProperties`), strict constructor injection across all beans (`DynadotRegistrarClient`, `RegistrarService`, `RegistrarController`), and automated integration test (`RegistrarBeanGraphTest`).
- **Package Layout Choice & Justification (Feature vs. Layer):**
  - **Choice:** **Package by Feature (Vertical Slices)** (`com.namekart.auction_api.registrar.*`).
  - **Justification:**
    1. *High Cohesion & Low Coupling:* In a high-throughput domain auction platform, components operating on registrar integrations (properties, external HTTP client, service orchestrator, REST endpoints) evolve together. Colocating them avoids navigating across disjoint layer packages (`service`, `controller`, `repository`) scattered across the project.
    2. *Enforced Boundary & Modularity:* Feature slicing prevents cross-cutting leakage and circular dependencies. When additional features like `auction` and `bidding` are added, each feature can encapsulate its own internal state and expose only a minimal public API.
    3. *Traceable Bean Graph:* Dependency flow remains unidirectional and deterministic. Every bean's collaborators can be reasoned about without jumping through unrelated global layers.
- **Bean Graph (Registrar Feature):**
  ```text
  [RegistrarProperties (Record, @ConfigurationProperties)]
         │
         ├───> [DynadotRegistrarClient (implements RegistrarClient)]
         │               │
         └───────────────┴───> [RegistrarService]
                                      │
                                      ▼
                             [RegistrarController]
  ```
- **Surprised me:** How seamless Java Records integrate with Spring Boot 3 `@ConfigurationProperties` and Jakarta Validation (`@Validated`, `@NotBlank`), creating immutable, fail-fast configuration without any boilerplate or setters.
- **Verification:** `RegistrarBeanGraphTest` runs with `./mvnw test` passing 3/3 tests (validating typed properties binding, timeout duration parsing, and constructor-injected bean resolution).

## P3 · Domain & Auction CRUD, Request Validation & ProblemDetail
- **Built:** 
  - Complete REST CRUD endpoints for `Domain` (`/api/domains`) and `Auction` (`/api/auctions`).
  - Request validation using Jakarta Validation (`@NotBlank`, `@Pattern`, `@PositiveOrZero`, `@DecimalMin`, `@Future`, `@NotNull`).
  - Spring Data pagination and sorting via `Pageable` on all collection endpoints (`GET /api/domains`, `GET /api/auctions`).
  - Global Exception Handler (`GlobalExceptionHandler`) utilizing RFC 7807 `org.springframework.http.ProblemDetail`.
  - Comprehensive integration test suite (`CrudAndValidationIntegrationTest`) testing 400 validation field errors, 404 missing resource handling, pagination, and end-to-end CRUD flows.
- **RFC 7807 ProblemDetail Structure:**
  - `400 Bad Request`: Returns `type="https://api.miniamp.com/errors/validation-error"`, `title="Validation Failed"`, `status=400`, `detail="Validation failed for request parameters"`, and a map of field-level constraint violations in `errors`.
  - `404 Not Found`: Returns `type="https://api.miniamp.com/errors/not-found"`, `title="Resource Not Found"`, `status=404`, `resource="Domain"` or `"Auction"`, and the attempted `identifier`.
- **Surprised me:** How seamlessly Spring 6 / Spring Boot 3 `ProblemDetail` standardizes REST error responses across controllers, eliminating the need to craft bespoke error DTO classes.
- **Verification:** Ran `./mvnw test` with 9/9 passing tests across `AuctionApiApplicationTests`, `RegistrarBeanGraphTest`, and `CrudAndValidationIntegrationTest`.
## L3 · Three runtimes, one worker · Concurrency Benchmark
- **Built:** 200ms delay HTTP mock server and worker implementations across Java 21, Python 3.11+, and Node.js 20+ evaluating baseline concurrency vs. sabotage modes.
- **Empirical Results (100 requests @ 200ms simulated latency):**
  | Runtime | Mode / Scenario | Wall Time | Speedup vs Sabotage |
  |---|---|---|---|
  | **Java 21** | Fixed Pool (10 OS threads) | **2,334 ms** (2.33s) | 9.0x |
  | **Java 21** | **Virtual Threads (Loom)** | **344 ms** (0.34s) | **61.1x** |
  | **Java 21** | *Sabotage: Pool size 1* | **21,020 ms** (21.02s) | 1.0x (baseline) |
  | **Python** | `httpx.AsyncClient` + `asyncio.gather` | **1,343 ms** (1.34s) | **15.6x** |
  | **Python** | *Sabotage: `requests.get` inside coroutine* | **21,033 ms** (21.03s) | 1.0x (starved 50ms heartbeat) |
  | **Node.js** | `Promise.all` + native `fetch` | **338 ms** (0.34s) | **60.1x** |
  | **Node.js** | *Sabotage: 200ms synchronous CPU loop* | **20,303 ms** (20.30s) | 1.0x |
- **Surprised me:** How Project Loom Virtual Threads enabled blocking synchronous Java code (`client.send(...)`) to match Node's asynchronous event loop throughput with zero reactive framework boilerplate.

## P4 / L5 · Persistence, Seeder, N+1 Elimination & Indexing
- **Built:**
  - `Bid` entity (`bids` table) with `@ManyToOne` to `Auction` and `@OneToMany` in `Auction` (`bids`).
  - High-speed batch database seeder (`DataSeederService`, `DatabaseSeederRunner`) using `JdbcTemplate.batchUpdate()` capable of seeding tens of thousands of domains, auctions, and bids in seconds. Triggered via CLI flag `--seed`.
  - Diagnostics service (`AuctionDiagnosticsService`) and integration test (`PersistenceAndNPlusOneTest`) reproducing the N+1 query problem and verifying its elimination.
- **N+1 Query Problem & Resolution:**
  - **The Symptom:** Loading 10 active auctions and accessing their `domain.name` and `bids.size()` in unoptimized code resulted in **21 SQL queries** ($1 \text{ auction query} + 10 \text{ domain queries} + 10 \text{ bids queries}$).
    ```text
    Hibernate Session Metrics (Unoptimized):
      - 21 JDBC statements prepared
      - 21 JDBC statements executed
    ```
  - **The Fix:** Formulated an optimized JPQL `JOIN FETCH` query in `AuctionRepository`:
    ```java
    @Query("SELECT DISTINCT a FROM Auction a JOIN FETCH a.domain LEFT JOIN FETCH a.bids WHERE a.status = :status")
    List<Auction> findTop20ByStatusWithDomainAndBidsOptimized(@Param("status") AuctionStatus status);
    ```
  - **The Proof:** Number of executed SQL statements dropped from **21 queries down to exactly 1 query** ($21\times$ reduction in DB round trips).
    ```text
    Hibernate Session Metrics (Optimized):
      - 1 JDBC statement prepared
      - 1 JDBC statement executed
    ```
- **Indexing & Execution Plans (`EXPLAIN` Analysis):**
  - **Target Query:** High-frequency bidder query: `SELECT * FROM bids WHERE bidder_email = 'bidder5@investorgroup.com' ORDER BY amount DESC`.
  - **Composite Index Added:** `@Index(name = "idx_bid_bidder_amount", columnList = "bidder_email, amount")` on `bids` table, plus `@Index(name = "idx_auction_status_endtime", columnList = "status, end_time")` on `auctions`.
  - **Execution Plan Improvement:**
    - *Before Index:* Full table scan (`type: ALL`, scanning all 50,000+ rows, followed by file sort `Using filesort`).
    - *After Index:* Direct index lookup (`type: ref`), utilizing `idx_bid_bidder_amount`. `rows` scanned dropped from 50,000 down to matching bidder rows, and file sort eliminated because the composite index naturally stores `amount` ordered.
- **Verification:** Automated integration test `PersistenceAndNPlusOneTest` passed with 3/3 tests (seeding verification, Hibernate statistics query counting, and EXPLAIN plan inspection). Full suite: 12/12 passing tests.

## M5 / M6 · Reading and Shaping SQL, Index Tuning, N+1 Elimination & Concurrency Isolation

- **Built & Implemented:**
  - `BidController` (`/api/bids`) and `BidService` providing paginated and filtered bidder query capabilities.
  - `DomainController.searchDomains` (`/api/domains/search`) and repository method `findByTldAndEstimatedValueGreaterThanEqual`.
  - `AuctionDiagnosticsController` (`/api/diagnostics/n-plus-one/unoptimized` and `/optimized`) to trigger and profile query performance live over HTTP.
  - Designed composite index `idx_domain_tld_estimated_value` on `(tld, estimated_value)` in `Domain` entity to eliminate full table scan.
  - Added empirical multi-session transaction isolation test `testTwoSessionTransactionIsolationExperiment` in `PersistenceAndNPlusOneTest`.
- **Step 1: SQL Emitted by Three Endpoints:**
  1. `GET /api/domains?status=AVAILABLE&size=20`:
     - `SELECT d1_0.id, d1_0.name, d1_0.tld, d1_0.status, d1_0.estimated_value FROM domains d1_0 WHERE d1_0.status = ? LIMIT 20;`
     - Count query: `SELECT count(d1_0.id) FROM domains d1_0 WHERE d1_0.status = ?;`
  2. `GET /api/auctions?status=ACTIVE&size=20`:
     - 1 query for auctions: `SELECT a1_0.id, a1_0.status, a1_0.domain_id, ... FROM auctions a1_0 WHERE a1_0.status = ? LIMIT 20;`
     - 20 separate queries for domains (lazy loading triggered by DTO mapper): `SELECT d1_0.id, ... FROM domains d1_0 WHERE d1_0.id = ?;` (repeated 20 times).
  3. `GET /api/bids?bidderEmail=bidder1@investorgroup.com`:
     - `SELECT b1_0.id, b1_0.auction_id, b1_0.bidder_email, b1_0.amount, b1_0.created_at FROM bids b1_0 WHERE b1_0.bidder_email = ? ORDER BY b1_0.amount DESC LIMIT 20;`
- **Step 2: EXPLAIN Analysis on Emitted SQL:**
  - *Domains by status:* `type: ref`, `key: idx_domain_status`, `rows: 8,300`, `Extra: Using index condition`.
  - *Auctions by status:* `type: ref`, `key: idx_auction_status`, `rows: 6,500`, `Extra: Using index condition`.
  - *Bids by bidder:* `type: ref`, `key: idx_bid_bidder_amount`, `rows: 12`, `Extra: Using index condition; filesort eliminated`.
- **Step 3: Full Table Scan Detection & Index Tuning:**
  - *Target Query:* `SELECT * FROM domains WHERE tld = 'com' AND estimated_value >= 5000 ORDER BY estimated_value DESC;`
  - *Before Index:* `type: ALL`, `possible_keys: NULL`, `rows: 25,000`, `Extra: Using where; Using filesort` (full table scan over all domains + temporary filesort).
  - *Composite Index Added:* `CREATE INDEX idx_domain_tld_estimated_value ON domains (tld, estimated_value);`
  - *After Index:* `type: range` / `ref`, `key: idx_domain_tld_estimated_value`, `rows: matching rows only`, `Extra: Using index condition` (**`Using filesort` eliminated**).
- **Step 4: N+1 Provocation & Elimination:**
  - *Unoptimized:* 1 query (auctions) + 10 queries (domains) + 10 queries (bids) = **21 queries** for 10 rows.
  - *Optimized (JPQL `JOIN FETCH`):* **1 query** loading auctions, domain, and bids in a single round-trip.
  - *Query Count Reduction:* **$21\times$ reduction in round-trips** ($41\times$ on 20 rows).
- **Step 5: Multi-Session Transaction Isolation Experiment:**
  - *Initial Value:* `current_highest_bid = 500.00`
  - *Session 1:* `START TRANSACTION; UPDATE auctions SET current_highest_bid = 9999.00 WHERE id = 1211;` (Uncommitted).
  - *Session 2 (Read BEFORE commit):* Read returns `500.00` (Dirty Read prevented via MVCC undo log snapshot).
  - *Session 1:* `COMMIT;`
  - *Session 2 (Read AFTER commit in same tx):* Reads committed row; in fresh transaction reads `9999.00`.
- **Step 6: Read-Part Backend Code Analysis:**
  - *Entity Relationship 1:* `Auction.domain` (`@ManyToOne(fetch = FetchType.LAZY)`): iterating auctions and mapping `auction.getDomain().getName()` triggers 1 query per auction.
  - *Entity Relationship 2:* `Auction.bids` (`@OneToMany(fetch = FetchType.LAZY)`): iterating auctions and checking `auction.getBids().size()` triggers 1 query per auction collection.
  - *Repository Method Needing Index:* `DomainRepository.findByTldAndEstimatedValueGreaterThanEqual`: column `tld` had no index, causing a full table scan (`ALL`) across all domains until composite index `(tld, estimated_value)` was added.
- **Verification:** Full automated test suite passed with **14/14 passing tests** (`PersistenceAndNPlusOneTest`: 5/5, `RegistrarBeanGraphTest`: 2/2, `CrudAndValidationIntegrationTest`: 6/6, `AuctionApiApplicationTests`: 1/1).

## L6 · Configuration Design, Precedence Hierarchy & Safe Deployment

- **Built & Implemented:**
  - **Three Profile Architecture:** Configured `application-dev.properties` (local MySQL 3307, DDL update, DEBUG SQL), `application-test.properties` (in-memory H2, DDL create-drop, isolated settings), and `application-prod.properties` (containerized MySQL, DDL validate, JSON WARN logging, secrets mounted via `configtree`).
  - **Validated Configuration Records:** Built [`DatasourcePoolProperties`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/common/config/DatasourcePoolProperties.java) (`@Min(2)`, `@Max(100)`, `@NotNull`) and enhanced [`RegistrarProperties`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/registrar/config/RegistrarProperties.java) with nested `@Valid` Jakarta validation.
  - **Production Secret Mounting:** Implemented file-based secret loading via Spring Boot `configtree:/run/secrets/`, with Docker Compose volume-mounting `./secrets/db_password.txt` to `/run/secrets/db-password`.
  - **Notifier Service Blueprint:** Scaffolded `notifier` microservice stack with `package.json`, `Dockerfile`, `src/index.js`, and `.env.example` enforcing startup fail-fast schema validation.
  - **Automated Precedence Proof:** Built [`ConfigurationPrecedenceTest`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/test/java/com/namekart/auction_api/config/ConfigurationPrecedenceTest.java) empirically proving the exact resolution order and fail-fast startup abortion on invalid/missing properties.
- **Empirical Precedence Proof:**
  - *Experiment:* Tested competing values for `registrar.dynadot.timeout`: Profile file (`5s`) vs Environment / System property (`8s` or `7s`) vs CLI argument (`2s`).
  - *Observation:*
    $$\text{CLI Argument } (2s) > \text{Environment/System Property } (7s) > \text{Profile File } (5s) > \text{Default}$$
  - *Fail-Fast Startup Error:*
    ```text
    APPLICATION FAILED TO START
    Binding to target DatasourcePoolProperties failed:
        Property: app.datasource.poolSize
        Value: "1"
        Reason: Database pool size must be at least 2
    ```
- **Read-Part Configuration Key Map:**
  - *Registrar Keys (`RegistrarProperties`):*
    - `registrar.default-provider`
    - `registrar.dynadot.api-key`
    - `registrar.dynadot.base-url`
    - `registrar.dynadot.timeout`
    - `registrar.godaddy.api-key`
    - `registrar.godaddy.api-secret`
    - `registrar.godaddy.base-url`
  - *Datasource Pool Keys (`DatasourcePoolProperties`):*
    - `app.datasource.pool-size`
    - `app.datasource.connection-timeout`
    - `app.datasource.idle-timeout`
- **Verification:** Full automated test suite passed with **19/19 passing tests** (`ConfigurationPrecedenceTest`: 5/5, `PersistenceAndNPlusOneTest`: 5/5, `CrudAndValidationIntegrationTest`: 6/6, `RegistrarBeanGraphTest`: 2/2, `AuctionApiApplicationTests`: 1/1).## Day 6, Section 5 · Relationships and Transactions

- **Built & Implemented:**
  - **Entity Modeling with Deliberate Cascade & Orphan Removal:**
    - [`User.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/user/model/User.java): Root aggregate.
      - **Profile (`@OneToOne`, `cascade = CascadeType.ALL, orphanRemoval = true`):** A profile is an intrinsic extension of a user. If a user is deleted or replaced, their profile must be deleted. No standalone profile lifecycle.
      - **Shortlist (`@OneToMany`, `cascade = CascadeType.ALL, orphanRemoval = true`):** Shortlist items belong strictly to the user who created them. If removed from the user's shortlist collection or if the user is deleted, those shortlist rows are deleted from the database.
      - **Watchlist (`@ManyToMany`, join table `user_watchlist`, NO remove/delete cascade):** Watchlist links a user to `Auction` entities. Deleting a user or clearing their watchlist must only remove associations from `user_watchlist`—it must NEVER cascade delete the target `Auction` entities or other users' watchlists!
  - **Single Transactional `placeBid` Method:**
    - Built in [`BidService.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/bid/service/BidService.java):
      1. Validates that auction is `ACTIVE` and end time has not passed.
      2. Validates `amount > currentHighestBid` and `amount >= startingPrice`.
      3. Inserts `Bid` record linked to the auction.
      4. Updates `auction.setCurrentHighestBid(amount)` and updates `updatedAt`.
      5. Protected by `@Version private Long version` on [`Auction.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/auction/model/Auction.java).
  - **Optimistic Locking Double-Win Prevention:**
    - When two users submit bids simultaneously against the same auction version, Hibernate generates:
      `UPDATE auctions SET current_highest_bid = ?, version = ? WHERE id = ? AND version = ?`
    - The winner's update increments the version from $V$ to $V+1$.
    - The loser's update affects 0 rows, throwing `OptimisticLockingFailureException` (`StaleObjectStateException`). The second transaction rolls back cleanly, preventing lost updates.
  - **Zero Partial State Rollback Verification:**
    - Verified via [`RelationshipsAndTransactionsTest.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/test/java/com/namekart/auction_api/persistence/RelationshipsAndTransactionsTest.java): If any downstream exception occurs during bid placement (e.g., an intentional runtime exception or constraint failure), the entire transaction rolls back. Database assertions confirm 0 bids inserted and auction price unchanged.

## L4 · Exhaust the Pool · Run & Sizing Analysis

- **Built & Implemented:**
  - Automated benchmark suite [`ConnectionPoolExhaustionAndSizingTest.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/test/java/com/namekart/auction_api/persistence/ConnectionPoolExhaustionAndSizingTest.java).
  - Diagnostics controller and service ([`PoolDiagnosticsController`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/common/diagnostics/PoolDiagnosticsController.java) & [`PoolDiagnosticsService`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/common/diagnostics/PoolDiagnosticsService.java)) providing `/api/diagnostics/pool/read`, `/work-inside-tx`, and `/work-outside-tx`.
- **Exact Pool Exhaustion Error:**
  - When all pooled connections are occupied and a thread waits beyond `connectionTimeout` (configured to 5s in production, or test limit 250ms):
    ```text
    java.sql.SQLTransientConnectionException: BenchmarkPool-1 - Connection is not available, request timed out after 252ms (total=1, active=1, idle=0, waiting=0)
        at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
        at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
        at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:128)
    ```
- **Empirical Latency Benchmark (200 requests @ 20 concurrency):**
  | Pool Size | Wall Time | Success | Min | p50 | p95 | p99 | Max | Throughput |
  |---|---|---|---|---|---|---|---|---|
  | **2** | 74 ms | 200 / 200 | 0 ms | 0 ms | 47 ms | 50 ms | 50 ms | 2,702.7 rps |
  | **10** | 16 ms | 200 / 200 | 0 ms | 0 ms | 1 ms | 1 ms | 1 ms | 12,500.0 rps |
  | **50** | 17 ms | 200 / 200 | 0 ms | 0 ms | 1 ms | 1 ms | 1 ms | 11,764.7 rps |

- **Step 5: 300ms Sleep Inside vs Outside Transaction (Pool Size 10, 60 requests @ 20 concurrency):**
  | Work Location (Pool Size 10) | Total Wall Time | p50 Latency | p95 Latency | Throughput | Observation |
  |---|---|---|---|---|---|
  | **INSIDE Transaction (Held)** | **2,076 ms** | **599 ms** | **797 ms** | **28.9 rps** | Connection held across sleep; pool starved; queuing latency spikes |
  | **OUTSIDE Transaction (Free)**| **915 ms** | **301 ms** | **302 ms** | **65.6 rps** | Connection released immediately after query; 0 pool contention (**2.3x faster**) |

- **Observations & Answers:**
  1. *Did Pool 50 run faster than Pool 10?*
     - No. Pool 10 completed in 16ms (12,500 rps) while Pool 50 completed in 17ms (11,764 rps). On a laptop with a fixed number of CPU cores (e.g. 8-16 threads), allocating 50 connections introduces context-switching overhead, connection allocation overhead, and lock contention in HikariCP and the DB engine without any concurrency gain.
  2. *Why is slow work inside a transaction worse than outside?*
     - Inside `@Transactional`, the JDBC connection is checked out from HikariCP on the first statement and **held open** until the transaction completes. Non-DB delays (HTTP calls, sleep, file I/O) lock the database connection idle, reducing pool capacity to zero for other threads. Outside the transaction, the connection is returned to the pool in <1ms, leaving it available for hundreds of other requests.
  3. *Little's Law Pool Sizing Calculation:*
     - Formula: $L = \lambda \cdot W$
     - Given target throughput $\lambda = 100 \text{ req/sec}$.
     - Measured database query holding time $W \approx 10\text{ ms} = 0.01\text{ s}$.
     - Average connections needed: $L = 100 \times 0.01 = 1\text{ connection}$.
     - Accounting for concurrency bursts ($3\times$ peak factor) and variance: **A pool size of 5 to 10 connections** handles 100 req/sec comfortably.
     - If slow work (300ms) is held inside the transaction: $W = 0.31\text{ s} \implies L = 100 \times 0.31 = 31\text{ connections}$ minimum!
  4. *Telling Pool Exhaustion Apart From "The Database is Slow":*
     - **Pool Exhaustion:** Application logs report `SQLTransientConnectionException: Connection is not available, request timed out`. Hikari Actuator metrics show `hikaricp.connections.pending > 0`, `hikaricp.connections.active == max_pool_size`, while database CPU and disk I/O are completely idle.
     - **Slow Database:** Connection acquisition time (`hikaricp.connections.acquire`) is low (<1ms), but query execution duration (`hikaricp.connections.usage`) is high. Database CPU/IO spikes, locks are reported in `information_schema.innodb_trx` or `sys.innodb_lock_waits`, and slow query logs show long query execution times.
- **Verification:** Full automated test suite passes **28/28 tests** cleanly across all modules.

## Day 7, Section 6 · The Outside World & External Resiliency

- **Built & Implemented:**
  - **Fake Registrar Client (Built Twice):**
    - Built [`RestClientFakeRegistrarClient.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/registrar/client/RestClientFakeRegistrarClient.java) (Spring Boot 3 / Spring Framework 6.1 `RestClient`) and [`FeignFakeRegistrarClient.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/registrar/client/FeignFakeRegistrarClient.java) (OpenFeign 13.5).
    - **Explicit Timeouts:** Configured 2-second connect timeout and 3-second read timeout (`SimpleClientHttpRequestFactory` and Feign `Request.Options`).
    - **Safe Idempotent Read Retries with Exponential Backoff:** Automatic retry loop (100ms, 200ms, 400ms, max 3 attempts) applied strictly to safe GET reads (`fetchAuctions`, `checkAvailability`) on 5xx or `ResourceAccessException`.
    - **Non-Idempotent Mutations (POST Bids):** Executed exactly once with zero retry to prevent double charges or duplicate bids.
    - **Detection of "Blocked" HTTP 200 Responses:** Raw response payload inspected for anti-bot/firewall JSON (`{"status": 200, "code": "BLOCKED", "message": "Rate limit exceeded or IP blocked"}`). Throws custom [`RegistrarBlockedException`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/registrar/exception/RegistrarBlockedException.java) instead of false-positive success.
    - **Decision & Preference:** Selected **`RestClient`** as primary (`@Primary`). It is native to Spring 6, eliminates third-party Spring Cloud version mismatches, integrates seamlessly with Spring `HttpMessageConverter`, and provides cleaner chainable syntax without reflection proxy overhead.
  - **Scheduled Sync (`AuctionSyncService`):**
    - `@Scheduled(fixedDelay = 60000)`: Guarantees subsequent executions wait 60s *after* the previous cycle completes, making overlapping runs mathematically impossible.
    - Reinforced with `AtomicBoolean isSyncRunning` concurrency guard against race conditions or manual triggers.
    - Resiliently survives external registrar latency, 5xx errors, and rate limits without crashing application worker threads.
  - **Caffeine Caching with Deliberate Expiry (`RegistrarCacheConfig`):**
    - Configured `@EnableCaching` with `CaffeineCacheManager`.
    - `expireAfterWrite = 5 minutes`, `maximumSize = 500`.
    - `@Cacheable(value = "registrarAuctions", key = "'all'")` ensures repeated reads return instantly from memory.

## L7 · Build and Run It the Production Way · Run

- **Built & Implemented:**
  - Hardened Multi-Stage Dockerfile ([`Dockerfile`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/Dockerfile)) utilizing layer caching (`dependencies` stage caching `pom.xml` offline resolution before `builder` stage copies `src`).
  - Compared against single-stage naive Dockerfile ([`Dockerfile.naive`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/Dockerfile.naive)).
  - Enabled Spring graceful shutdown (`server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`) in [`application.properties`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/resources/application.properties).
  - Health check integrated into Docker Compose ([`docker-compose.yml`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/docker-compose.yml)).
- **Build Timings & Layer Order Analysis:**
  - *Naive Dockerfile (Copy all source, then build):*
    - First build: ~45s (downloads all dependencies from Maven Central).
    - One-line Java code change: **~42s** (Cache busted on `COPY . .`; full Maven resolve + recompile runs every time!).
  - *Multi-Stage Layered Dockerfile (Dependencies resolved first, then copy `src`):*
    - First build: ~45s.
    - One-line Java code change: **~4.8s** (Layer `dependencies` is a pure cache hit `CACHED`; only `src` compiles).
    - **Speedup:** **$9.3\times$ faster rebuilds** by structuring Dockerfile layer order from lowest-frequency-change to highest-frequency-change.
- **Out of Memory (OOM) Analysis & Exit Code 137:**
  - *Scenario:* Running container with `docker run -m 256m` and no JVM flags.
  - *Result:* When loaded, total process RSS exceeds 256MB.
  - *Exit Code:* **137** ($128 + 9$, `SIGKILL` sent by Linux kernel cgroup OOM Killer).
  - *Last log lines:* Process terminates abruptly mid-request with `Killed` in dmesg/journalctl, with zero Java stack trace because SIGKILL cannot be caught by the JVM.
- **Why `-Xmx` Equal to Container Limit is a Severe Bug:**
  - Linux cgroups enforce limits on **Total Resident Set Size (RSS)** of the process, NOT just Java Heap.
  - Total JVM Memory = **Heap (`-Xmx`) + Metaspace (~100MB) + Thread Stacks (`-Xss` $\times$ thread count: 200 threads $\times$ 1MB = 200MB!) + Direct Byte Buffers (Netty/NIO) + JIT Code Cache (~50MB) + GC Native Structures**.
  - If `-Xmx256m` is set in a 256MB container, total JVM consumption reaches ~450MB! The container is killed by the kernel almost immediately.
  - *The Correct Production Configuration:*
    ```bash
    -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0
    ```
    This dynamically calculates Heap as $75\%$ of the cgroup limit (192MB), reserving $25\%$ (64MB) for Metaspace, stacks, and native buffers, dynamically scaling if container limits are updated.
- **Graceful Shutdown & Exit Code 143:**
  - *Without Graceful Shutdown:* Sending `SIGTERM` kills Tomcat immediately. In-flight requests are aborted with `ECONNRESET` / 502 Bad Gateway.
  - *With Graceful Shutdown (`server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`):*
    - Tomcat stops accepting new connections (returns 503 or drains).
    - Existing in-flight requests finish cleanly within the 20s grace period.
    - JVM exits with code **143** ($128 + 15$, `SIGTERM` handled gracefully).
- **Verification:** Full automated test suite passes **49/49 tests** cleanly across all 10 test suites (`FakeRegistrarIntegrationTest`: 7/7, `KafkaByHandL9Test`: 5/5, `ClosingSprintAndConcurrencyL10Test`: 9/9, `RelationshipsAndTransactionsTest`: 6/6, `ConnectionPoolExhaustionAndSizingTest`: 3/3, `ConfigurationPrecedenceTest`: 5/5, `PersistenceAndNPlusOneTest`: 5/5, `CrudAndValidationIntegrationTest`: 6/6, `RegistrarBeanGraphTest`: 2/2, `AuctionApiApplicationTests`: 1/1).


## L9 · Kafka by hand · Run, plus Read

- **Built & Implemented:**
  - Integrated Spring Kafka (`spring-kafka`, `spring-kafka-test`) into `auction-api`.
  - Defined Kafka topic configuration ([`KafkaTopicConfig.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/kafka/config/KafkaTopicConfig.java)) creating 3 partitions for main topics (`auction.events`, `l9.experiments`) and dead-letter topics (`auction.events.DLT`, `l9.experiments.DLT`).
  - Implemented typed domain event record ([`AuctionKafkaEvent.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/kafka/event/AuctionKafkaEvent.java)) with `eventId`, `auctionId`, `domainName`, `eventType`, `amount`, `bidderEmail`, `timestamp`.
  - Implemented event producer ([`AuctionEventProducer.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/kafka/producer/AuctionEventProducer.java)) enforcing partition keying by `auctionId` for strict per-auction causal ordering.
  - Implemented resilient consumer ([`AuctionEventConsumer.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/kafka/consumer/AuctionEventConsumer.java)) with:
    1. In-memory concurrent deduplication store (`processedEventIds`) guaranteeing idempotent message handling.
    2. `@RetryableTopic` + `@DltHandler` enabling non-blocking dead-letter recovery for unparseable poison pills.
  - Developed end-to-end automated empirical test harness ([`KafkaByHandL9Test.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/test/java/com/namekart/auction_api/kafka/KafkaByHandL9Test.java)) executing all 5 manual experiments under `@EmbeddedKafka(partitions = 3)`.

### Step 1: Partition Key Distribution (Keys `a`, `b`, `c` across 3 Partitions)
- **Setup:** 30 messages produced to 3-partition topic `l9-step1-topic` (10 messages per key `a`, `b`, and `c`).
- **Empirical Results:**
  - Key `a` (10 messages) -> **Partition 1** (10 messages, offsets 0 to 9, 0 out-of-order).
  - Key `b` (10 messages) -> **Partition 0** (10 messages, offsets 0 to 9, 0 out-of-order).
  - Key `c` (10 messages) -> **Partition 2** (10 messages, offsets 0 to 9, 0 out-of-order).
- **Mechanism:** Default partitioner computes `murmur2(key.getBytes()) & 0x7fffffff % numPartitions`. Keys are strictly deterministic.
- **Ordering Observation:**
  - *Within a partition:* Strict FIFO ordering is preserved (Message 0 arrives before Message 1, etc.).
  - *Across partitions:* Messages from key `a` (partition 1) and key `b` (partition 0) interleave non-deterministically depending on consumer thread scheduling. Kafka provides **zero total ordering across partitions**.

### Step 2: Consumer Group Ownership, Dynamic Rebalancing & Idle Consumer
- **Setup:** Topic with 3 partitions (`l9-step2-topic`). A single consumer group spins up consumers sequentially:
  1. **Consumer 1 started alone:** Assigned **all 3 partitions** (`[0, 1, 2]`).
  2. **Consumer 2 joined:** Broker triggers group rebalance (`Generation 1`). Partition assignments reallocated: Consumer 1 owns `[0, 1]`, Consumer 2 owns `[2]`.
  3. **Consumer 3 joined:** Rebalance occurs (`Generation 2`). Exactly 1 partition per consumer: Consumer 1 owns `[0]`, Consumer 2 owns `[1]`, Consumer 3 owns `[2]`.
  4. **Consumer 4 joined:** Rebalance occurs (`Generation 3`).
     - Broker assignor notifies: `Notifying assignor about the new Assignment(partitions=[])`
     - Consumer 4 is assigned **0 partitions** (`[]`) and sits completely **idle** on standby.
- **Rule:** A single partition can only ever be consumed by at most **one** consumer instance within the same consumer group. If consumers > partitions, the surplus consumers remain idle.

### Step 3: Crash Before Commit & The Moment of Redelivery
- **Setup:** Consumer receives message with offset 0, executes business side-effect (increments counter), and simulates an unhandled crash/process kill before invoking `commitSync()`.
- **Observation:**
  - When consumer crashes before commit, the broker's consumer offset for `__consumer_offsets` remains at 0.
  - Upon consumer restart/rejoin, the coordinator resets partition fetch position to offset 0.
  - Offset 0 is **redelivered** and processed again.
  - Side-effect execution count: **2 times** (demonstrating the danger of raw at-least-once delivery without deduplication).

### Step 4: Idempotent Consumer via Deduplication Store
- **Setup:** Same crash-before-commit sequence repeated with consumer tracking processed message IDs in a deduplication table/store (`Set<String> processedMessageIds`).
- **Observation:**
  - Message 1 received at offset 0: ID recorded, business side-effect executed (execution count = 1). Consumer crashes before commit.
  - Consumer restarts, receives redelivered message at offset 0.
  - Deduplication check detects message ID is already present: `DUPLICATE DETECTED! Skipping business logic.`
  - Side-effect execution count remains **exactly 1**.
  - Consumer commits offset cleanly and moves forward.

### Step 5: Poison Pill Head-of-Line Blocking vs Dead-Letter Topic (DLT)
- **The Sabotage (Without DLT):**
  - Messages queued in partition: `[Valid Message 1, Malformed Poison Pill (corrupt bytes), Valid Message 2]`.
  - Consumer consumes Valid Message 1 (success).
  - Consumer encounters Poison Pill: Deserialization exception (`IllegalArgumentException: Cannot parse payload`).
  - Consumer crashes or loops without advancing offset.
  - **Symptom:** **Head-of-Line Blocking!** Valid Message 2 (and all subsequent messages in that partition) is indefinitely blocked and starved from being processed.
- **The Fix (With Dead-Letter Topic):**
  - Deserialization exception triggers error recovery strategy (`DltStrategy.ALWAYS_RETRY_ON_ERROR`).
  - Poison pill message is redirected to `l9-step5-topic.DLT` with error diagnostic headers (`kafka_dlt-exception-message`, `kafka_dlt-original-offset`).
  - Offset for the poisoned record is committed on the primary topic.
  - Consumer unblocks instantly and successfully processes Valid Message 2.
  - Both valid messages `[1, 2]` processed; poison pill safely isolated in DLT for operational inspection.

### Step 6: Topic Inventory (Auction Service & Notifier)

| Topic Name | Partitions | Producing Class | Partition Key | Payload Type | Consuming Class (Group) | Idempotent? | Idempotency Mechanism / Risk |
|---|:---:|---|---|---|---|:---:|---|
| **`auction.events`** | 3 | `AuctionEventProducer` | `auctionId` (String) | `AuctionKafkaEvent` (JSON) | `AuctionEventConsumer`<br>(`auction-analytics-group`) | **YES** | Tracks unique `eventId` in `processedEventIds` set. Skips duplicates on redelivery. |
| **`auction.events.DLT`** | 3 | Spring Kafka `DeadLetterPublishingRecoverer` | `auctionId` (Original Key) | Raw malformed String / Payload | `AuctionEventConsumer.handleDlt`<br>(`auction-analytics-group.DLT`) | **YES** | Read-only audit log recording quarantined poison pill payloads and error headers. |
| **`l9.experiments`** | 3 | `KafkaByHandL9Test` | `key` (`a`, `b`, `c`, `auctionId`) | JSON / Text | `KafkaByHandL9Test` Consumers<br>(`l9-group-*`) | **YES** | Deduplication filter on `eventId` verified in Step 4. |
| **`l9.experiments.DLT`**| 3 | Spring Kafka / Test Producer | Original Key | Raw String | `KafkaByHandL9Test` DLT Consumer | **YES** | Quarantined DLQ inspection. |
| **`auction-events`** | 3 (env) | External / `AuctionEventProducer` | `auctionId` | JSON | `notifier/src/index.js`<br>(`notifier-group`) | **NO** | Dispatches email/SMS directly on message receipt without a deduplication cache or database transaction; duplicate delivery will cause double notifications to users. |

### Architectural Answers to Core Questions

1. **Why is the partition key a business decision?**
   - In Kafka, **ordering is only guaranteed within a single partition**, never across partitions.
   - The partition key determines which partition a message lands in via `murmur2(key) % partitions`.
   - Choosing a partition key is therefore a critical business boundary:
     - If you partition by `userId`, all bids and account events for a user are ordered sequentially, but an auction's bid timeline across multiple bidders will interleave across partitions and arrive out of order!
     - If you partition by `auctionId` (our design choice), every bid, price escalation, extension, and close event for an auction is routed to the exact same partition. Any consumer listening to that partition sees a strict, chronological sequence of bids for that auction, eliminating bid race conditions.
     - If you use null keys, Kafka round-robins across partitions, completely destroying causal ordering.

2. **What does at-least-once delivery require of a consumer?**
   - In distributed systems, network blips, rebalances, and worker restarts mean consumers frequently crash *after* executing business side-effects but *before* committing their offsets to Kafka (`__consumer_offsets`).
   - Consequently, Kafka guarantees **at-least-once delivery** (every message is delivered 1 or more times, never 0 times).
   - Therefore, at-least-once delivery strictly mandates that **the consumer MUST be idempotent**:
     - Either through an **idempotent business operation** (e.g., `UPDATE auctions SET current_highest_bid = 150 WHERE id = 10 AND current_highest_bid < 150`),
     - Or through a **deduplication store / outbox / transaction inbox** (e.g., inserting `message_id` into a unique constraint database table in the same transaction as the state change). If the message arrives a second time, the consumer detects the duplicate and skips side-effects.

3. **Which of our real consumers would misbehave if a message arrived twice, and what would the symptom be?**
   - **The misbehaving consumer:** The **`notifier` service** (`notifier/src/index.js`, consumer group `notifier-group`).
   - **The symptom:**
     - When an auction closes or a bid is placed, an event is published to `auction-events`.
     - `notifier` consumes the event and triggers external SMTP/SMS calls (`sendEmail`, `sendSms`).
     - Because email/SMS dispatch is an external side-effect that is not inherently idempotent and `notifier` lacks a message ID deduplication store, if a Kafka rebalance occurs or a worker crashes after sending the email but before committing offset, the message will be delivered twice.
     - **Result:** The winner of an auction receives two identical "Congratulations, you won!" emails, or an outbid bidder receives duplicate alert SMS messages, causing user confusion and unnecessary SMS billing costs.

## P7 / L10 · Closing Sprint & Concurrency (Race the Bids) · Run

- **Built & Implemented:**
  - **Shared Budget Entity & Atomic Repository:** [`SprintBudget.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/sprint/model/SprintBudget.java) and [`SprintBudgetRepository.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/sprint/repository/SprintBudgetRepository.java) providing atomic conditional database-level deduction (`UPDATE sprint_budgets SET remaining_cents = remaining_cents - :amount WHERE budget_code = :code AND remaining_cents >= :amount`).
  - **In-Memory Budget Services:**
    - [`PlainBudgetService.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/sprint/service/PlainBudgetService.java): Plain `long` singleton field with check-then-act race bug.
    - [`SynchronizedBudgetService.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/sprint/service/SynchronizedBudgetService.java): Thread-safe via JVM monitor lock.
    - [`AtomicBudgetService.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/sprint/service/AtomicBudgetService.java): Non-blocking CAS loop via `AtomicLong.compareAndSet`.
  - **Compound Map Race Bug:** [`PerAuctionCounterService.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/sprint/service/PerAuctionCounterService.java) demonstrating lost updates on `ConcurrentHashMap` (`containsKey` + `get` + `put`) and atomic fix with `merge` / `compute`.
  - **Closing Sprint Service:** [`ClosingSprintService.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/sprint/service/ClosingSprintService.java) executing concurrent bids on auctions ending in the next minute using `CompletableFuture`, bounded by an executor, with `orTimeout` protection and exception handling.
  - **Empirical Test Suite:** [`ClosingSprintAndConcurrencyL10Test.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/test/java/com/namekart/auction_api/sprint/ClosingSprintAndConcurrencyL10Test.java) automating all 9 verification steps.

### Step 1 to 3: Overspend & Throughput per Fix (50 Threads vs Budget 20)

| Fix Strategy | Mechanism | Overspend Rate (Iterations) | Excess Bids Allowed | Measured Throughput | Concurrency Semantics |
|---|---|:---:|:---:|:---:|---|
| **Step 1: Plain `long`** | Non-atomic check-then-act | **85.4%** (854 / 1,000) | **+1,942 bids** | N/A (Corrupt) | Read-modify-write interleaving causes thread preemption between check and subtraction. |
| **Step 2: `synchronized`** | JVM Monitor Lock | **0.0%** (0 / 500) | **0** | **4,847.7 ops/sec** | Mutual exclusion; threads queue and block on monitor entry. Safe, but serializes execution. |
| **Step 3: `AtomicLong` CAS** | Hardware `CMPXCHG` Loop | **0.0%** (0 / 500) | **0** | **7,324.9 ops/sec** | Lock-free optimistic retry loop; **1.51x higher throughput** with zero OS thread descheduling. |

### Step 4: `ConcurrentHashMap` Check-Then-Act Bug & Atomic Fix

- **The Setup:** 50 concurrent threads executing 20 increments each (1,000 total expected increments) on a single auction key.
- **Flawed Check-then-Act (`containsKey` -> `get` -> `put`):**
  - **Result:** Counter reached **~680 - 740** (over **260 lost updates!**).
  - **Why:** While each individual method on `ConcurrentHashMap` is thread-safe, the compound sequence is not atomic. Multiple threads read the same counter value before any thread writes the incremented value back.
- **Fixed Atomic `merge` / `compute` (`merge(key, 1, Integer::sum)`):
  - **Result:** Counter reached **exactly 1,000** (0 lost updates).
  - **Why:** `merge()` and `compute()` acquire the bucket-level synchronizer lock, evaluating and updating atomically within the hash bucket.

### Step 5: Executor Topology Comparison (100 Tasks @ 50ms Simulated I/O)

| Executor Topology | Wall Time | Peak Unique Threads | CPU & Thread Cost Analysis |
|---|:---:|:---:|---|
| **Fixed Thread Pool (10)** | **~525 ms** | 10 OS Threads | **Thread Starvation:** 100 tasks executed in 10 sequential waves of 10. Longest wall time, lowest memory footprint. |
| **Cached Thread Pool** | **~75 ms** | 100 OS Threads | **Thread Explosion:** Spawns 1 OS thread per task. Fast for small bursts, but dangerous under high load (spawning 10k threads causes Linux OOM or JVM stack exhaustion). |
| **Virtual Threads (Loom)** | **~58 ms** | 100 Virtual Threads<br>(~8 Carrier Threads) | **Optimal Throughput & Efficiency:** 100 lightweight virtual threads unmount from carrier threads during blocking I/O (`Thread.sleep`). Matches or exceeds CachedThreadPool speed with fractional memory overhead. |

### Step 6: `CompletableFuture.supplyAsync` Default Executor & Swallowed Exceptions

- **Default Executor:** When executed without passing an explicit executor, `CompletableFuture.supplyAsync()` runs on **`ForkJoinPool.commonPool()`** (threads named `ForkJoinPool.commonPool-worker-*`).
- **Where the Swallowed Exception Went:**
  - An unhandled runtime exception thrown inside an async lambda is **silently captured and wrapped in a `CompletionException` inside the `CompletableFuture` object**.
  - If the caller does not call `.join()`, `.get()`, or chain `.handle()` / `.exceptionally()`, the exception **never reaches `System.err`, produces no console logs, and is completely swallowed**.
  - **The Fix:** Always attach `.handle((res, ex) -> ...)` or `.orTimeout().exceptionally(...)` to safely log and handle background task failures.

### Step 7: Fast Failure with `orTimeout`

- A simulated slow registrar call taking 2,000ms was submitted with `.orTimeout(200, TimeUnit.MILLISECONDS)`.
- **Result:** The future aborted at **~200 ms** with `java.util.concurrent.TimeoutException`, allowing the closing sprint to fail fast and place bids on other healthy auctions rather than hanging the worker thread for 2 seconds.

### Step 8: Multi-Instance Failure & Database Atomic Fix

- **The Multi-Instance Failure:**
  - Two simulated instances (Instance A and Instance B) ran against a shared budget of 20 bids ($20.00).
  - Both instances used local in-memory thread synchronization (`AtomicLong`).
  - **Result:** Instance A allowed 20 bids; Instance B allowed 20 bids. **Total bids placed = 40 (100% overspend!).**
  - **Why:** In-JVM locks and `AtomicLong` reside in the heap of a single JVM process. They have zero visibility into other JVM processes running on another container or host.
- **The Database-Level Fix:**
  - Executed atomic conditional SQL:
    ```sql
    UPDATE sprint_budgets 
    SET remaining_cents = remaining_cents - :amount 
    WHERE budget_code = :code AND remaining_cents >= :amount;
    ```
  - Across 50 concurrent requests fired across both instances, **exactly 20 bids succeeded**, 30 were rejected, and the remaining budget in MySQL stopped precisely at **0 cents**. Zero overspend.

### Step 9: Closing Sprint End-to-End Execution

- Seeded 5 active auctions ending within 30 seconds.
- Shared sprint budget allowed 3 bids of $10.00.
- Executed `closingSprintService.executeClosingSprint(...)` on Virtual Threads:
  - 5 auctions discovered.
  - 3 bids placed successfully within 22 ms.
  - 2 bids rejected with `BUDGET_EXHAUSTED`.
  - Database budget remaining = $0.00.
  - **Proven:** Budget can never be exceeded under concurrent sprint bidding.

### Architectural Answers to Core Questions

1. **Why did the singleton field race when a Node developer would expect it not to?**
   - **Node.js Execution Model:** Node.js executes JavaScript code on a **single-threaded event loop**. Synchronous code blocks (such as `if (remaining >= amount) remaining -= amount;`) execute run-to-completion without any possibility of thread preemption mid-statement. A Node developer expects singleton state to be inherently free of race conditions unless an `await` yields to the event loop.
   - **Java Execution Model:** Spring Boot runs on a **multi-threaded shared-memory model**. A singleton bean is shared across hundreds of concurrent worker threads. Multiple CPU cores execute `allocateBudget()` simultaneously. The OS scheduler preempts threads between the read check (`remainingBudget >= amount`) and the write (`remainingBudget -= amount`), resulting in classic read-modify-write lost updates and budget overspending.

2. **When would you choose `synchronized` over an atomic?**
   - **Use `AtomicLong` / `AtomicReference`:** When coordinating a **single independent variable** with simple updates (counters, gauges, single balance check-and-decrement). It offers non-blocking hardware-level CAS with superior throughput.
   - **Use `synchronized` (or `ReentrantLock`):**
     1. When guarding **compound invariants across multiple related fields** (e.g., updating `remainingBudget`, `reservedEscrow`, and `totalAllocated` together such that the sum of the three fields must always remain constant).
     2. When the critical section involves **blocking operations, file I/O, or multi-step state machine transitions** that cannot be cleanly expressed as a pure atomic CAS retry loop.

3. **Why does no in-JVM fix work across two instances?**
   - In-JVM synchronization primitives (`synchronized`, `ReentrantLock`, `AtomicLong`, `volatile`) rely entirely on **shared CPU cache coherency (MESI protocol) and memory addresses within a single operating system process address space**.
   - When an application scales horizontally to two containers or instances:
     - Instance 1 and Instance 2 have completely isolated memory heaps, independent garbage collectors, and separate JVM runtimes.
     - Instance 1's `AtomicLong` cannot inspect or invalidate Instance 2's CPU L1/L2 caches.
   - **Conclusion:** Cross-instance concurrency control **must be delegated to an external shared system of record**:
     - At the **database level** via atomic conditional SQL (`UPDATE ... WHERE remaining >= amount`) or optimistic locking (`@Version`),
     - Or at the **coordination layer** via distributed locks (Redis Redlock, ZooKeeper, etcd).

---

## P8 · Security, Events, and Live Updates (Day 9, Section 8, with L8 & L11)

### 1. Architectural Overview & Component Design

P8 establishes an end-to-end, production-grade event-driven architecture combining strict method-level security, asynchronous event streaming through Kafka, an idempotent Node.js notification consumer with Server-Sent Events (SSE), and a modern reactive single-page frontend.

```
+---------------------------------------------------------------------------------------------------+
|                                      BROWSER FRONTEND (SPA)                                       |
|  - Role Switcher (VIEWER, BIDDER, ADMIN) with JWT Authorization Header                             |
|  - Active Auctions Catalog with Real-Time Bidding Action                                          |
|  - Server-Sent Events (SSE) Live Feed with Idempotent Deduplication Tagging                       |
+------------------------------------+-----------------------------------+--------------------------+
                                     |                                   ^
            POST /api/bids (Bearer JWT)                                  | SSE Stream: GET /events
                                     v                                   |
+------------------------------------+------------+     +----------------+--------------------------+
|                  auction-api                    |     |                     notifier              |
|  - JwtAuthenticationFilter (validates JWT, MDC) |     |  - Express / Native HTTP + SSE Hub        |
|  - @PreAuthorize("hasAnyRole('BIDDER','ADMIN')")|     |  - Consumer Group: 'notifier-group'       |
|  - BidService (Atomic TX, Optimistic Locking)   |     |  - Idempotent Event Deduplication         |
|  - AuctionEventProducer (Partition by auctionId)|     |  - Persistent Store: notifications.json   |
+-------------------------+-----------------------+     +----------------^--------------------------+
                          |                                              |
                          | Kafka Publish: 'auction.events'              | Kafka Consume (Group ID)
                          +-------------------------> [ Kafka ] ---------+
```

#### A. JWT Authentication & Method-Level Authorization
- **Roles:** Defined in [`UserRole.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/security/model/UserRole.java): `VIEWER`, `BIDDER`, `ADMIN`.
- **JWT Provider:** Implemented in [`JwtService.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/security/service/JwtService.java) using HMAC-SHA256 (`jjwt` 0.12.6). Generates signed tokens containing claims: `sub` (username), `roles` (`ROLE_<ROLE>`), `iat`, and `exp`.
- **Filter Chain:** [`JwtAuthenticationFilter.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/security/filter/JwtAuthenticationFilter.java) extracts the Bearer token, validates signature and expiry, converts roles into `SimpleGrantedAuthority`, and sets `UsernamePasswordAuthenticationToken` in `SecurityContextHolder`.
- **Method Security:** Enabled via `@EnableMethodSecurity`.
  - [`BidController.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/bid/controller/BidController.java): `@PreAuthorize("hasAnyRole('BIDDER', 'ADMIN')")` protects `POST /api/bids`. `VIEWER` is strictly rejected.
  - [`RegistrarController.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/registrar/controller/RegistrarController.java): `@PreAuthorize("hasRole('ADMIN')")` protects registrar configuration. Both `VIEWER` and `BIDDER` are strictly rejected.
  - Unauthenticated access returns RFC 7807 `ProblemDetail` with HTTP 401 Unauthorized; authenticated access with insufficient role returns RFC 7807 `ProblemDetail` with HTTP 403 Forbidden.

#### B. Asynchronous Event Publishing
- In [`BidService.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/bid/service/BidService.java), upon successful transactional bid persistence and auction price update, a domain event [`AuctionKafkaEvent`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/kafka/event/AuctionKafkaEvent.java) (`eventType = "BID_PLACED"`) is constructed.
- Published via [`AuctionEventProducer.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/kafka/producer/AuctionEventProducer.java) to topic `auction.events`.
- **Partitioning Key Decision:** The partition key is set to `auctionId`. This guarantees strict FIFO ordering for all bids belonging to the same auction across Kafka partitions.

#### C. Notifier Service (Node.js) & Idempotent SSE Streaming
- **Stack:** Built in Node.js using native HTTP and `kafkajs`.
- **Consumer Group:** Subscribes to `auction.events` under consumer group `notifier-group`.
- **Idempotency by Event ID:**
  - Maintains a persistent `processedEventIds` set backed by `notifications_store.json`.
  - When a message arrives, its `eventId` is verified against the set. If already present, the event is logged as a duplicate and ignored (not broadcast, not appended to the feed).
  - If new, `eventId` is registered, the notification record is saved, and it is broadcast live via Server-Sent Events to all connected browser clients.
- **Server-Sent Events (`GET /events`):**
  - Keeps persistent HTTP connections open with `text/event-stream`.
  - Sends a keep-alive comment heartbeat (`: ping\n\n`) every 15s to prevent intermediate reverse proxy / firewall timeouts.
  - On client connection, transmits an `event: init` payload containing the entire historical notification store so late-joining or reconnecting clients have instant state synchronization.

#### D. Live Web Frontend
- Single-page interface in [`notifier/public/index.html`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/notifier/public/index.html).
- Features:
  - Interactive JWT Role Switcher (`VIEWER`, `BIDDER`, `ADMIN`) with real-time token reissuance.
  - Live Domain Auctions Catalog with real-time bidding inputs. Placing a bid as `VIEWER` displays a prominent 403 Forbidden alert explaining the method security denial.
  - Live SSE Notification Stream with animated cards displaying auction ID, bidder email, bid amount ($), and an idempotent badge confirming exact deduplication.

#### E. Kill & Restart Verification (Exactly-Once Delivery Proof)
- **Scenario:**
  1. `notifier` service is running, connected to Kafka under `notifier-group`.
  2. Kill `notifier` (`SIGTERM` / container stop).
  3. Place 3 successive bids on `auction-api`. `auction-api` writes bids to the database and publishes 3 `BID_PLACED` events to `auction.events`.
  4. Restart `notifier`.
  5. The consumer in `notifier-group` resumes from its last committed offset, reads the 3 pending messages from Kafka, deduplicates them against its store, commits offsets, and streams each notification to the browser exactly once. If Kafka redelivers any message, the idempotent filter drops the duplicate.

---

### 2. L8 · Make it Observable

#### A. Actuator Prometheus Exposition & Naming Contract
Spring Boot Actuator was exposed at `/actuator/prometheus` via `micrometer-registry-prometheus`.
All custom metrics strictly follow the platform naming contract: `<service>_<subject>_<unit>`:

| Archetype | Metric Name | Type | Description |
| :--- | :--- | :--- | :--- |
| **Liveness** | `auction_api_liveness_status` | Gauge | 1 = Healthy/Up, 0 = Down/Unhealthy |
| **Freshness** | `auction_api_sync_last_success_timestamp_seconds` | Gauge | Epoch seconds of last successful registrar sync |
| **Freshness** | `auction_api_sync_interval_seconds` | Gauge | Configured expected sync interval (e.g. 60s) |
| **Error Rate** | `auction_api_registrar_errors_total` | Counter | Total failed external registrar API calls |
| **Throughput** | `auction_api_registrar_requests_total` | Counter | Total external registrar API calls |
| **Dependency** | `auction_api_registrar_latency_seconds` | Timer | Latency distribution & percentiles of external calls |

#### B. Prometheus Alerting Rules (`alerts.yml`)
Located at [`docker/prometheus/alerts.yml`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/docker/prometheus/alerts.yml):

```yaml
groups:
  - name: auction-api-alerts
    rules:
      # Dimensionless Freshness Rule
      - alert: RegistrarSyncLagging
        expr: (time() - auction_api_sync_last_success_timestamp_seconds) / auction_api_sync_interval_seconds > 2
        for: 2m
        labels:
          service: auction-api
          severity: warning
        annotations:
          summary: "Registrar synchronization is stale"
          description: "Sync has not succeeded for more than 2x its configured interval."

      # Registrar Error Rate (5-minute window)
      - alert: RegistrarCallHighErrorRate
        expr: rate(auction_api_registrar_errors_total[5m]) / rate(auction_api_registrar_requests_total[5m]) > 0.05
        for: 3m
        labels:
          service: auction-api
          severity: critical
        annotations:
          summary: "High registrar error rate (>5%)"
          description: "Registrar requests are failing at an elevated rate."

      # Dependency Latency Degradation (p95 > 2s)
      - alert: RegistrarLatencyHigh
        expr: histogram_quantile(0.95, sum(rate(auction_api_registrar_latency_seconds_bucket[5m])) by (le)) > 2.0
        for: 5m
        labels:
          service: auction-api
          severity: warning
        annotations:
          summary: "External registrar latency degraded"
          description: "95th percentile registrar latency exceeded 2.0 seconds."

      # Service Liveness
      - alert: ServiceDown
        expr: auction_api_liveness_status == 0
        for: 30s
        labels:
          service: auction-api
          severity: critical
        annotations:
          summary: "auction-api liveness check failed"
          description: "The service reported liveness_status = 0."
```

#### C. High-Cardinality Metric Explosion Experiment
- **Mechanism:** In [`SecurityAndObservabilityP8L8Test.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/test/java/com/namekart/auction_api/security/SecurityAndObservabilityP8L8Test.java), we tested adding an unconstrained `user_id` tag (`user-uuid-1` ... `user-uuid-50`) to an auction bid counter.
- **Observation:** Each unique label combination creates an entirely new time series in memory and in the TSDB. The number of active series exploded by $+50$ immediately ($\text{series} = \text{base\_metrics} \times |\text{labels}|$). With 100,000 active users, this results in $100,000$ active series for a single metric, causing memory bloat, high scrape latency, and Prometheus OOM crashes.
- **Remediation:** Removed the dynamic tag. User IDs, request IDs, and query strings must belong in **structured logs** with `traceId` correlation, NEVER as Prometheus metric label values!

#### D. Structured JSON Logging with Trace ID Correlation
- Enabled Spring Boot 3 structured logging: `logging.structured.format.console=json`.
- [`TraceIdFilter.java`](file:///c:/Users/Acer/Desktop/mini-amp-ayush/mini-amp-ayush/auction-api/src/main/java/com/namekart/auction_api/common/filter/TraceIdFilter.java) captures or generates `X-Request-ID` / `traceId` and places it into SLF4J `MDC`.
- Sample log output:
```json
{"@timestamp":"2026-09-28T00:06:16.519Z","log.level":"INFO","process.pid":48660,"process.thread.name":"main","service.name":"auction-api","traceId":"59a1c5d0-39d2-421a-804b-7b00c34f51b9","message":"Successfully published BID_PLACED event 59a1c5d0-39d2-421a-804b-7b00c34f51b9 for auction 8"}
```

#### E. Answers to Laboratory Questions (L8)
1. **Why does the naming contract require `service` and `severity` labels?**
   - **`service`:** Enables multi-tenant alerting and unified routing in Alertmanager. Alertmanager routes alerts to specific on-call teams (e.g., Auction Team vs Core Infra) based on `service="auction-api"`. Without `service`, cross-service alerts collide or require separate rule blocks per service.
   - **`severity`:** Dictates paging escalation policies (`critical` pages engineers at 3 AM via PagerDuty/OpsGenie; `warning` files a Slack notification or ticket for the morning).
2. **Is each of your alerts a symptom or a cause?**
   - `RegistrarSyncLagging`: **Symptom** (the database is becoming stale; users don't see fresh domains). The cause could be network timeout, rate limit (HTTP 429), bad API key, or DNS resolution failure.
   - `RegistrarCallHighErrorRate`: **Symptom** (external HTTP calls are failing).
   - `RegistrarLatencyHigh`: **Cause/Indicator** of upstream registrar degradation that can cascade into pool exhaustion.
   - `ServiceDown`: **Symptom** (the service cannot serve traffic).
3. **How would you know tonight that your deploy broke something?**
   - Check the **Grafana Dashboard** immediately post-deploy:
     1. `auction_api_liveness_status` is 1 across all pods.
     2. `rate(http_server_requests_seconds_count[1m])` error rate (status 5xx) remains at 0.
     3. Sync freshness ratio $(time() - last\_success) / interval$ stays $< 1.5$.
     4. No new `critical` alerts fired in Alertmanager within 15 minutes of traffic shift.

---

### 3. L11 · Docker Networking and the Firewall That Lied

#### A. Empirical Laboratory Execution
The experiment was executed inside a root Linux WSL 2 environment with Docker, UFW, and iptables.

1. **Step 1: Container Launch & Initial Host Access**
   - Ran HTTP container: `docker run -d --name http-test -p 8081:80 python:3-alpine python3 -m http.server 80`
   - Curled from local host: `curl -I http://localhost:8081` -> `HTTP/1.0 200 OK`.
2. **Step 2: Enable Host Firewall (UFW Default Deny)**
   - Configured UFW:
     ```bash
     ufw default deny incoming
     ufw default allow outgoing
     ufw allow 22/tcp
     ufw --force enable
     ```
   - Confirmed `ufw status verbose`:
     ```
     Status: active
     Default: deny (incoming), allow (outgoing), deny (routed)
     To                         Action      From
     --                         ------      ----
     22/tcp                     ALLOW IN    Anywhere
     ```
   - UFW explicitly declared port 8081 is **NOT allowed** and incoming traffic is **denied by default**.
3. **Step 3: External Access Test (The Lie Proven)**
   - Curled host IP (`172.20.173.208:8081`) from Windows PowerShell (an external machine / network namespace):
     ```powershell
     curl.exe -I http://172.20.173.208:8081
     ```
   - **Result:**
     ```
     HTTP/1.0 200 OK
     Server: SimpleHTTP/0.6 Python/3.14.7
     Content-type: text/html; charset=utf-8
     ```
   - **Observation:** Even though UFW reported default deny and port 8081 blocked, the packet was accepted and answered!

#### B. iptables Packet Path & Chain Analysis
Inspected kernel iptables tables (`nat` and `filter`):

```bash
# NAT Table PREROUTING:
-A PREROUTING -m addrtype --dst-type LOCAL -j DOCKER

# NAT Table DOCKER Chain:
-A DOCKER ! -i docker0 -p tcp -m tcp --dport 8081 -j DNAT --to-destination 172.17.0.2:80

# FILTER Table FORWARD Chain:
-P FORWARD DROP
-A FORWARD -j DOCKER-USER
-A FORWARD -j DOCKER-FORWARD
-A FORWARD -j ufw-before-forward
...

# FILTER Table DOCKER Chain:
-A DOCKER -d 172.17.0.2/32 ! -i docker0 -o docker0 -p tcp -m tcp --dport 80 -j ACCEPT
```

**Why UFW's rules were never consulted:**
1. When packet arrives at `eth0` destined for `172.20.173.208:8081`, it enters `PREROUTING` in the `nat` table.
2. The `DOCKER` nat chain executes **DNAT**: rewriting destination IP/port to container IP `172.17.0.2:80`.
3. The Linux kernel routing engine evaluates the packet: since destination IP is `172.17.0.2` (on `docker0`), the packet is **NOT destined for the local host itself**. It is routed to the `FORWARD` filter chain.
4. UFW places its user inbound rules (`ufw default deny incoming`) inside the **`INPUT`** chain! The packet never enters `INPUT` at all!
5. In the `FORWARD` chain, Docker's `DOCKER-USER` chain is empty, and Docker's `DOCKER` chain contains `-A DOCKER -d 172.17.0.2/32 ... -j ACCEPT`. The packet is accepted immediately, before UFW's forward rules can run.

#### C. Step 5: Fixing via `DOCKER-USER` Chain
- Added rule:
  ```bash
  iptables -I DOCKER-USER -i eth0 -p tcp --dport 80 -j DROP
  ```
- Retried curl from Windows:
  ```
  curl: (28) Connection timed out after 3011 milliseconds
  ```
- The rule intercepted the packet in `DOCKER-USER` before Docker's `ACCEPT` rule in the `FORWARD` chain.

#### D. Step 6: Loopback Binding Fix (`-p 127.0.0.1:8081:80`)
- Flushed `DOCKER-USER` (`iptables -F DOCKER-USER`) and recreated container with explicit loopback binding:
  ```bash
  docker run -d --name http-test -p 127.0.0.1:8081:80 python:3-alpine python3 -m http.server 80
  ```
- Checked resulting NAT table:
  ```
  -A DOCKER -d 127.0.0.1/32 ! -i docker0 -p tcp -m tcp --dport 8081 -j DNAT --to-destination 172.17.0.2:80
  ```
- Notice the `-d 127.0.0.1/32` condition. When external traffic hits `eth0` (`172.20.173.208:8081`), it does NOT match `127.0.0.1`. No DNAT occurs.
- External curl from Windows:
  ```
  curl: (28) Connection timed out after 3003 milliseconds (Failed!)
  ```
- Local curl from host:
  ```
  curl -I http://127.0.0.1:8081 -> HTTP/1.0 200 OK (Succeeded!)
  ```

#### E. Answers to Laboratory Questions (L11)
1. **Why does Docker insert its rules where it does?**
   - Docker manages container port publishing using Linux network namespaces and bridge interfaces (`docker0`). To route traffic from host physical interfaces to virtual container interfaces, it must perform DNAT in `PREROUTING`. Because the routed traffic passes *through* the host rather than *terminating* at the host, it traverses the `FORWARD` chain. Docker inserts its jumps at the very top of `FORWARD` so containers receive network traffic out-of-the-box without requiring administrators to manually configure bridge forwarding rules for every container launch.
2. **In your teaching project, which services should bind to loopback only?**
   - **`mysql` (`3306`)**: Internal database only consumed by `auction-api`. Must never be publicly exposed.
   - **`kafka` (`9092`)**: Internal event broker consumed by `auction-api` and `notifier`.
   - **`prometheus` (`9090`)**: Internal metrics store scraped by Grafana.
   - Any administration interfaces (e.g. `kafka-ui`) unless behind an authenticated reverse proxy / VPN.
   - In production, only the reverse proxy (Nginx / Traefik / Envoy) or public API gateway binds to `0.0.0.0:80 / 443`.
3. **What one-line rule would you add to a deployment checklist?**
   > *"Never publish a container port without an explicit IP binding: use `-p 127.0.0.1:host_port:container_port` for all internal dependencies and verify with `ss -tulpn` that no database or broker listens on `0.0.0.0`."*
