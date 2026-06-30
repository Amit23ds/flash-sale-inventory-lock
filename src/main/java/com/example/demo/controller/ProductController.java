package com.example.demo.controller;

import com.example.demo.dto.StockResponse;
import com.example.demo.entity.Product;
import com.example.demo.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/stock/{productId}")
    public ResponseEntity<StockResponse> getStock(@PathVariable Long productId) {
        Product product = productService.getProductById(productId);

        StockResponse stockResponse = StockResponse.builder()
                .productId(product.getId())
                .availableStock(product.getAvailableStock())
                .build();

        return ResponseEntity.ok(stockResponse);
    }
}