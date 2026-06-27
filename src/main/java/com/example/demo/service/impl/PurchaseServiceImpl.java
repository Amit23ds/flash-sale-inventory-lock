package com.example.demo.service.impl;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.dto.PurchaseResponse;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.Product;
import com.example.demo.exception.LockAcquisitionException;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.PurchaseService;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseServiceImpl implements PurchaseService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final RedissonClient redissonClient;

    @Override
    @Transactional
    public PurchaseResponse purchase(PurchaseRequest request) {
        RLock lock = redissonClient.getLock("product-lock:" + request.getProductId());
        boolean locked = false;

        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);
            if (!locked) {
                throw new LockAcquisitionException("Too many requests");
            }

            Product product = productRepository.findById(request.getProductId())
                    .orElseThrow(() -> new ProductNotFoundException("Product not found"));

            if (product.getAvailableStock() <= 0) {
                throw new OutOfStockException("Out of stock");
            }

            product.setAvailableStock(product.getAvailableStock() - 1);
            productRepository.save(product);

            Order order = Order.builder()
                    .userId(request.getUserId())
                    .productId(product.getId())
                    .orderStatus(OrderStatus.CONFIRMED)
                    .build();

            Order savedOrder = orderRepository.save(order);

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
}