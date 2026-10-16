package com.example.demo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.entity.Product;
import com.example.demo.exception.OutOfStockException;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.impl.PurchaseTransactionalService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Calls the transactional layer directly, with no Redis lock and no retries, to prove that
 * the JPA @Version check alone prevents overselling.
 */
@SpringBootTest
@Testcontainers
class OptimisticLockingIT {

    @Container
    static final MySQLContainer<?> MYSQL = IntegrationTestSupport.mysqlContainer();

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerMySql(registry, MYSQL);
    }

    // Redis is not used on this path; the mock keeps the context from connecting to it.
    @MockitoBean
    private RedissonClient redissonClient;

    @Autowired
    private PurchaseTransactionalService purchaseTransactionalService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void resetState() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    void concurrentDecrements_withoutLockOrRetry_neverOversell() throws Exception {
        int initialStock = 10;
        int concurrentCalls = 30;

        Product product = productRepository.save(Product.builder()
                .productName("Flash Sale Item")
                .availableStock(initialStock)
                .build());

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();
        AtomicInteger optimisticFailures = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        IntegrationTestSupport.runConcurrently(concurrentCalls, Duration.ofSeconds(60), index -> {
            try {
                purchaseTransactionalService.decrementStockAndCreateOrder(product.getId(), index + 1L);
                successes.incrementAndGet();
            } catch (OutOfStockException exception) {
                outOfStock.incrementAndGet();
            } catch (ObjectOptimisticLockingFailureException exception) {
                optimisticFailures.incrementAndGet();
            } catch (RuntimeException exception) {
                unexpected.incrementAndGet();
            }
        });

        int finalStock = productRepository.findById(product.getId()).orElseThrow().getAvailableStock();
        long ordersInDb = orderRepository.count();

        assertEquals(0, unexpected.get(), "Only out-of-stock and optimistic-lock failures are expected");
        assertEquals(concurrentCalls, successes.get() + outOfStock.get() + optimisticFailures.get(),
                "Every call must end in exactly one outcome");
        assertTrue(finalStock >= 0, "Stock must never go negative");
        assertEquals(initialStock - finalStock, successes.get(),
                "Every unit sold must correspond to a successful call");
        assertEquals(successes.get(), ordersInDb,
                "Every successful call must have exactly one persisted order");
    }
}
