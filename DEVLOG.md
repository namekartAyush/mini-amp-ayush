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

