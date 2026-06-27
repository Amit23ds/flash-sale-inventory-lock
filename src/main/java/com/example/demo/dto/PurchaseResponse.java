package com.example.demo.dto;

import java.time.LocalDateTime;

import com.example.demo.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseResponse {

    private Long orderId;
    private Long userId;
    private Long productId;
    private OrderStatus orderStatus;
    private LocalDateTime createdAt;
}