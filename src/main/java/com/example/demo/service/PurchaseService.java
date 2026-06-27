package com.example.demo.service;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.dto.PurchaseResponse;

public interface PurchaseService {

    PurchaseResponse purchase(PurchaseRequest request);
}