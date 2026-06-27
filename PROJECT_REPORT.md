# Flash Sale Inventory Lock System Report

## Objective

The objective of this project is to prevent inventory overselling during high-concurrency flash sale events by implementing distributed locking using Redis and Redisson.

---

## Tech Stack

* Java 21
* Spring Boot 3
* Spring Data JPA
* MySQL
* Redis
* Redisson
* Docker
* Maven
* JUnit 5
* Mockito
* Apache JMeter

---

## System Architecture

Client (JMeter / REST Client)

↓

Spring Boot REST API

↓

Redisson Distributed Lock

↓

Business Logic

↓

MySQL Database

↓

Redis (Lock Management)

---

## Key Features

* REST API for purchase requests
* Distributed locking using Redisson
* Transactional inventory updates
* Global exception handling
* MySQL persistence
* Dockerized environment
* Unit and Integration Testing
* Concurrent load testing using Apache JMeter

---

## Challenges

* Preventing race conditions during concurrent purchases
* Avoiding overselling of limited inventory
* Ensuring transactional consistency
* Managing distributed locks efficiently

---

## Solution

Each purchase request acquires a Redis distributed lock based on the product ID before checking and updating inventory. The lock ensures that only one request can modify the product stock at a time, preventing concurrent updates from causing overselling.

---

## Testing

### Unit Testing

* PurchaseService
* ProductService

### Integration Testing

* PurchaseController
* Validation
* Exception Handling

### Load Testing

Apache JMeter

Configuration:

* 100 concurrent requests
* POST /api/v1/purchase

---

## Results

* Total Requests: 100
* Successful Purchases: 10
* Out of Stock Responses: 90
* Remaining Stock: 0
* Overselling: Prevented
* Negative Inventory: Not Observed

---

## Conclusion

The application successfully demonstrates how Redis distributed locking can be used with Spring Boot to safely process concurrent purchase requests while maintaining inventory consistency and preventing overselling.
