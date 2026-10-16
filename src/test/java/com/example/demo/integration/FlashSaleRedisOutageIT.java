package com.example.demo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.dto.PurchaseRequest;
import com.example.demo.entity.Product;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.github.dockerjava.api.DockerClient;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Pauses Redis during a flash sale: purchases must degrade to optimistic locking only,
 * never return 5xx, and never oversell.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class FlashSaleRedisOutageIT {

    @Container
    static final MySQLContainer<?> MYSQL = IntegrationTestSupport.mysqlContainer();

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerMySql(registry, MYSQL);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Fail fast against the paused Redis so the test stays quick.
        registry.add("app.redis.redisson.timeout", () -> "500ms");
        registry.add("app.redis.redisson.retry-attempts", () -> 1);
        registry.add("app.redis.redisson.retry-interval", () -> "100ms");
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
    void concurrentPurchasesWithRedisPaused_neverReturn5xxAndNeverOversell() throws Exception {
        int initialStock = 10;
        int concurrentRequests = 50;

        Product product = productRepository.save(Product.builder()
                .productName("Flash Sale Item")
                .availableStock(initialStock)
                .build());

        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger serverErrors = new AtomicInteger();

        DockerClient docker = DockerClientFactory.instance().client();
        String redisId = REDIS.getContainerId();
        docker.pauseContainerCmd(redisId).exec();
        try {
            assertTrue(docker.inspectContainerCmd(redisId).exec().getState().getPaused(),
                    "Redis must be paused before purchases start");

            IntegrationTestSupport.runConcurrently(concurrentRequests, Duration.ofSeconds(60), index -> {
                ResponseEntity<String> response = restTemplate.postForEntity(
                        "http://localhost:" + port + "/api/v1/purchase",
                        new PurchaseRequest(index + 1L, product.getId()),
                        String.class);
                if (response.getStatusCode().value() == 200) {
                    confirmed.incrementAndGet();
                } else if (response.getStatusCode().is5xxServerError()) {
                    serverErrors.incrementAndGet();
                }
            });
        } finally {
            docker.unpauseContainerCmd(redisId).exec();
        }

        int finalStock = productRepository.findById(product.getId()).orElseThrow().getAvailableStock();
        long ordersInDb = orderRepository.count();

        assertEquals(0, serverErrors.get(), "A Redis outage must not surface as 5xx");
        assertTrue(finalStock >= 0, "Stock must never go negative");
        assertEquals(initialStock - finalStock, ordersInDb,
                "Every unit sold must have exactly one persisted order");
        assertEquals(ordersInDb, confirmed.get(),
                "Every 200 response must correspond to a persisted order");
        assertTrue(confirmed.get() >= 1,
                "Degraded mode must still sell stock while Redis is down");
    }
}
