package com.rohit.nyvra.income;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the payslip store: binds {@link PayslipStorageProperties} and, when
 * {@code nyvra.income.payslip-storage.type=s3}, builds the S3 client and {@link S3PayslipStorage}. Otherwise
 * {@link LocalPayslipStorage} is the active store.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PayslipStorageProperties.class)
public class PayslipStorageConfig {

    /**
     * Builds the S3 client from the configured endpoint and credentials.
     *
     * @param properties the storage settings
     * @return the client
     */
    @Bean
    @ConditionalOnProperty(name = "nyvra.income.payslip-storage.type", havingValue = "s3")
    MinioClient payslipMinioClient(PayslipStorageProperties properties) {
        MinioClient.Builder builder = MinioClient.builder()
            .endpoint(properties.endpoint())
            .credentials(properties.accessKey(), properties.secretKey());
        if (properties.region() != null && !properties.region().isBlank()) {
            builder.region(properties.region());
        }
        return builder.build();
    }

    /**
     * Creates the S3-backed payslip store.
     *
     * @param client     the S3 client
     * @param properties the storage settings
     * @return the store
     */
    @Bean
    @ConditionalOnProperty(name = "nyvra.income.payslip-storage.type", havingValue = "s3")
    PayslipStorage s3PayslipStorage(MinioClient payslipMinioClient, PayslipStorageProperties properties) {
        return new S3PayslipStorage(payslipMinioClient, properties.bucket());
    }
}
