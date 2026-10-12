package com.rohit.nyvra.analytics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the analytics configuration properties so the rules fail fast at boot if malformed. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FinancialRules.class, HealthScoreInputRules.class})
public class AnalyticsConfig {
}
