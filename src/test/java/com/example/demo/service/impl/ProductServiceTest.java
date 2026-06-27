package com.example.demo.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.demo.dto.StockResponse;
import com.example.demo.entity.Product;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.repository.ProductRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductServiceImpl productService;

    @Test
    void getAvailableStock_returnsStockWhenProductExists() {
        Product product = Product.builder()
                .id(1L)
                .productName("Keyboard")
                .availableStock(15)
                .build();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        StockResponse response = productService.getAvailableStock(1L);

        assertEquals(1L, response.getProductId());
        assertEquals(15, response.getAvailableStock());
        verify(productRepository).findById(1L);
        verifyNoMoreInteractions(productRepository);
    }

    @Test
    void getAvailableStock_throwsWhenProductMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        ProductNotFoundException exception = assertThrows(
                ProductNotFoundException.class,
                () -> productService.getAvailableStock(99L));

        assertEquals("Product not found", exception.getMessage());
        verify(productRepository).findById(99L);
        verifyNoMoreInteractions(productRepository);
    }
}