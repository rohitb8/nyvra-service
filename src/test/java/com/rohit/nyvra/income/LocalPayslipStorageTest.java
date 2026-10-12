package com.rohit.nyvra.income;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link LocalPayslipStorage}: round trip, idempotent delete, and path-escape protection. */
class LocalPayslipStorageTest {

    /** Scratch directory the storage writes under. */
    @TempDir
    Path directory;

    /**
     * A stored file can be read back, and deleting it twice is fine.
     *
     * @throws IOException on storage failure
     */
    @Test
    void storesOpensAndDeletes() throws IOException {
        LocalPayslipStorage storage = new LocalPayslipStorage(directory.toString());
        storage.put("a/b/file", new ByteArrayInputStream("hello".getBytes()), "application/pdf");

        try (InputStream in = storage.open("a/b/file")) {
            assertThat(new String(in.readAllBytes())).isEqualTo("hello");
        }
        storage.delete("a/b/file");
        storage.delete("a/b/file");
        assertThatThrownBy(() -> storage.open("a/b/file")).isInstanceOf(IOException.class);
    }

    /** A key that climbs out of the storage directory is refused for reads as well as writes. */
    @Test
    void refusesKeysThatEscapeTheDirectory() {
        LocalPayslipStorage storage = new LocalPayslipStorage(directory.toString());

        assertThatThrownBy(() -> storage.open("../outside")).isInstanceOf(IOException.class);
    }
}
