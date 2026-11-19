# Event-Driven E-Commerce Order & Inventory Service

A high-concurrency, fault-tolerant order and inventory microservice built with **Java 21**, **Spring Boot 4.1**, **MySQL**, **Redis**, **Apache Kafka**, and **Resilience4j**.

## Architecture

```text
Client ──► REST API ──► Redis (Cache-Aside & Idempotency SETNX)
               │
               ├──► MySQL (Order: PENDING, Flyway-managed)
               └──► afterCommit() ──► Kafka [order-events] ──► InventoryConsumer
                                                                  ├── Pessimistic Lock (SELECT FOR UPDATE)
                                                                  ├── Payment (Resilience4j Retry + Circuit Breaker)
                                                                  └── Rollback Stock on Failure / Route to .DLT
```

## Tech Stack

| Category | Technologies |
| :--- | :--- |
| **Language & Framework** | Java 21, Spring Boot 4.1, Spring WebMVC, Spring Data JPA, Hibernate |
| **Database & Migrations** | MySQL 8.0 (InnoDB), Flyway, HikariCP |
| **Caching & Distributed Locks** | Redis 7.2, Spring Data Redis (JSON Serialization) |
| **Event Streaming** | Apache Kafka, Spring Kafka, Dead-Letter Topics (`order-events.DLT`) |
| **Resilience & Observability** | Resilience4j (Circuit Breaker, Retry), Spring Boot Actuator, Micrometer |
| **Testing & DevOps** | Testcontainers (MySQL, Kafka, Redis), JUnit 5, Awaitility, Docker & Docker Compose |

## Key Features
- **Concurrency & Deadlock Safety:** Uses MySQL pessimistic row locking (`SELECT ... FOR UPDATE`) and deterministic SKU lock ordering to prevent overselling under concurrent flash-sale traffic.
- **Cache-Aside Catalog:** Product lookups hit Redis first (10m TTL) with automatic cache eviction/updates on stock changes.
- **API Idempotency:** Atomic Redis `SETNX` locks on `Idempotency-Key` headers prevent duplicate orders during network retries.
- **Transactional Outbox & DLT:** Publishes Kafka events strictly inside `afterCommit()` hooks and routes unrecoverable failures to `order-events.DLT`.
- **Resilience & Compensating Rollbacks:** Protects payment calls with Resilience4j `@Retry` and `@CircuitBreaker`, automatically restoring reserved stock if payment fails.

## Quick Start

1. **Start Infrastructure:**
   ```bash
   docker compose up -d mysql redis kafka
   ```
2. **Run Application:**
   ```bash
   ./mvnw spring-boot:run
   ```
3. **Run Testcontainers Integration & Concurrency Suite:**
   ```bash
   ./mvnw test
   ```

## Core Endpoints

| Method | Endpoint | Purpose |
| :--- | :--- | :--- |
| `GET` | `/api/v1/products/{sku}` | Fetch product details (Redis cached) |
| `PATCH` | `/api/v1/products/{sku}/stock` | Update inventory & refresh cache |
| `POST` | `/api/v1/orders` | Submit async order (`Idempotency-Key` header) |
| `GET` | `/api/v1/orders/{orderNumber}` | Check order status (`PENDING`, `CONFIRMED`, `FAILED`) |
| `POST` | `/api/v1/orders/simulate-payment-outage?enabled=true` | Toggle payment failures to test Circuit Breaker & rollback |
