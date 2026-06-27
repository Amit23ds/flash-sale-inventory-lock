package com.example.demo.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedissonConfigProperties.class)
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties, RedissonConfigProperties redissonProperties) {
        Config config = new Config();
        String redisAddress = buildRedisAddress(redisProperties);
        var singleServerConfig = config.useSingleServer();

        singleServerConfig
                .setAddress(redisAddress)
                .setConnectionPoolSize(redissonProperties.connectionPoolSize())
                .setConnectionMinimumIdleSize(redissonProperties.connectionMinimumIdleSize())
                .setTimeout((int) redissonProperties.timeout().toMillis());

        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            singleServerConfig.setPassword(redisProperties.getPassword());
        }

        return Redisson.create(config);
    }

    private String buildRedisAddress(RedisProperties redisProperties) {
        String host = redisProperties.getHost() == null || redisProperties.getHost().isBlank()
                ? "redis"
                : redisProperties.getHost();
        int port = redisProperties.getPort();
        return "redis://" + host + ":" + port;
    }
}