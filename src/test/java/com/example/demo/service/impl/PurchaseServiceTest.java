package com.example.demo.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.dto.PurchaseResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.LockAcquisitionException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

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
        Product product = Product.builder()
                .id(20L)
                .productName("Mouse")
                .availableStock(3)
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
        when(productRepository.findById(20L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        PurchaseResponse response = purchaseService.purchase(request);

        assertEquals(55L, response.getOrderId());
        assertEquals(10L, response.getUserId());
        assertEquals(20L, response.getProductId());
        assertEquals(OrderStatus.CONFIRMED, response.getOrderStatus());
        assertEquals(LocalDateTime.of(2026, 6, 27, 12, 0), response.getCreatedAt());

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(redissonClient).getLock("product-lock:20");
        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verify(productRepository).findById(20L);
        verify(productRepository).save(productCaptor.capture());
        verify(orderRepository).save(orderCaptor.capture());
        verify(lock).unlock();
        verifyNoMoreInteractions(redissonClient, lock, productRepository, orderRepository);

        assertEquals(2, productCaptor.getValue().getAvailableStock());
        assertEquals(10L, orderCaptor.getValue().getUserId());
        assertEquals(20L, orderCaptor.getValue().getProductId());
        assertEquals(OrderStatus.CONFIRMED, orderCaptor.getValue().getOrderStatus());
    }

    @Test
    void purchase_throwsWhenProductIsOutOfStock() throws Exception {
        PurchaseRequest request = PurchaseRequest.builder()
                .userId(10L)
                .productId(20L)
                .build();
        Product product = Product.builder()
                .id(20L)
                .productName("Mouse")
                .availableStock(0)
                .build();

        when(redissonClient.getLock("product-lock:20")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(productRepository.findById(20L)).thenReturn(Optional.of(product));

        OutOfStockException exception = assertThrows(
                OutOfStockException.class,
                () -> purchaseService.purchase(request));

        assertEquals("Out of stock", exception.getMessage());
        verify(redissonClient).getLock("product-lock:20");
        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verify(productRepository).findById(20L);
        verify(lock).unlock();
        verifyNoMoreInteractions(redissonClient, lock, productRepository, orderRepository);
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
        verifyNoMoreInteractions(redissonClient, lock, productRepository, orderRepository);
    }
}