package com.example.demo.service.impl;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.dto.PurchaseResponse;
import com.example.demo.entity.Order;
import com.example.demo.exception.ConcurrentStockUpdateException;
import com.example.demo.exception.LockAcquisitionException;
import com.example.demo.service.PurchaseService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PurchaseServiceImpl implements PurchaseService {

    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 3;

    private final PurchaseTransactionalService purchaseTransactionalService;
    private final RedissonClient redissonClient;

    @Override
    public PurchaseResponse purchase(PurchaseRequest request) {
        RLock lock = redissonClient.getLock("product-lock:" + request.getProductId());
        boolean locked = false;

        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);
            if (!locked) {
                throw new LockAcquisitionException("Too many requests");
            }

            // Lock is acquired outside the transaction so we never hold a DB connection
            // while blocked on Redis, and released only after commit so a waiter cannot
            // read pre-commit stock. The @Version retry below is a DB-level fallback in
            // case the Redis lock is unavailable or its lease expires under load.
            Order savedOrder = decrementWithRetry(request.getProductId(), request.getUserId());

            return PurchaseResponse.builder()
                    .orderId(savedOrder.getId())
                    .userId(savedOrder.getUserId())
                    .productId(savedOrder.getProductId())
                    .orderStatus(savedOrder.getOrderStatus())
                    .createdAt(savedOrder.getCreationTimestamp())
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Interrupted while acquiring lock");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Order decrementWithRetry(Long productId, Long userId) {
        int attempts = 0;
        while (true) {
            try {
                return purchaseTransactionalService.decrementStockAndCreateOrder(productId, userId);
            } catch (ObjectOptimisticLockingFailureException exception) {
                attempts++;
                if (attempts >= MAX_OPTIMISTIC_LOCK_RETRIES) {
                    throw new ConcurrentStockUpdateException(
                            "Could not reserve stock after " + attempts + " concurrent retries");
                }
            }
        }
    }
}
