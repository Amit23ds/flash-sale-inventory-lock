# Flash Sale Inventory Lock System Report

## Objective

Prevent overselling during flash sale using Redis distributed locking.

---

## Architecture

Client

↓

Spring Boot

↓

Redisson Lock

↓

MySQL

↓

Redis

---

## Challenges

- Concurrent inventory updates
- Lock acquisition
- Transaction consistency

---

## Solution

Used Redisson distributed locks before reducing inventory.

---

## Testing

Unit Test

Integration Test

JMeter

---

## Result

100 Requests

10 Successful Purchases

90 Out Of Stock

No Negative Inventory

No Overselling