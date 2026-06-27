package com.example.demo.service;

import com.example.demo.dto.StockResponse;
import com.example.demo.entity.Product;

public interface ProductService {

    Product getProductById(Long productId);

    StockResponse getAvailableStock(Long productId);
}