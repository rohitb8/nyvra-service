package com.rohit.nyvra.income;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.MinioException;

/**
 * {@link PayslipStorage} backed by an S3-compatible bucket (MinIO locally, S3 elsewhere), selected with
 * {@code nyvra.income.payslip-storage.type=s3}. The bucket must already exist; docker-compose creates
 * {@code nyvra-documents} for local development.
 */
public class S3PayslipStorage implements PayslipStorage {

    /** Part size the client uses when the content length is unknown; above the 5 MB upload cap. */
    private static final long PART_SIZE = 10L * 1024 * 1024;

    /** Client for the S3 API. */
    private final MinioClient client;

    /** Bucket every object is stored in. */
    private final String bucket;

    /**
     * Creates the storage.
     *
     * @param client the S3 client
     * @param bucket the bucket that holds the payslips
     */
    public S3PayslipStorage(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, InputStream content, String contentType) throws IOException {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(content, -1, PART_SIZE).contentType(contentType).build());
        } catch (MinioException | GeneralSecurityException e) {
            throw new IOException("Could not store payslip object " + key, e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (MinioException | GeneralSecurityException e) {
            throw new IOException("Could not read payslip object " + key, e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (MinioException | GeneralSecurityException e) {
            throw new IOException("Could not delete payslip object " + key, e);
        }
    }
}
