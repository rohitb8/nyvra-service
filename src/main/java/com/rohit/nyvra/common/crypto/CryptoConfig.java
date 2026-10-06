package com.rohit.nyvra.common.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the crypto beans once at startup, so a missing or malformed key fails the boot, not a request. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {

    @Bean
    FieldEncryptor fieldEncryptor(CryptoProperties properties) {
        return FieldEncryptor.from(properties);
    }

    @Bean
    BlindIndexHasher blindIndexHasher(CryptoProperties properties) {
        return BlindIndexHasher.from(properties);
    }
}
