package com.igirepay.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's @Scheduled support.
 * Required for the TTL-based eviction job in IdempotencyStore.
 */
@Configuration
@EnableScheduling
public class AppConfig {
    // All configuration is handled via application.properties
}
