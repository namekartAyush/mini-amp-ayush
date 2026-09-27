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
- **Verification:** Full automated test suite passed with **19/19 passing tests** (`ConfigurationPrecedenceTest`: 5/5, `PersistenceAndNPlusOneTest`: 5/5, `CrudAndValidationIntegrationTest`: 6/6, `RegistrarBeanGraphTest`: 2/2, `AuctionApiApplicationTests`: 1/1).



