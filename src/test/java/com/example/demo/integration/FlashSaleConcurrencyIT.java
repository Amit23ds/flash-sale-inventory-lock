package com.example.demo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.entity.Product;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
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

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();
        AtomicInteger throttled = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> submissions = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            final long userId = i + 1L;
            submissions.add(executor.submit(() -> {
                try {
                    startGate.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "http://localhost:" + port + "/api/v1/purchase",
                            new PurchaseRequest(userId, product.getId()),
                            String.class);
                    switch (response.getStatusCode().value()) {
                        case 200 -> confirmed.incrementAndGet();
                        case 409 -> outOfStock.incrementAndGet();
                        case 429 -> throttled.incrementAndGet();
                        default -> unexpected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startGate.countDown();
        for (Future<?> submission : submissions) {
            submission.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

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
