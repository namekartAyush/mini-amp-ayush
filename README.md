# Mini AMP - Domain Auction Platform

Mini AMP is a high-performance domain auction platform built with Spring Boot 3, MySQL, Kafka, and Docker.

---

## Prerequisites

- **Java**: JDK 21+
- **Maven**: Included via Maven Wrapper (`./mvnw`)
- **Docker**: Docker Desktop with Docker Compose v2+

---

## Quickstart & Verification Guide

### 1. Configure Environment Variables
Copy `.env.example` to create your local `.env` configuration:
```bash
cp .env.example .env
```

---

### 2. Infrastructure Setup (P0 · Skeleton)
Start MySQL 8.0, KRaft Kafka, and Kafka UI using Docker Compose:
```bash
docker compose up -d
```

To verify all containers are running and healthy:
```bash
docker compose ps
```

Services exposed:
- **MySQL**: `localhost:3307` (database: `mini_amp`, user: `amp_user`, password: `amppassword`)
- **Kafka**: `localhost:9092` (KRaft mode)
- **Kafka UI**: [http://localhost:8081](http://localhost:8081)

---

### 3. Spring Boot API (P0 · Skeleton)
Navigate to the `auction-api` directory and start the application:
```bash
cd auction-api
./mvnw spring-boot:run
```

#### Health Endpoint Verification
Verify that the Spring Boot 3 application is connected to MySQL and reporting healthy status:
```bash
curl http://localhost:8080/actuator/health
```

Expected JSON response:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "MySQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

---

### 4. Java Warm-up CLI Execution (P1 · Java Warm-up)
The P1 Java Warm-up module parses a JSON dataset of 2,500 domain records (`src/main/resources/domains.json`), performs stream operations (filtering, grouping, financial aggregations), and outputs portfolio statistics.

Run directly from the command line through Maven:
```bash
cd auction-api
./mvnw compile exec:java "-Dexec.mainClass=com.namekart.auction_api.warmup.DomainWarmupApp"
```

Or pass `--warmup` during Spring Boot run:
```bash
cd auction-api
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--warmup"
```

---

## Milestones Status

- [x] **P0 · Skeleton**: Docker Compose (MySQL, Kafka, Kafka UI), Spring Boot 3 app, JPA/Actuator, Health Endpoint verified.
- [x] **P1 · Java Warm-up**: Domain records JSON parser, Stream filter & group by TLD, valuation statistics, Maven CLI executable.
- [x] **P2 · Architecture, Packaging & Bean Graph**: Package-by-Feature, `@ConfigurationProperties` record for registrar settings, strict constructor injection, and deterministic bean graph.

