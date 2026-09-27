# Mini AMP · High-Performance Domain Auction Platform

Mini AMP is a high-concurrency, event-driven domain auction platform built with Spring Boot 3, MySQL 8, Apache Kafka (KRaft), Node.js (SSE notifications), Prometheus, and Grafana.

---

## 1. System Architecture

```
                          +------------------------------------------+
                          |             WEB BROWSER (SPA)            |
                          |  - Role switcher (VIEWER, BIDDER, ADMIN) |
                          |  - Real-time catalog & bidding UI        |
                          |  - Live SSE notification feed            |
                          +--------------------+---------------------+
                                               |
                     POST /api/bids (Bearer JWT) | GET /events (SSE stream)
                                               v
+----------------------------------------------+     +-----------------------------------------+
|                 AUCTION-API                  |     |                 NOTIFIER                |
|  - Spring Boot 3.4 (Java 21/25)              |     |  - Node.js (Express + SSE Hub)          |
|  - JWT Role-based security (method-level)    |     |  - Consumer Group: 'notifier-group'     |
|  - Transactional bidding + optimistic lock   |     |  - Persistent idempotent store          |
|  - Closing sprint concurrency (Loom threads) |     |  - Streams live updates to browser      |
|  - Metrics: Prometheus /actuator/prometheus  |     |  - Port: 3001                           |
|  - Port: 8080                                |     |                                         |
+----------------------+-----------------------+     +--------------------+--------------------+
                       |                                                  ^
                       | Writes bids & auctions                           |
                       v                                                  | Consumes events
+----------------------+-----------------------+                          | (groupId: notifier-group)
|              MYSQL 8.0 DATABASE              |                          |
|  - Port: 3307 (Internal Docker: 3306)        |                          |
|  - Optimistic locking (@Version)             |                          |
|  - Composite indexes on (status, end_time)   |                          |
+----------------------------------------------+                          |
                       |                                                  |
                       | Publishes domain events                          |
                       +-------------------> [ KAFKA (KRaft) ] -----------+
                                             - Topic: auction.events
                                             - Key: auctionId (strict FIFO)
                                             - Port: 9092
```

---

## 2. Prerequisites & Toolchain

A clean clone requires only standard tools:
- **Operating System:** Windows, macOS, or Linux
- **Java:** JDK 21+ (Java 25 supported)
- **Node.js:** Node 18+ (for running `notifier` standalone, or use Docker)
- **Maven:** Bundled via `./mvnw` / `mvnw.cmd` (no local Maven installation required)
- **Docker:** Docker Desktop or Docker Engine with Docker Compose v2+

---

## 3. Quickstart: Clean Clone to Running System

### Step 1: Clone and Configure Environment
```bash
git clone <repo-url> mini-amp
cd mini-amp

# Create environment configuration from template
cp .env.example .env
```

### Step 2: Start the Entire Stack via Docker Compose
One command starts MySQL, Kafka, Kafka UI, Prometheus, Grafana, and compiles & runs both `auction-api` and `notifier`:
```bash
docker compose up -d --build
```

Verify all 6 services are up and healthy:
```bash
docker compose ps
```

| Service | Host Port | Internal Port | Purpose | Health Check / Verification |
| :--- | :--- | :--- | :--- | :--- |
| **`auction-api`** | `8080` | `8080` | Spring Boot REST API | `curl http://localhost:8080/actuator/health` |
| **`notifier`** | `3001` | `3001` | SSE live feed & web UI | [http://localhost:3001](http://localhost:3001) |
| **`mysql`** | `3307` | `3306` | Primary database | `mysql -h 127.0.0.1 -P 3307 -u amp_user -pamppassword` |
| **`kafka`** | `9092` | `9092` | Event streaming broker | `nc -zv localhost 9092` |
| **`kafka-ui`** | `8081` | `8080` | Kafka topic inspector | [http://localhost:8081](http://localhost:8081) |
| **`prometheus`** | `9090` | `9090` | Metrics scraper & TSDB | [http://localhost:9090](http://localhost:9090) |
| **`grafana`** | `3000` | `3000` | Observability dashboard | [http://localhost:3000](http://localhost:3000) (admin / admin) |

---

## 4. Running All Tests

You can run the entire automated test suite locally without Docker using the embedded H2/Kafka profile and mock slices:

```bash
cd auction-api
./mvnw clean test
```
*(On Windows PowerShell, use `.\mvnw.cmd clean test`)*

### Targeted Test Suites

| Test Suite | Purpose | Command |
| :--- | :--- | :--- |
| **Controller Slice Tests** | Fast `@WebMvcTest` controller slice tests with mock security context | `./mvnw test -Dtest=BidControllerSliceTest,AuctionControllerSliceTest` |
| **Testcontainers Real MySQL** | Runs repository tests against a real disposable MySQL 8 container | `./mvnw test -Dtest=AuctionRepositoryTestcontainersTest` |
| **Notifier Contract Test** | Validates Kafka JSON serialization/deserialization against Node schema | `./mvnw test -Dtest=EventPayloadContractTest` |
| **Closing Sprint Concurrency** | Verifies 1,000 iterations of concurrent bids under shared budget (P7/L10) | `./mvnw test -Dtest=ClosingSprintAndConcurrencyL10Test` |
| **Connection Pool Sizing** | Validates pool exhaustion and arithmetic sizing under load (L4) | `./mvnw test -Dtest=ConnectionPoolExhaustionAndSizingTest` |
| **Configuration Binding** | Verifies typed `@ConfigurationProperties` and startup failure on bad configs | `./mvnw test -Dtest=ConfigurationPropertiesValidationTest` |

---

## 5. Security & Authentication

The platform uses HMAC-SHA256 JWT tokens with role-based method security (`@PreAuthorize`).

### User Roles & Permissions

| Role | Permissions | Sample User Token |
| :--- | :--- | :--- |
| **`VIEWER`** | Read auctions (`GET /api/auctions`), SSE stream (`GET /events`). Bidding is rejected (HTTP 403). | Header `Authorization: Bearer <viewer_jwt>` |
| **`BIDDER`** | All `VIEWER` permissions + Place bids (`POST /api/bids`). | Header `Authorization: Bearer <bidder_jwt>` |
| **`ADMIN`** | All `BIDDER` permissions + Configure registrar & trigger sync. | Header `Authorization: Bearer <admin_jwt>` |

### Quick Authentication Examples

Obtain sample tokens or use the built-in token generator in the web UI at `http://localhost:3001`.

#### 1. Place a Bid as BIDDER (Allowed)
```bash
curl -X POST http://localhost:8080/api/bids \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSIsInJvbGVzIjpbIlJPTEVfQklEREVSIl0sImlhdCI6MTY3MDAwMDAwMCwiZXhwIjoxOTkwMDAwMDAwfQ.g8Yw9vB_7cR8U4VvP3e2..." \
  -d '{"auctionId": 1, "bidderEmail": "alice@example.com", "amount": 1500.00}'
```

#### 2. Attempt to Place a Bid as VIEWER (Rejected with 403 ProblemDetail)
```bash
curl -X POST http://localhost:8080/api/bids \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib2IiLCJyb2xlcyI6WyJST0xFX1ZJRVdFUiJdLCJpYXQiOjE2NzAwMDAwMDAsImV4cCI6MTk5MDAwMDAwMH0.abc..." \
  -d '{"auctionId": 1, "bidderEmail": "bob@example.com", "amount": 1600.00}'
```
Response:
```json
{
  "type": "https://api.miniamp.com/errors/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "Access Denied: You do not have sufficient permissions to perform this action",
  "timestamp": "2026-09-28T00:10:00Z"
}
```

---

## 6. End-to-End Verification Walkthrough

### 1. View Live Notification Stream in Browser
Open `http://localhost:3001` in your browser.
1. The modern glassmorphism frontend loads active domain auctions.
2. Select the **BIDDER** role in the role selector.
3. Click **"Place Bid"** on any auction.
4. **Observe:** The bid is persisted in MySQL, an event is published to `auction.events`, consumed by `notifier` with idempotent deduplication, and broadcast live via Server-Sent Events to the notification panel.

### 2. Verify Kill & Restart (Exactly-Once Semantics)
1. Stop the notifier container: `docker compose stop notifier`
2. Place 3 bids on `auction-api` via curl.
3. Start the notifier container: `docker compose start notifier`
4. **Observe:** The consumer in `notifier-group` resumes from its committed offset, processes the 3 pending events, records their `eventId`s into its persistent deduplication store, and streams each notification to the browser feed exactly once.

### 3. Verify Observability & Metrics
- Scrape Prometheus metrics:
  ```bash
  curl http://localhost:8080/actuator/prometheus | grep auction_api
  ```
  Look for:
  - `auction_api_liveness_status`: Gauge (1 = healthy)
  - `auction_api_sync_last_success_timestamp_seconds`: Gauge (timestamp)
  - `auction_api_registrar_requests_total`: Counter
  - `auction_api_registrar_errors_total`: Counter
- Open Grafana at `http://localhost:3000` (credentials: `admin` / `admin`). The pre-provisioned dashboard displays real-time RPS, p95 latency, pool utilization, and registrar health.

---

## 7. Developer CLI Tools

### High-Speed Database Seeder (P4)
Populate 10,000 auctions and 50,000 bids with batch inserts:
```bash
cd auction-api
./mvnw spring-boot:run -Dspring-boot.run.arguments="--seed"
```

### Domain Portfolio Warm-up CLI (P1)
Parse, filter, group, and calculate domain portfolio statistics from `domains.json`:
```bash
cd auction-api
./mvnw compile exec:java -Dexec.mainClass="com.namekart.auction_api.warmup.DomainWarmupApp"
```

---

## 8. Repository Layout

```
mini-amp/
├── auction-api/                     # Core Spring Boot 3 API
│   ├── src/main/java/               # Package-by-feature implementation
│   │   └── com/namekart/auction_api/
│   │       ├── auction/             # Domain auctions entity, repo, controller
│   │       ├── bid/                 # Transactional bidding & optimistic locking
│   │       ├── registrar/           # Feign & RestClient registrar clients
│   │       ├── sprint/              # Closing sprint & concurrency benchmarks
│   │       ├── security/            # JWT provider, filter & method security
│   │       ├── kafka/               # Kafka producers & event models
│   │       └── common/              # Metrics, logging filters & global error handler
│   └── src/test/java/               # Unit, slice, contract, & concurrency tests
├── notifier/                        # Node.js event consumer & SSE service
│   ├── src/index.js                 # Kafkajs consumer, deduplication, SSE hub
│   └── public/index.html            # Glassmorphism frontend SPA
├── docker/                          # Infrastructure configs
│   ├── prometheus/                  # Prometheus config & alerts.yml
│   └── grafana/                     # Provisioned datasources & dashboards
├── docker-compose.yml               # Multi-container orchestration
├── .env.example                     # Environment variables template
├── DEVLOG.md                        # Complete chronological development log
└── README.md                        # This document
```
