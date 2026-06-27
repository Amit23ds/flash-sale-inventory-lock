package com.example.demo.service.impl;

import com.example.demo.dto.StockResponse;
import com.example.demo.entity.Product;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    @Override
    public Product getProductById(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));
    }

    @Override
    public StockResponse getAvailableStock(Long productId) {
        Product product = getProductById(productId);
        return StockResponse.builder()
                .productId(product.getId())
                .availableStock(product.getAvailableStock())
                .build();
    }
}