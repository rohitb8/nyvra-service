package com.rohit.nyvra.income;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the S3/MinIO payslip store, bound from {@code nyvra.income.payslip-storage.*}. Credentials come
 * from environment variables or the secret store, never from a committed file.
 *
 * @param type      {@code local} (default) or {@code s3}; only {@code s3} reads the other settings
 * @param endpoint  base URL of the S3 API, for example {@code http://localhost:9000} for MinIO
 * @param bucket    bucket that holds the payslips; it must already exist
 * @param accessKey access key id
 * @param secretKey secret access key
 * @param region    bucket region; blank lets the client use its default (MinIO ignores it)
 */
@ConfigurationProperties(prefix = "nyvra.income.payslip-storage")
public record PayslipStorageProperties(
    String type,
    String endpoint,
    String bucket,
    String accessKey,
    String secretKey,
    String region) {
}
