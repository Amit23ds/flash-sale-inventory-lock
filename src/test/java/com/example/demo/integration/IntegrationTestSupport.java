package com.example.demo.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared setup for the Testcontainers ITs. Each IT still owns its own containers. */
final class IntegrationTestSupport {

    private IntegrationTestSupport() {
    }

    static MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4.10"))
                .withDatabaseName("demo_db");
    }

    static void registerMySql(DynamicPropertyRegistry registry, MySQLContainer<?> mysql) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    /**
     * Runs {@code task} on {@code threads} threads that are all released at once by a
     * CountDownLatch, then waits up to {@code timeout} for each one. The task receives
     * its 0-based thread index. A task that throws fails the test.
     */
    static void runConcurrently(int threads, Duration timeout, IntConsumer task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<?>> submissions = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                submissions.add(executor.submit(() -> {
                    startGate.await();
                    task.accept(index);
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> submission : submissions) {
                submission.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
