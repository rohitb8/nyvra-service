package com.rohit.nyvra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on {@code @Async} so work that should not hold up a request (such as re-categorising existing expenses
 * after a new rule) runs on Spring Boot's auto-configured application task executor.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {
}
