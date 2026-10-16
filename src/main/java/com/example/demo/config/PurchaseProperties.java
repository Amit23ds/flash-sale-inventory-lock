package com.example.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.purchase")
public record PurchaseProperties(
        @DefaultValue("3") int maxOptimisticRetries,
        @DefaultValue("5ms") Duration retryBackoffMin,
        @DefaultValue("25ms") Duration retryBackoffMax) {
}
