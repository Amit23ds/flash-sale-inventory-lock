package com.example.demo.service.impl;

import com.example.demo.config.PurchaseProperties;
import com.example.demo.dto.PurchaseRequest;
import com.example.demo.dto.PurchaseResponse;
import com.example.demo.entity.Order;
import com.example.demo.exception.ConcurrentStockUpdateException;
import com.example.demo.exception.LockAcquisitionException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.service.PurchaseService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.List;
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

    // Metric tags are deliberately low-cardinality: never tag with productId or userId.
    private static final String PURCHASE_METRIC = "flashsale.purchase";
    private static final String LOCK_WAIT_METRIC = "flashsale.lock.wait";
    private static final String LOCK_HELD_METRIC = "flashsale.lock.held";
    private static final String OPTIMISTIC_RETRY_METRIC = "flashsale.optimistic.retry";
    private static final String LOCK_FALLBACK_METRIC = "flashsale.lock.fallback";

    private final PurchaseTransactionalService purchaseTransactionalService;
    private final RedissonClient redissonClient;
    private final PurchaseProperties purchaseProperties;
    private final MeterRegistry meterRegistry;

    // Register every known series at zero on startup. Otherwise a series only appears on its first
    // increment, and rate() in Prometheus cannot see that first increase.
    @PostConstruct
    void registerMeters() {
        for (String outcome : List.of("confirmed", "out_of_stock", "not_found", "lock_timeout",
                "version_conflict", "error")) {
            meterRegistry.counter(PURCHASE_METRIC, "outcome", outcome);
        }
        for (String result : List.of("acquired", "timeout", "redis_error")) {
            meterRegistry.timer(LOCK_WAIT_METRIC, "result", result);
        }
        meterRegistry.timer(LOCK_HELD_METRIC);
        meterRegistry.counter(OPTIMISTIC_RETRY_METRIC);
        meterRegistry.counter(LOCK_FALLBACK_METRIC);
    }

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        try {
            PurchaseResponse response = lockAndPurchase(request);
            meterRegistry.counter(PURCHASE_METRIC, "outcome", "confirmed").increment();
            return response;
        } catch (RuntimeException exception) {
            meterRegistry.counter(PURCHASE_METRIC, "outcome", outcomeOf(exception)).increment();
            throw exception;
        }
    }

    private PurchaseResponse lockAndPurchase(PurchaseRequest request) {
        Long productId = request.getProductId();
        RLock lock = redissonClient.getLock("product-lock:" + productId);

        Timer.Sample lockWait = Timer.start(meterRegistry);
        boolean locked;
        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            stopLockWait(lockWait, "timeout");
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Interrupted while acquiring lock");
        } catch (RedisException exception) {
            stopLockWait(lockWait, "redis_error");
            meterRegistry.counter(LOCK_FALLBACK_METRIC).increment();
            // Degraded mode: the lock layer is down, so rely on @Version alone. Stock stays
            // correct; under contention more buyers get 409 instead of being serialized.
            log.warn("Redis lock unavailable for productId={}, falling back to optimistic locking: {}",
                    productId, exception.toString());
            return toResponse(decrementWithRetry(productId, request.getUserId()));
        }
        stopLockWait(lockWait, locked ? "acquired" : "timeout");

        if (!locked) {
            throw new LockAcquisitionException("Too many requests");
        }

        Timer.Sample lockHeld = Timer.start(meterRegistry);
        try {
            // Lock is acquired outside the transaction so we never hold a DB connection
            // while blocked on Redis, and released only after commit so a waiter cannot
            // read pre-commit stock. The @Version retry below is a DB-level fallback in
            // case the Redis lock is unavailable or its lease expires under load.
            return toResponse(decrementWithRetry(productId, request.getUserId()));
        } finally {
            releaseLock(lock, productId);
            lockHeld.stop(meterRegistry.timer(LOCK_HELD_METRIC));
        }
    }

    private void stopLockWait(Timer.Sample lockWait, String result) {
        lockWait.stop(meterRegistry.timer(LOCK_WAIT_METRIC, "result", result));
    }

    private static String outcomeOf(RuntimeException exception) {
        return switch (exception) {
            case OutOfStockException _ -> "out_of_stock";
            case ProductNotFoundException _ -> "not_found";
            case LockAcquisitionException _ -> "lock_timeout";
            case ConcurrentStockUpdateException _ -> "version_conflict";
            default -> "error";
        };
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
                meterRegistry.counter(OPTIMISTIC_RETRY_METRIC).increment();
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
