package com.example.demo.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.dto.PurchaseResponse;
import com.example.demo.entity.OrderStatus;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.service.ProductService;
import com.example.demo.service.PurchaseService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.redisson.api.RedissonClient;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

        @MockitoBean
    private PurchaseService purchaseService;

        @MockitoBean
        private ProductService productService;

        @MockitoBean
        private RedissonClient redissonClient;

    @Test
    void purchase_returnsResponseWhenSuccessful() throws Exception {
        PurchaseResponse response = PurchaseResponse.builder()
                .orderId(11L)
                .userId(5L)
                .productId(7L)
                .orderStatus(OrderStatus.CONFIRMED)
                .createdAt(LocalDateTime.of(2026, 6, 27, 10, 30))
                .build();
        when(purchaseService.purchase(org.mockito.ArgumentMatchers.any(PurchaseRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":5,"productId":7}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(11L))
                .andExpect(jsonPath("$.userId").value(5L))
                .andExpect(jsonPath("$.productId").value(7L))
                .andExpect(jsonPath("$.orderStatus").value("CONFIRMED"));
    }

    @Test
    void purchase_returns409WhenOutOfStock() throws Exception {
        when(purchaseService.purchase(org.mockito.ArgumentMatchers.any(PurchaseRequest.class)))
                .thenThrow(new OutOfStockException("Out of stock"));

        mockMvc.perform(post("/api/v1/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":5,"productId":7}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().string("Out of stock"));
    }

    @Test
    void purchase_returns400WhenRequestIsInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.userId").value("User ID must not be null"))
                .andExpect(jsonPath("$.productId").value("Product ID must not be null"));
    }
}