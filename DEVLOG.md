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


