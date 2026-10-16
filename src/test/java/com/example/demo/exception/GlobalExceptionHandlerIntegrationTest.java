package com.example.demo.exception;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.service.ProductService;
import com.example.demo.service.PurchaseService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseService purchaseService;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private RedissonClient redissonClient;

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":5,"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Malformed request body"));
    }

    @Test
    void nonNumericProductId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/stock/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid value for parameter: productId"));
    }

    @Test
    void unsupportedHttpMethod_returns405() throws Exception {
        mockMvc.perform(delete("/api/v1/purchase"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(content().string("Method not allowed"));
    }

    @Test
    void unknownPath_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Not found"));
    }

    @Test
    void unexpectedException_returns500WithoutLeakingMessage() throws Exception {
        when(purchaseService.purchase(any(PurchaseRequest.class)))
                .thenThrow(new RuntimeException("db password is hunter2"));

        mockMvc.perform(post("/api/v1/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":5,"productId":7}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Internal server error"));
    }
}
