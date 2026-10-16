package com.example.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.redis.redisson")
public record RedissonConfigProperties(
        int connectionPoolSize,
        int connectionMinimumIdleSize,
        Duration timeout,
        // Defaults match Redisson 3.37.0's own BaseConfig defaults.
        @DefaultValue("3") int retryAttempts,
        @DefaultValue("1500ms") Duration retryInterval) {
}
