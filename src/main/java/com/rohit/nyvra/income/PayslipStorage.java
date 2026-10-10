package com.rohit.nyvra.income;

import java.io.IOException;
import java.io.InputStream;

/**
 * Port for the object store that holds payslip files. The database keeps only the returned key, so a
 * different backend (MinIO/S3) can replace {@link LocalPayslipStorage} without touching the service.
 */
public interface PayslipStorage {

    /**
     * Stores a file.
     *
     * @param key         the object key to store it under
     * @param content     the file bytes; the caller closes the stream
     * @param contentType the file's media type
     * @throws IOException if the file cannot be written
     */
    void put(String key, InputStream content, String contentType) throws IOException;

    /**
     * Removes a file; a key that does not exist is ignored.
     *
     * @param key the object key
     * @throws IOException if the file exists but cannot be removed
     */
    void delete(String key) throws IOException;
}
