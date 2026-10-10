package com.rohit.nyvra.income;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link PayslipStorage} backed by a directory on the local disk, used for local development and tests.
 * The directory comes from {@code nyvra.income.payslip-storage-dir} (default: a folder under the JVM's
 * temp directory). Production should supply an S3/MinIO implementation instead.
 */
@Component
public class LocalPayslipStorage implements PayslipStorage {

    /** Directory every object is stored under. */
    private final Path root;

    /**
     * Creates the storage.
     *
     * @param directory the storage directory; created on first write
     */
    public LocalPayslipStorage(
            @Value("${nyvra.income.payslip-storage-dir:${java.io.tmpdir}/nyvra-payslips}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream content, String contentType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /**
     * Maps a key to a path inside the storage directory.
     *
     * @param key the object key
     * @return the file path
     * @throws IOException if the key would escape the storage directory
     */
    private Path resolve(String key) throws IOException {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IOException("Object key escapes the storage directory");
        }
        return path;
    }
}
