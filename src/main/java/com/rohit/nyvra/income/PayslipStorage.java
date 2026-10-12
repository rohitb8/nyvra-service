package com.rohit.nyvra.income;

import java.io.IOException;
import java.io.InputStream;

/**
 * Port for the object store that holds payslip files. The database keeps only the returned key, so the
 * backend is swappable: {@link LocalPayslipStorage} for development and tests, {@link S3PayslipStorage} for
 * MinIO/S3.
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
     * Opens a stored file for reading.
     *
     * @param key the object key
     * @return a stream over the file bytes; the caller closes it
     * @throws IOException if the file does not exist or cannot be read
     */
    InputStream open(String key) throws IOException;

    /**
     * Removes a file; a key that does not exist is ignored.
     *
     * @param key the object key
     * @throws IOException if the file exists but cannot be removed
     */
    void delete(String key) throws IOException;
}
