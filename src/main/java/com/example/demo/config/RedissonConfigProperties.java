package com.example.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.redis.redisson")
public record RedissonConfigProperties(
        int connectionPoolSize,
        int connectionMinimumIdleSize,
        Duration timeout) {
}