package com.example.demo.service.impl;

import com.example.demo.config.PurchaseProperties;
import com.example.demo.dto.PurchaseRequest;
import com.example.demo.dto.PurchaseResponse;
import com.example.demo.entity.Order;
import com.example.demo.exception.ConcurrentStockUpdateException;
import com.example.demo.exception.LockAcquisitionException;
import com.example.demo.service.PurchaseService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseServiceImpl implements PurchaseService {

    private final PurchaseTransactionalService purchaseTransactionalService;
    private final RedissonClient redissonClient;
    private final PurchaseProperties purchaseProperties;

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        Long productId = request.getProductId();
        RLock lock = redissonClient.getLock("product-lock:" + productId);

        boolean locked;
        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Interrupted while acquiring lock");
        } catch (RedisException exception) {
            // Degraded mode: the lock layer is down, so rely on @Version alone. Stock stays
            // correct; under contention more buyers get 409 instead of being serialized.
            log.warn("Redis lock unavailable for productId={}, falling back to optimistic locking: {}",
                    productId, exception.toString());
            return toResponse(decrementWithRetry(productId, request.getUserId()));
        }

        if (!locked) {
            throw new LockAcquisitionException("Too many requests");
        }

        try {
            // Lock is acquired outside the transaction so we never hold a DB connection
            // while blocked on Redis, and released only after commit so a waiter cannot
            // read pre-commit stock. The @Version retry below is a DB-level fallback in
            // case the Redis lock is unavailable or its lease expires under load.
            return toResponse(decrementWithRetry(productId, request.getUserId()));
        } finally {
            releaseLock(lock, productId);
        }
    }

    private void releaseLock(RLock lock, Long productId) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RedisException exception) {
            // The transaction has already finished, so a failed unlock must not turn the result
            // into a 500. Redisson cancels the watchdog renewal even when unlock fails, so the
            // key is not extended any more and expires on its own within lockWatchdogTimeout
            // (30 s by default). Until then, other buyers of this product may wait out tryLock
            // and get 429 once Redis is reachable again.
            log.warn("Failed to release Redis lock for productId={}, it will expire with its lease: {}",
                    productId, exception.toString());
        }
    }

    private Order decrementWithRetry(Long productId, Long userId) {
        int attempts = 0;
        while (true) {
            try {
                return purchaseTransactionalService.decrementStockAndCreateOrder(productId, userId);
            } catch (ObjectOptimisticLockingFailureException exception) {
                attempts++;
                if (attempts >= purchaseProperties.maxOptimisticRetries()) {
                    throw new ConcurrentStockUpdateException(
                            "Could not reserve stock after " + attempts + " concurrent retries");
                }
                backOff();
            }
        }
    }

    // Random jitter so buyers that just collided on the same @Version do not retry in lockstep.
    private void backOff() {
        long minMillis = purchaseProperties.retryBackoffMin().toMillis();
        long maxMillis = Math.max(minMillis, purchaseProperties.retryBackoffMax().toMillis());
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConcurrentStockUpdateException("Interrupted while retrying stock update");
        }
    }

    private PurchaseResponse toResponse(Order savedOrder) {
        return PurchaseResponse.builder()
                .orderId(savedOrder.getId())
                .userId(savedOrder.getUserId())
                .productId(savedOrder.getProductId())
                .orderStatus(savedOrder.getOrderStatus())
                .createdAt(savedOrder.getCreationTimestamp())
                .build();
    }
}
