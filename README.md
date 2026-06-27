# Flash Sale Inventory Lock System

## Overview

A Spring Boot application demonstrating a distributed inventory locking mechanism using Redisson and Redis to prevent overselling during flash sales.

---

## Tech Stack

- Java 21+
- Spring Boot 3
- Spring Data JPA
- MySQL
- Redis
- Redisson
- Docker
- Maven
- JUnit 5
- Mockito
- JMeter

---

## Features

- Distributed locking
- Flash sale purchase API
- Stock management
- Exception handling
- REST API
- Dockerized deployment
- Unit Testing
- Integration Testing
- Load Testing

---

## Project Structure

src
├── controller
├── service
├── repository
├── entity
├── dto
├── exception

---

## API

POST /api/v1/purchase

Request

{
  "userId":1,
  "productId":1
}

Response

{
  "orderId":1,
  "userId":1,
  "productId":1,
  "orderStatus":"CONFIRMED"
}

---

## Running

docker compose up --build

mvn spring-boot:run

---

## Testing

mvn test

---

## Load Test

Apache JMeter

100 concurrent requests

Expected Result

- 10 Success
- 90 Out Of Stock
- No Overselling

---

## Author

Amit Kumar Yadav