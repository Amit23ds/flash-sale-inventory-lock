package com.example.demo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.entity.Product;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers

class FlashSaleConcurrencyIT {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("demo_db");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerMySql(registry, MYSQL);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @BeforeEach
    void resetState() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    void concurrentPurchases_neverOversellStock() throws Exception {
        int initialStock = 10;
        int concurrentRequests = 50;

        Product product = productRepository.save(Product.builder()
                .productName("Flash Sale Item")
                .availableStock(initialStock)
                .build());

        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();
        AtomicInteger throttled = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        IntegrationTestSupport.runConcurrently(concurrentRequests, Duration.ofSeconds(30), index -> {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "http://localhost:" + port + "/api/v1/purchase",
                    new PurchaseRequest(index + 1L, product.getId()),
                    String.class);
            switch (response.getStatusCode().value()) {
                case 200 -> confirmed.incrementAndGet();
                case 409 -> outOfStock.incrementAndGet();
                case 429 -> throttled.incrementAndGet();
                default -> unexpected.incrementAndGet();
            }
        });

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();

        assertEquals(0, unexpected.get(),
                "No request should have received an unexpected status code");
        assertEquals(0, refreshed.getAvailableStock(),
                "Final stock must be zero to prove no oversell");
        assertEquals(initialStock, confirmed.get(),
                "Confirmed purchases must equal initial stock");
        assertEquals(initialStock, orderRepository.count(),
                "Persisted orders must equal initial stock");
        assertEquals(concurrentRequests - initialStock,
                outOfStock.get() + throttled.get(),
                "Failed requests must equal excess demand");
    }
}
