package com.rohit.nyvra.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class BlindIndexHasherTest {

    @Test
    void isDeterministicPerKeyAndDiffersAcrossKeys() {
        byte[] keyA = new byte[32];
        byte[] keyB = new byte[32];
        Arrays.fill(keyB, (byte) 1);

        BlindIndexHasher hasherA = new BlindIndexHasher(keyA);

        assertThat(hasherA.hash("someone@example.com")).isEqualTo(hasherA.hash("someone@example.com"));
        assertThat(hasherA.hash("someone@example.com"))
            .isNotEqualTo(new BlindIndexHasher(keyB).hash("someone@example.com"))
            .hasSize(64);
    }
}
