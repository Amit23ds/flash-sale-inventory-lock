package com.example.demo.service.impl;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseTransactionalService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public Order decrementStockAndCreateOrder(Long productId, Long userId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));

        if (product.getAvailableStock() <= 0) {
            throw new OutOfStockException("Out of stock");
        }

        product.setAvailableStock(product.getAvailableStock() - 1);
        productRepository.save(product);

        Order order = Order.builder()
                .userId(userId)
                .productId(product.getId())
                .orderStatus(OrderStatus.CONFIRMED)
                .build();

        return orderRepository.save(order);
    }
}
