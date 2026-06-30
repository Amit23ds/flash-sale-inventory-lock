package com.example.demo.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.dto.PurchaseResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.ConcurrentStockUpdateException;
import com.example.demo.exception.LockAcquisitionException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.exception.ProductNotFoundException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {

    @Mock
    private PurchaseTransactionalService purchaseTransactionalService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @InjectMocks
    private PurchaseServiceImpl purchaseService;

    @Test
    void purchase_returnsResponseWhenPurchaseSucceeds() throws Exception {
        PurchaseRequest request = PurchaseRequest.builder()
                .userId(10L)
                .productId(20L)
                .build();
        Order savedOrder = Order.builder()
                .id(55L)
                .userId(10L)
                .productId(20L)
                .orderStatus(OrderStatus.CONFIRMED)
                .creationTimestamp(LocalDateTime.of(2026, 6, 27, 12, 0))
                .build();

        when(redissonClient.getLock("product-lock:20")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(purchaseTransactionalService.decrementStockAndCreateOrder(20L, 10L))
                .thenReturn(savedOrder);

        PurchaseResponse response = purchaseService.purchase(request);

        assertEquals(55L, response.getOrderId());
        assertEquals(10L, response.getUserId());
        assertEquals(20L, response.getProductId());
        assertEquals(OrderStatus.CONFIRMED, response.getOrderStatus());
        assertEquals(LocalDateTime.of(2026, 6, 27, 12, 0), response.getCreatedAt());

        verify(redissonClient).getLock("product-lock:20");
        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verify(purchaseTransactionalService).decrementStockAndCreateOrder(20L, 10L);
        verify(lock).unlock();
        verifyNoMoreInteractions(redissonClient, purchaseTransactionalService);
    }

    @Test
    void purchase_releasesLockWhenOutOfStock() throws Exception {
        PurchaseRequest request = PurchaseRequest.builder()
                .userId(10L)
                .productId(20L)
                .build();

        when(redissonClient.getLock("product-lock:20")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(purchaseTransactionalService.decrementStockAndCreateOrder(20L, 10L))
                .thenThrow(new OutOfStockException("Out of stock"));

        OutOfStockException exception = assertThrows(
                OutOfStockException.class,
                () -> purchaseService.purchase(request));

        assertEquals("Out of stock", exception.getMessage());
        verify(purchaseTransactionalService).decrementStockAndCreateOrder(20L, 10L);
        verify(lock).unlock();
    }

    @Test
    void purchase_releasesLockWhenProductMissing() throws Exception {
        PurchaseRequest request = PurchaseRequest.builder()
                .userId(10L)
                .productId(20L)
                .build();

        when(redissonClient.getLock("product-lock:20")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(purchaseTransactionalService.decrementStockAndCreateOrder(20L, 10L))
                .thenThrow(new ProductNotFoundException("Product not found"));

        ProductNotFoundException exception = assertThrows(
                ProductNotFoundException.class,
                () -> purchaseService.purchase(request));

        assertEquals("Product not found", exception.getMessage());
        verify(purchaseTransactionalService).decrementStockAndCreateOrder(20L, 10L);
        verify(lock).unlock();
    }

    @Test
    void purchase_retriesAndSucceedsAfterOptimisticLockFailure() throws Exception {
        PurchaseRequest request = PurchaseRequest.builder()
                .userId(10L)
                .productId(20L)
                .build();
        Order savedOrder = Order.builder()
                .id(55L)
                .userId(10L)
                .productId(20L)
                .orderStatus(OrderStatus.CONFIRMED)
                .creationTimestamp(LocalDateTime.of(2026, 6, 27, 12, 0))
                .build();

        when(redissonClient.getLock("product-lock:20")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(purchaseTransactionalService.decrementStockAndCreateOrder(20L, 10L))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 20L))
                .thenReturn(savedOrder);

        PurchaseResponse response = purchaseService.purchase(request);

        assertEquals(55L, response.getOrderId());
        verify(purchaseTransactionalService, times(2)).decrementStockAndCreateOrder(20L, 10L);
        verify(lock).unlock();
    }

    @Test
    void purchase_throwsConcurrentStockUpdateExceptionWhenRetriesExhausted() throws Exception {
        PurchaseRequest request = PurchaseRequest.builder()
                .userId(10L)
                .productId(20L)
                .build();

        when(redissonClient.getLock("product-lock:20")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(purchaseTransactionalService.decrementStockAndCreateOrder(20L, 10L))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 20L));

        ConcurrentStockUpdateException exception = assertThrows(
                ConcurrentStockUpdateException.class,
                () -> purchaseService.purchase(request));

        assertEquals("Could not reserve stock after 3 concurrent retries", exception.getMessage());
        verify(purchaseTransactionalService, times(3)).decrementStockAndCreateOrder(20L, 10L);
        verify(lock).unlock();
    }

    @Test
    void purchase_throwsWhenLockCannotBeAcquired() throws Exception {
        PurchaseRequest request = PurchaseRequest.builder()
                .userId(10L)
                .productId(20L)
                .build();

        when(redissonClient.getLock("product-lock:20")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(false);

        LockAcquisitionException exception = assertThrows(
                LockAcquisitionException.class,
                () -> purchaseService.purchase(request));

        assertEquals("Too many requests", exception.getMessage());
        verify(redissonClient).getLock("product-lock:20");
        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verifyNoMoreInteractions(redissonClient, lock, purchaseTransactionalService);
    }
}
