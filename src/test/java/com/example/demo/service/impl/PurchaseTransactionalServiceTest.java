package com.example.demo.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseTransactionalServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private PurchaseTransactionalService purchaseTransactionalService;

    @Test
    void decrementStockAndCreateOrder_returnsOrderWhenStockAvailable() {
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

        when(productRepository.findById(20L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        Order result = purchaseTransactionalService.decrementStockAndCreateOrder(20L, 10L);

        assertEquals(55L, result.getId());
        assertEquals(10L, result.getUserId());
        assertEquals(20L, result.getProductId());
        assertEquals(OrderStatus.CONFIRMED, result.getOrderStatus());

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(productRepository).findById(20L);
        verify(productRepository).save(productCaptor.capture());
        verify(orderRepository).save(orderCaptor.capture());
        verifyNoMoreInteractions(productRepository, orderRepository);

        assertEquals(2, productCaptor.getValue().getAvailableStock());
        assertEquals(10L, orderCaptor.getValue().getUserId());
        assertEquals(20L, orderCaptor.getValue().getProductId());
        assertEquals(OrderStatus.CONFIRMED, orderCaptor.getValue().getOrderStatus());
    }

    @Test
    void decrementStockAndCreateOrder_throwsWhenOutOfStock() {
        Product product = Product.builder()
                .id(20L)
                .productName("Mouse")
                .availableStock(0)
                .build();

        when(productRepository.findById(20L)).thenReturn(Optional.of(product));

        OutOfStockException exception = assertThrows(
                OutOfStockException.class,
                () -> purchaseTransactionalService.decrementStockAndCreateOrder(20L, 10L));

        assertEquals("Out of stock", exception.getMessage());
        verify(productRepository).findById(20L);
        verifyNoMoreInteractions(productRepository, orderRepository);
    }

    @Test
    void decrementStockAndCreateOrder_throwsWhenProductMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        ProductNotFoundException exception = assertThrows(
                ProductNotFoundException.class,
                () -> purchaseTransactionalService.decrementStockAndCreateOrder(99L, 10L));

        assertEquals("Product not found", exception.getMessage());
        verify(productRepository).findById(99L);
        verifyNoMoreInteractions(productRepository, orderRepository);
    }
}
